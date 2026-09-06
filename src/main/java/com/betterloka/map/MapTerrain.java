package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.api.HttpTransport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
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
 * Loka's own ground, the same tiles its website draws.
 *
 * <p>Two earlier versions of this fetched from {@code map.lokamc.com/{instance}/tiles/}, which is
 * where Dynmap serves tiles from and where that instance's marker icons really do live. It produced
 * a bare background, and the reason took a long time to find: the map's root {@code config.js}
 * carries a LiveAtlas block that repoints tiles — and only tiles — at a second host:
 *
 * <pre>
 *   tiles:   'https://assets.lokamc.com/static/images/map/'
 *   markers: 'https://map.lokamc.com/conquest/tiles/'
 * </pre>
 *
 * <p>What is left behind on the Dynmap host is a handful of tiles last written in January 2022 —
 * three of them across the whole of Rivina. The assets host has every tile of every continent at
 * every zoom, which is why the website has ground and this did not.
 */
public final class MapTerrain {
    /** Not the Dynmap host. See the class comment; this is the whole reason the map was blank. */
    private static final String BASE_URL = "https://assets.lokamc.com/static/images/map";

    /** Every tile is this many pixels square, at every zoom. */
    public static final int TILE_PIXELS = 128;

    /**
     * The coarsest zoom Loka renders, and the one the background is built from.
     *
     * <p>One tile covers 1024 blocks, so a whole continent is between 25 and 156 tiles — small
     * enough to have all of it on screen at once, which is what makes the ground complete the
     * moment the map opens instead of filling in square by square.
     */
    public static final int BASE_LEVEL = 5;

    /** How many tile textures to keep uploaded before dropping the least recently drawn. */
    private static final int MAX_TEXTURES = 768;

    /** Tiles asked for per frame, so a fast pan queues work instead of flooding the server. */
    private static final int FETCHES_PER_FRAME = 8;

    /** One tile: a world, a zoom, and a position. */
    public record Key(String world, int level, int x, int y) {
    }

    private final HttpTransport transport;
    private final Path cacheDir;

    /** Drawn textures, in the order they were last used, so the oldest can be dropped. */
    private final LinkedHashMap<Key, Identifier> textures = new LinkedHashMap<>(64, 0.75f, true);

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
     * <p>{@code null} means not downloaded yet, or a corner of the sea Loka never rendered; the map
     * leaves the coarser layer or the plain ocean showing there. Nothing blocks on a download.
     */
    public Identifier tile(Continent continent, int level, int tileX, int tileY) {
        Key key = new Key(continent.world(), level, tileX, tileY);
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
                fetch(key);
            } else {
                // Over budget for this frame: forget the attempt so a later frame picks it up.
                attempted.remove(key);
            }
        }
        return null;
    }

    /** One decoded tile: pixels, ready to be handed to the graphics card. */
    private record Pixels(int width, int height, int[] argb) {
    }

    private void fetch(Key key) {
        transport.executor().execute(() -> {
            byte[] jpeg = load(key);
            Pixels pixels = jpeg == null ? null : decode(key, jpeg);
            if (pixels == null) {
                missing.add(key);
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> register(key, pixels));
            }
        });
    }

    /**
     * Turns a tile into pixels, off the render thread.
     *
     * <p>Not {@code NativeImage.read}, which is the obvious call and silently cannot do this: it
     * checks for a PNG signature first and throws {@code Bad PNG Signature} on everything else.
     * Loka serves JPEG. That one check is why the ground was missing long after the tiles were
     * arriving and sitting in the cache directory.
     */
    private static Pixels decode(Key key, byte[] jpeg) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (image == null) {
                BetterLoka.LOGGER.debug("No decoder for tile {}", key);
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            return new Pixels(width, height,
                    image.getRGB(0, 0, width, height, null, 0, width));
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not decode tile {}", key, e);
            return null;
        }
    }

    private byte[] load(Key key) {
        Path file = cacheDir.resolve(key.world()).resolve(String.valueOf(key.level()))
                .resolve(key.x() + "_" + key.y() + ".jpg");
        try {
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
        } catch (IOException e) {
            BetterLoka.LOGGER.debug("Could not read cached tile {}", key, e);
        }

        try {
            byte[] bytes = transport.getBytes(url(key), true);
            // A tile Loka never rendered comes back as a 143-byte transparent PNG, not a 404.
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

    /**
     * The address LiveAtlas builds, followed exactly.
     *
     * <p>The directory is the tile position shifted five bits — this server ignores it and answers
     * from the filename alone, but it is what a browser sends and there is no reason to differ.
     */
    static String url(Key key) {
        return BASE_URL + "/" + key.world() + "/flat/"
                + (key.x() >> 5) + "_" + (key.y() >> 5) + "/"
                + zoomPrefix(key.level()) + key.x() + "_" + key.y() + ".jpg";
    }

    /** Dynmap's name for a zoomed-out tile: one {@code z} per level, then an underscore. */
    static String zoomPrefix(int level) {
        return level == 0 ? "" : "z".repeat(level) + "_";
    }

    private void register(Key key, Pixels pixels) {
        try {
            // Deliberately not closed: the texture takes ownership and frees it on eviction.
            NativeImage image = new NativeImage(pixels.width(), pixels.height(), false);
            for (int y = 0; y < pixels.height(); y++) {
                int row = y * pixels.width();
                for (int x = 0; x < pixels.width(); x++) {
                    image.setColorArgb(x, y, pixels.argb()[row + x]);
                }
            }
            Identifier id = Identifier.of(BetterLoka.MOD_ID, "terrain_"
                    + key.world().toLowerCase(Locale.ROOT) + "_" + key.level()
                    + "_" + tag(key.x()) + "_" + tag(key.y()));
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
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not upload tile {}", key, e);
        }
    }

    /** Identifiers allow no minus sign, so negative rows are spelled out. */
    private static String tag(int value) {
        return value < 0 ? "n" + (-value) : String.valueOf(value);
    }

    // --- the grid ---

    /** Blocks covered by one tile at a zoom: 32 at the sharpest, doubling each level out. */
    public static int blocksPerTile(int level) {
        return 32 << level;
    }

    /**
     * The zoom to draw detail from, for a given closeness.
     *
     * <p>Chosen so a tile lands on screen at roughly the 128 pixels it was drawn at: sharp without
     * asking for four times the tiles to shrink them. {@code BASE_LEVEL} is the floor because the
     * background is already drawn from there, and 0 the ceiling because that is as sharp as Loka
     * renders.
     */
    public static int levelFor(double blocksPerPixel) {
        int level = 0;
        while (level < BASE_LEVEL && blocksPerTile(level) / blocksPerPixel < TILE_PIXELS) {
            level++;
        }
        return level;
    }

    /**
     * Positions are counted in tiles of the sharpest zoom, whatever the level.
     *
     * <p>That is Dynmap's own convention — a tile two levels out is named for the sharp tile at its
     * corner, and its index is a multiple of four — and keeping it means one set of coordinates
     * lines the layers up instead of one per level.
     */
    public static int tileXAt(double worldX, int level) {
        return Math.floorDiv((int) Math.floor(worldX / 32.0), 1 << level) << level;
    }

    /**
     * Dynmap counts its rows upward while the world counts Z downward, so the sign flips here.
     *
     * <p>The flip is on the row index alone, not on which end of the row the name refers to: a row
     * is named for its northern edge and covers ground southward, exactly as a column is named for
     * its western edge and covers ground eastward. Negating the whole span instead — naming a row
     * for its southern edge — draws every continent one tile out of place, which on Balak is 512
     * blocks and puts the island clear of the territories drawn over it.
     */
    public static int tileYAt(double worldZ, int level) {
        int step = 1 << level;
        return -Math.floorDiv((int) Math.floor(worldZ / 32.0), step) * step;
    }

    /** World X of a tile's western edge; it covers {@link #blocksPerTile} eastward from there. */
    public static double tileWorldX(int tileX) {
        return tileX * 32.0;
    }

    /** World Z of a tile's northern edge; it covers {@link #blocksPerTile} southward from there. */
    public static double tileWorldZ(int tileY) {
        return -tileY * 32.0;
    }
}
