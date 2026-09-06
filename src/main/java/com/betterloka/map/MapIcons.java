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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keeps and towers Loka draws on its own map, fetched and shown here.
 *
 * <p>They are Loka's artwork, so they are fetched at runtime the way a browser viewing the map
 * fetches them, and cached in the config directory — never bundled into the jar. Sixteen pixels
 * square and nine of them in all, so the whole set is about ten kilobytes, once.
 */
public final class MapIcons {
    private static final String BASE_URL = "https://map.lokamc.com";

    /** Only these are ever requested, so a bad icon name in the marker data cannot drive fetches. */
    private static final Set<String> KNOWN = Set.of(
            "territory_neutral", "territory_owned", "territory_mutator",
            "town1", "town2", "town3", "yellowskull", "redskull", "anchor", "world",
            // Capitals and the Conquest maps' own markers. "worldcap" is Garama's world capital,
            // and without it the one town on the server that holds that title had no icon at all.
            "cap", "worldcap", "ship", "territory_buff");

    private final HttpTransport transport;
    private final Path cacheDir;

    /** What has been handed to the texture manager, and what has been tried and failed. */
    private final Map<String, Identifier> ready = new ConcurrentHashMap<>();
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();

    public MapIcons(HttpTransport transport, Path cacheDir) {
        this.transport = transport;
        this.cacheDir = cacheDir;
    }

    /**
     * The texture for one marker icon, if it is ready to draw.
     *
     * <p>Returns {@code null} the first time and starts fetching; the map draws without it and picks
     * it up on a later frame. Nothing here blocks the render thread on a download.
     */
    public Identifier get(String icon, Continent continent) {
        if (icon == null || !KNOWN.contains(icon)) {
            return null;
        }
        Identifier existing = ready.get(icon);
        if (existing != null) {
            return existing;
        }
        if (attempted.add(icon)) {
            fetch(icon, continent);
        }
        return null;
    }

    private void fetch(String icon, Continent continent) {
        transport.executor().execute(() -> {
            byte[] png = load(icon, continent);
            if (png == null) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return;
            }
            // Textures belong to the render thread, so the bytes cross over and the upload happens
            // there.
            client.execute(() -> register(icon, png));
        });
    }

    private byte[] load(String icon, Continent continent) {
        Path cached = cacheDir.resolve(icon + ".png");
        try {
            if (Files.isRegularFile(cached)) {
                return Files.readAllBytes(cached);
            }
        } catch (IOException e) {
            BetterLoka.LOGGER.debug("Could not read the cached icon {}", icon, e);
        }

        try {
            byte[] png = transport.getBytes(BASE_URL + "/" + continent.instance()
                    + "/tiles/_markers_/" + icon + ".png", true);
            Files.createDirectories(cacheDir);
            Files.write(cached, png);
            return png;
        } catch (Exception e) {
            BetterLoka.LOGGER.debug("Could not fetch the map icon {}", icon, e);
            return null;
        }
    }

    private void register(String icon, byte[] png) {
        try {
            // Deliberately not closed: the texture takes ownership of the image and frees it when
            // the texture manager drops it.
            NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
            Identifier id = Identifier.of(BetterLoka.MOD_ID,
                    "map_icon_" + icon.toLowerCase(Locale.ROOT));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id,
                    new NativeImageBackedTexture(() -> "betterloka/" + icon, image));
            ready.put(icon, id);
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not decode the map icon {}", icon, e);
        }
    }
}
