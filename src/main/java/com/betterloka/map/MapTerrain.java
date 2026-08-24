package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.api.HttpTransport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ground under the territories, one tile at a time, for whatever is on screen.
 *
 * <p>An earlier version downloaded a whole continent at Dynmap's coarsest zoom and stitched it into
 * one image. It produced a blank sheet every time, and measuring the tile server says why: of a
 * hundred tiles sampled in the middle of Kalros, zoom 0 has 24, zoom 1 has 11, and zoom 2 has none
 * at all. Loka renders the detailed level and almost nothing above it, so the coarse whole-continent
 * mosaic was fetching tiles that do not exist.
 *
 * <p>So the ground is fetched at zoom 0 for the window being looked at, and only for that window.
 * That is what makes it affordable — a zoomed-in view is a few dozen tiles rather than the thirty
 * thousand a whole continent would be — and it is why the map pans and zooms rather than showing
 * everything at once.
 */
public final class MapTerrain {
    private static final String BASE_URL = "https://map.lokamc.com";

    /** Blocks covered by one tile at the zoom Loka actually renders. */
    public static final int BLOCKS_PER_TILE = 32;

    /** How many tile textures to keep uploaded before dropping the least recently drawn. */
    private static final int MAX_TEXTURES = 512;

    /** Tiles asked for per frame, so a fast pan queues work instead of flooding the server. */
    private static final int FETCHES_PER_FRAME = 6;

    /** One tile of one world. */
    public record Key(String world, int x, int y) {
    }

    private final HttpTransport transport;
    private final Path cacheDir;

    /** Drawn textures, in the order they were last used, so the oldest can be dropped. */
    private final LinkedHashMap<Key, Identifier> textures =
            new LinkedHashMap<>(64, 0.75f, true);

    /** Tiles already asked for, whether they arrived or turned out not to exist. */
    private final Set<Key> attempted = ConcurrentHashMap.newKeySet();
    private final Set<Key> missing = ConcurrentHashMap.newKeySet();

    /** Reset every frame by the screen, to cap how much a single pan can kick off. */
    private final AtomicInteger fetchesThisFrame = new AtomicInteger();

    public MapTerrain(HttpTransport transport, Path cacheDir) {
        this.transport = transport;
        this.cacheDir = cacheDir;
    }

    /** Called once per frame before drawing, so the per-frame fetch budget starts fresh. */
    public void beginFrame() {
        fetchesThisFrame.set(0);
    }

    /**
     * The texture for one tile, if it is ready.
     *
     * <p>{@code null} means either not yet downloaded or not rendered by Loka at all; the map draws
     * the territory colours over a plain background there and carries on. Nothing blocks.
     */
    public Identifier tile(Continent continent, int tileX, int tileY) {
        Key key = new Key(continent.world(), tileX, tileY);
        synchronized (textures) {
            Identifier existing = textures.get(key);
            if (existing != null) {
                return existing;
            }
        }
        if (missing.contains(key)) {
            return null;
        }
        if (attempted.add(key)) {
            if (fetchesThisFrame.incrementAndGet() <= FETCHES_PER_FRAME) {
                fetch(continent, key);
            } else {
                // Over budget for this frame: forget the attempt so a later frame picks it up.
                attempted.remove(key);
            }
        }
        return null;
    }

    private void fetch(Continent continent, Key key) {
        transport.executor().execute(() -> {
            byte[] jpeg = load(continent, key);
            if (jpeg == null) {
                missing.add(key);
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> register(key, jpeg));
            }
        });
    }

    private byte[] load(Continent continent, Key key) {
        Path file = cacheDir.resolve(key.world()).resolve(key.x() + "_" + key.y() + ".jpg");
        try {
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
        } catch (IOException e) {
            BetterLoka.LOGGER.debug("Could not read cached tile {}", key, e);
        }

        String url = BASE_URL + "/" + continent.instance() + "/tiles/" + continent.world()
                + "/flat/" + (key.x() / 32) + "_" + (key.y() / 32)
                + "/" + key.x() + "_" + key.y() + ".jpg";
        try {
            byte[] bytes = transport.getBytes(url, true);
            // A tile Loka never rendered comes back as a tiny blank placeholder, not a 404.
            if (bytes.length <= 300) {
                return null;
            }
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    private void register(Key key, byte[] jpeg) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(jpeg));
            Identifier id = Identifier.of(BetterLoka.MOD_ID, "terrain_" + key.world()
                    .toLowerCase(Locale.ROOT) + "_" + tag(key.x()) + "_" + tag(key.y()));
            MinecraftClient client = MinecraftClient.getInstance();
            client.getTextureManager().registerTexture(id,
                    new NativeImageBackedTexture(() -> "betterloka/terrain", image));

            Map.Entry<Key, Identifier> evicted = null;
            synchronized (textures) {
                textures.put(key, id);
                if (textures.size() > MAX_TEXTURES) {
                    var iterator = textures.entrySet().iterator();
                    Map.Entry<Key, Identifier> oldest = iterator.next();
                    evicted = Map.entry(oldest.getKey(), oldest.getValue());
                    iterator.remove();
                }
            }
            if (evicted != null) {
                // Dropped from the atlas as well, or a long pan would grow without bound.
                client.getTextureManager().destroyTexture(evicted.getValue());
                attempted.remove(evicted.getKey());
            }
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not decode tile {}", key, e);
        }
    }

    /** Identifiers allow no minus sign, so negative tile rows are spelled out. */
    private static String tag(int value) {
        return value < 0 ? "n" + (-value) : String.valueOf(value);
    }

    /** World X of a tile's left edge. */
    public static double tileWorldX(int tileX) {
        return tileX * (double) BLOCKS_PER_TILE;
    }

    /**
     * World Z of a tile's top edge.
     *
     * <p>Dynmap counts its rows upward while the world counts Z downward, so a row's top edge is the
     * more negative end of its span.
     */
    public static double tileWorldZ(int tileY) {
        return -(tileY + 1) * (double) BLOCKS_PER_TILE;
    }

    public static int tileXAt(double worldX) {
        return (int) Math.floor(worldX / BLOCKS_PER_TILE);
    }

    public static int tileYAt(double worldZ) {
        return (int) Math.floor(-worldZ / BLOCKS_PER_TILE) - 1;
    }
}
