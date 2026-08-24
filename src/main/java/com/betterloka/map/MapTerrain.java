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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The ground under the territories, stitched out of Loka's map tiles.
 *
 * <p>Downloaded on request, never on its own. Loka's Dynmap renders three zoom levels and the
 * coarsest of them is 128 blocks to a tile, so a continent is between eight hundred and two thousand
 * tiles — ten megabytes and a couple of thousand requests against somebody else's server. That is
 * fine to spend once, deliberately, and not fine to spend every time a screen opens.
 *
 * <p>What comes back is stitched straight into a single downscaled image: the full-size mosaic for
 * Kalros would be 7680 pixels across, and it is only ever drawn a few hundred wide.
 */
public final class MapTerrain {
    private static final String BASE_URL = "https://map.lokamc.com";

    /** Blocks per tile at the coarsest zoom Loka renders. Levels 3 and up do not exist. */
    private static final int BLOCKS_PER_TILE = 128;

    /** Dynmap's tile images are 128 square. */
    private static final int TILE_PIXELS = 128;

    /** Longest side of the stitched image. Well past what a map panel ever shows. */
    private static final int TARGET_LONG_SIDE = 1024;

    /** Kept modest: this is thousands of requests against a server doing us a favour. */
    private static final int PARALLEL_TILES = 4;

    /** One continent's ground, and where it sits in the world. */
    public record Terrain(Identifier texture, double minX, double minZ, double maxX, double maxZ) {
    }

    /** How a download is going, for the screen to show. */
    public record Progress(int done, int total, boolean running) {
        public int percent() {
            return total <= 0 ? 0 : done * 100 / total;
        }
    }

    private final HttpTransport transport;
    private final Path cacheDir;

    private final Map<Continent, Terrain> ready = new EnumMap<>(Continent.class);
    private final Map<Continent, Progress> progress = new EnumMap<>(Continent.class);
    /** Continents Loka has no tiles for, so the button stops offering what cannot be had. */
    private final java.util.Set<Continent> unavailable =
            java.util.EnumSet.noneOf(Continent.class);

    public MapTerrain(HttpTransport transport, Path cacheDir) {
        this.transport = transport;
        this.cacheDir = cacheDir;
    }

    public synchronized Terrain get(Continent continent) {
        return ready.get(continent);
    }

    public synchronized Progress progressOf(Continent continent) {
        return progress.getOrDefault(continent, new Progress(0, 0, false));
    }

    public synchronized boolean isRunning(Continent continent) {
        return progressOf(continent).running();
    }

    /** True once a download has come back with nothing, which is a fact about Loka's map. */
    public synchronized boolean isUnavailable(Continent continent) {
        return unavailable.contains(continent);
    }

    /** @return roughly how many tiles a continent needs, for the button to say what it will cost. */
    public static int tileEstimate(List<MapTerritory> territories) {
        if (territories.isEmpty()) {
            return 0;
        }
        Bounds bounds = Bounds.of(territories);
        return bounds.tilesAcross() * bounds.tilesDown();
    }

    /**
     * Loads the ground for one continent: from the cached image if it is there, otherwise by
     * downloading every tile it covers.
     */
    public void load(Continent continent, List<MapTerritory> territories) {
        synchronized (this) {
            if (ready.containsKey(continent) || isRunning(continent) || territories.isEmpty()) {
                return;
            }
            progress.put(continent, new Progress(0, tileEstimate(territories), true));
        }
        transport.executor().execute(() -> {
            try {
                Bounds bounds = Bounds.of(territories);
                byte[] png = cached(continent);
                if (png == null) {
                    png = download(continent, bounds);
                }
                if (png != null) {
                    publish(continent, bounds, png);
                } else {
                    synchronized (this) {
                        unavailable.add(continent);
                    }
                }
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.warn("Could not build the terrain for {}", continent, e);
            } finally {
                synchronized (this) {
                    Progress was = progressOf(continent);
                    progress.put(continent, new Progress(was.done(), was.total(), false));
                }
            }
        });
    }

    private byte[] cached(Continent continent) {
        Path file = cacheDir.resolve(continent.name().toLowerCase(Locale.ROOT) + ".png");
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Fetches every tile the territories sit on and draws it into one downscaled image. */
    private byte[] download(Continent continent, Bounds bounds) {
        int across = bounds.tilesAcross();
        int down = bounds.tilesDown();
        int total = across * down;

        double scale = (double) TARGET_LONG_SIDE
                / Math.max(across * TILE_PIXELS, down * TILE_PIXELS);
        int width = Math.max(1, (int) (across * TILE_PIXELS * scale));
        int height = Math.max(1, (int) (down * TILE_PIXELS * scale));

        NativeImage mosaic = new NativeImage(width, height, true);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger pasted = new AtomicInteger();

        List<Thread> workers = new ArrayList<>();
        AtomicInteger next = new AtomicInteger();
        for (int worker = 0; worker < PARALLEL_TILES; worker++) {
            Thread thread = new Thread(() -> {
                int index;
                while ((index = next.getAndIncrement()) < total) {
                    int column = index % across;
                    int row = index / across;
                    byte[] tile = fetchTile(continent, bounds, column, row);
                    if (tile != null) {
                        paste(mosaic, tile, column, row, across, down);
                        pasted.incrementAndGet();
                    }
                    synchronized (MapTerrain.this) {
                        progress.put(continent, new Progress(done.incrementAndGet(), total, true));
                    }
                }
            }, "betterloka-terrain-" + worker);
            thread.setDaemon(true);
            workers.add(thread);
            thread.start();
        }
        for (Thread thread : workers) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mosaic.close();
                return null;
            }
        }

        // Not every continent has ground to fetch: Loka's Dynmap renders a world when somebody
        // walks it, and Rivina's flat map has no tiles at all — not at zoom two, not even at zoom
        // zero in the middle of the island. An empty mosaic published as terrain would replace a
        // readable map with a white sheet, so nothing is published and the screen says so.
        if (pasted.get() == 0) {
            BetterLoka.LOGGER.info("Loka has rendered no map tiles for {}", continent);
            mosaic.close();
            return null;
        }

        try {
            Path file = cacheDir.resolve(continent.name().toLowerCase(Locale.ROOT) + ".png");
            Files.createDirectories(cacheDir);
            mosaic.writeTo(file);
            return Files.readAllBytes(file);
        } catch (IOException e) {
            BetterLoka.LOGGER.warn("Could not cache the terrain for {}", continent, e);
            return null;
        } finally {
            mosaic.close();
        }
    }

    private byte[] fetchTile(Continent continent, Bounds bounds, int column, int row) {
        int tileX = bounds.firstTileX() + column * 4;
        int tileY = bounds.lastTileY() - row * 4;
        String url = BASE_URL + "/" + continent.instance() + "/tiles/" + continent.world()
                + "/flat/" + (tileX / 32) + "_" + (tileY / 32)
                + "/zz_" + tileX + "_" + tileY + ".jpg";
        try {
            byte[] bytes = transport.getBytes(url, true);
            // The server answers a missing tile with a tiny blank placeholder rather than a 404.
            return bytes.length > 300 ? bytes : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Draws one tile into its place, sampling down to whatever the mosaic has room for. */
    private static void paste(NativeImage mosaic, byte[] jpeg, int column, int row,
                              int across, int down) {
        try (NativeImage tile = NativeImage.read(new ByteArrayInputStream(jpeg))) {
            int cellWidth = mosaic.getWidth() / across;
            int cellHeight = mosaic.getHeight() / down;
            int originX = column * cellWidth;
            int originY = row * cellHeight;

            for (int y = 0; y < cellHeight; y++) {
                for (int x = 0; x < cellWidth; x++) {
                    int sourceX = x * tile.getWidth() / Math.max(1, cellWidth);
                    int sourceY = y * tile.getHeight() / Math.max(1, cellHeight);
                    if (originX + x < mosaic.getWidth() && originY + y < mosaic.getHeight()) {
                        mosaic.setColorArgb(originX + x, originY + y,
                                tile.getColorArgb(sourceX, sourceY));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // One unreadable tile leaves a gap rather than losing the continent.
        }
    }

    private void publish(Continent continent, Bounds bounds, byte[] png) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
                Identifier id = Identifier.of(BetterLoka.MOD_ID,
                        "terrain_" + continent.name().toLowerCase(Locale.ROOT));
                client.getTextureManager().registerTexture(id,
                        new NativeImageBackedTexture(() -> "betterloka/terrain", image));
                synchronized (this) {
                    ready.put(continent, new Terrain(id, bounds.minX(), bounds.minZ(),
                            bounds.maxX(), bounds.maxZ()));
                }
            } catch (IOException | RuntimeException e) {
                BetterLoka.LOGGER.warn("Could not show the terrain for {}", continent, e);
            }
        });
    }

    /**
     * The tile grid a continent's territories sit on.
     *
     * <p>Tile coordinates run in multiples of four at this zoom, and the Z axis is inverted: Dynmap
     * counts its rows upwards while the world counts Z downwards.
     */
    record Bounds(int firstTileX, int lastTileX, int firstTileY, int lastTileY) {
        static Bounds of(List<MapTerritory> territories) {
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (MapTerritory territory : territories) {
                minX = Math.min(minX, territory.minX());
                maxX = Math.max(maxX, territory.maxX());
                minZ = Math.min(minZ, territory.minZ());
                maxZ = Math.max(maxZ, territory.maxZ());
            }
            return new Bounds(
                    align((int) Math.floor(minX / 32)),
                    align((int) Math.floor(maxX / 32)),
                    align((int) Math.floor(-maxZ / 32)),
                    align((int) Math.floor(-minZ / 32)));
        }

        /** Down to the nearest multiple of four, which is what a zoom-two tile is named by. */
        private static int align(int tile) {
            return Math.floorDiv(tile, 4) * 4;
        }

        int tilesAcross() {
            return (lastTileX - firstTileX) / 4 + 1;
        }

        int tilesDown() {
            return (lastTileY - firstTileY) / 4 + 1;
        }

        double minX() {
            return firstTileX * 32.0;
        }

        double maxX() {
            return (lastTileX + 4) * 32.0;
        }

        /** Row {@code lastTileY} is the top of the image and the smallest Z in the world. */
        double minZ() {
            return -(lastTileY + 4) * 32.0;
        }

        double maxZ() {
            return -firstTileY * 32.0;
        }
    }
}
