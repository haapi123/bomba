package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.HttpTransport;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * The most tiles one detail layer may ask for, whatever the zoom says it wants.
     *
     * <p>A level in is four times the tiles, and on the widest continent framed whole that is enough
     * to overrun {@link #MAX_TEXTURES} — at which point every frame evicts tiles the next frame
     * wants back, and the map spends itself re-decoding ground it already had. Stepping the detail
     * layer out until it fits costs sharpness at one zoom on one continent; not stepping out costs
     * the frame rate everywhere. Sized to leave the coarse layer its share of the budget.
     */
    public static final int MAX_DETAIL_TILES = 420;

    /**
     * Tiles asked for per frame, so a fast pan queues work instead of arriving all at once.
     *
     * <p>Raised from eight once the tiles got their own lane: at eight a frame a screenful of ground
     * took several seconds just to be <em>asked</em> for, before a byte of it had moved. The pace
     * that matters is the lane's, not this, and this only stops a flung drag from queueing a
     * continent.
     */
    private static final int FETCHES_PER_FRAME = 64;

    /** One tile: a world, a zoom, and a position. */
    public record Key(String world, int level, int x, int y) {
    }

    private final HttpTransport transport;
    private final Path cacheDir;

    /** One uploaded tile, and the claims that were painted into it. */
    private record Uploaded(Identifier id, long claims) {
    }

    /** Drawn textures, in the order they were last used, so the oldest can be dropped. */
    private final LinkedHashMap<Key, Uploaded> textures = new LinkedHashMap<>(64, 0.75f, true);

    /** Tiles already asked for, whether they arrived or turned out not to exist. */
    private final Set<Key> attempted = ConcurrentHashMap.newKeySet();

    /**
     * Tiles found to hold nothing, and the claims they held nothing under.
     *
     * <p>Keyed to the claims because a square of empty sea stops being empty the moment a territory
     * is drawn across it — that tile then has to be made, out of plain water with the claim on top.
     */
    private final Map<Key, Long> missing = new ConcurrentHashMap<>();

    /** Tiles being repainted because the claims changed; the old texture stays up meanwhile. */
    private final Set<Key> repainting = ConcurrentHashMap.newKeySet();

    /** Where the claims for a world come from: the map's data store, read fresh each time. */
    private volatile java.util.function.Function<String, List<MapTerritory>> claimsOf = world -> List.of();

    /** The last claims list seen for each world and its digest, so the digest is worked out once. */
    private record Digest(List<MapTerritory> list, long value) {
    }

    private final Map<String, Digest> digests = new ConcurrentHashMap<>();

    /** Reset every frame by the screen, to cap how much a single pan can kick off. */
    private final AtomicInteger fetchesThisFrame = new AtomicInteger();

    public MapTerrain(HttpTransport transport, Path cacheDir) {
        this.transport = transport;
        this.cacheDir = cacheDir;
    }

    /** Tells the terrain where to find the claims it paints into each tile. */
    public void setClaims(java.util.function.Function<String, List<MapTerritory>> source) {
        this.claimsOf = source;
    }

    /** The claims for a world, and their digest, recomputing the digest only when the list changes. */
    private Digest claims(String world) {
        List<MapTerritory> list = claimsOf.apply(world);
        Digest known = digests.get(world);
        if (known != null && known.list() == list) {
            return known;
        }
        Digest fresh = new Digest(list, TerritoryOverlay.fingerprint(list));
        digests.put(world, fresh);
        if (known != null && known.value() != fresh.value()) {
            repaintAll(world, fresh);
        }
        return fresh;
    }

    /**
     * Repaints every uploaded tile of a world whose claims just changed, not only the ones on screen.
     *
     * <p>Waiting for each tile to be drawn again before repainting it — which is what happens
     * otherwise — leaves the ground away from the view painted with the old claims, and panning onto
     * it shows the old colours for a moment before they change. Measured after a faked capture: the
     * tiles in view repainted within a second and nearly two hundred elsewhere stayed stale. They are
     * all small jobs off the render thread, so doing them at once costs a fraction of a second.
     */
    private void repaintAll(String world, Digest claims) {
        java.util.List<Key> stale = new java.util.ArrayList<>();
        synchronized (textures) {
            for (Map.Entry<Key, Uploaded> entry : textures.entrySet()) {
                if (entry.getKey().world().equals(world) && entry.getValue().claims() != claims.value()) {
                    stale.add(entry.getKey());
                }
            }
        }
        for (Key key : stale) {
            if (repainting.add(key)) {
                fetch(key, claims);
            }
        }
    }

    /**
     * Development only: uploaded tiles of a world still painted under claims older than the current.
     *
     * <p>After the claims change this has to fall to zero on its own, or a capture would never show.
     */
    public int devStaleTiles(String world) {
        long current = claims(world).value();
        int stale = 0;
        synchronized (textures) {
            for (Map.Entry<Key, Uploaded> entry : textures.entrySet()) {
                if (entry.getKey().world().equals(world) && entry.getValue().claims() != current) {
                    stale++;
                }
            }
        }
        return stale;
    }

    /** Called once per frame before drawing, so the per-frame fetch budget starts fresh. */
    public void beginFrame() {
        fetchesThisFrame.set(0);
    }

    /** How many tiles of one level are uploaded, and how many were found not to exist. */
    public String stateOf(int level) {
        int ready = 0;
        synchronized (textures) {
            for (Key key : textures.keySet()) {
                if (key.level() == level) {
                    ready++;
                }
            }
        }
        int gone = 0;
        for (Key key : missing.keySet()) {
            if (key.level() == level) {
                gone++;
            }
        }
        return "ready=" + ready + " missing=" + gone;
    }

    /**
     * The texture for one tile, if it is ready.
     *
     * <p>{@code null} means not downloaded yet, or a corner of the sea Loka never rendered; the map
     * leaves the coarser layer or the plain ocean showing there. Nothing blocks on a download.
     */
    public Identifier tile(Continent continent, int level, int tileX, int tileY) {
        Key key = new Key(continent.world(), level, tileX, tileY);
        Digest claims = claims(key.world());
        Uploaded existing;
        synchronized (textures) {
            existing = textures.get(key);
        }
        if (existing != null) {
            // Painted under older claims: repaint in the background and keep showing this one, so a
            // capture recolours the map without it blinking out first.
            if (existing.claims() != claims.value() && repainting.add(key)) {
                if (fetchesThisFrame.incrementAndGet() <= FETCHES_PER_FRAME) {
                    fetch(key, claims);
                } else {
                    repainting.remove(key);
                }
            }
            return existing.id();
        }
        Long emptyUnder = missing.get(key);
        if (emptyUnder != null && emptyUnder == claims.value()) {
            return null;
        }
        if (attempted.add(key)) {
            if (fetchesThisFrame.incrementAndGet() <= FETCHES_PER_FRAME) {
                missing.remove(key);
                fetch(key, claims);
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

    /**
     * A tile that is sampled smoothly rather than in squares.
     *
     * <p>Minecraft's interface textures are nearest-neighbour, which is right for pixel art and wrong
     * for a rendered photograph of terrain: magnified it gives hard square steps, and shrunk it drops
     * whole texels and makes the coastline crawl as the map is panned. Loka's tiles are photographs,
     * so they get the sampler a photograph wants.
     *
     * <p>The sampler is set here rather than passed to the draw because {@code DrawContext} reads it
     * off the texture — it calls {@code getSampler()} and hands the result to the render state — so
     * the texture is the only place a choice can be made.
     */
    static final class SmoothTexture extends DynamicTexture {
        SmoothTexture(NativeImage image) {
            this("betterloka/terrain", image);
        }

        SmoothTexture(String label, NativeImage image) {
            super(() -> label, image);
            this.sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache()
                    .getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR);
        }
    }

    /**
     * Loads a tile, paints the claims into it, and hands it to the render thread.
     *
     * <p>All of it off the render thread: reading, decoding and painting are the expensive parts,
     * and the render thread only ever uploads finished pixels.
     */
    private void fetch(Key key, Digest claims) {
        transport.tileExecutor().execute(() -> {
            try {
                byte[] jpeg = load(key);
                Pixels pixels = jpeg == null ? null : decode(key, jpeg);
                double span = blocksPerTile(key.level());
                double left = tileWorldX(key.x());
                double top = tileWorldZ(key.y());
                if (pixels == null) {
                    // Nothing rendered here. If a claim runs across it, it still has to be drawn —
                    // over plain water, as the website draws it — so the tile is made from scratch.
                    if (!TerritoryOverlay.touches(left, top, span, claims.list())) {
                        missing.put(key, claims.value());
                        // Let go of the attempt: the missing entry is what holds it back now, and
                        // only while the claims stay the same — when they change it is tried again.
                        attempted.remove(key);
                        return;
                    }
                    pixels = new Pixels(TILE_PIXELS, TILE_PIXELS, TerritoryOverlay.ocean(TILE_PIXELS));
                }
                TerritoryOverlay.paint(pixels.argb(), pixels.width(), left, top,
                        span / pixels.width(), claims.list());
                Pixels painted = pixels;
                Minecraft client = Minecraft.getInstance();
                if (client != null) {
                    client.execute(() -> register(key, painted, claims.value()));
                }
            } finally {
                repainting.remove(key);
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

    /** Where one tile is kept. The cache is a plain tree of JPEGs, readable with any image viewer. */
    private Path fileFor(Key key) {
        return cacheDir.resolve(key.world()).resolve(String.valueOf(key.level()))
                .resolve(key.x() + "_" + key.y() + ".jpg");
    }

    /** Whether this tile has already been settled — held on disk, or known to be open sea. */
    public boolean onDisk(Key key) {
        return Files.isRegularFile(fileFor(key));
    }

    private byte[] load(Key key) {
        return load(key, false);
    }

    /**
     * One tile's bytes: from disk if it is there, otherwise fetched and written there.
     *
     * <p>{@code null} means there is nothing to draw — either the fetch failed, or Loka never
     * rendered that square because it is open sea.
     */
    private byte[] load(Key key, boolean background) {
        Path file = fileFor(key);
        try {
            if (Files.isRegularFile(file)) {
                // An empty file is the marker for a square Loka never rendered. Keeping it is what
                // stops a restart asking the server again for every corner of sea on a continent,
                // which on a whole-continent download is a quarter of the requests.
                if (Files.size(file) == 0) {
                    return null;
                }
                return Files.readAllBytes(file);
            }
        } catch (IOException e) {
            BetterLoka.LOGGER.debug("Could not read cached tile {}", key, e);
        }

        byte[] bytes;
        try {
            bytes = transport.getTile(url(key), background);
        } catch (ApiException e) {
            // A 404 is the server saying there is no such tile, which is settled and worth
            // remembering. Anything else is this connection's problem and must not be written down
            // as though the tile did not exist.
            if (e.notFound()) {
                markEmpty(file);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }

        // A square Loka never rendered comes back as a 143-byte transparent PNG, not a 404.
        if (bytes.length <= 300) {
            markEmpty(file);
            return null;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            BetterLoka.LOGGER.debug("Could not cache tile {}", key, e);
        }
        return bytes;
    }

    private static void markEmpty(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        } catch (IOException e) {
            BetterLoka.LOGGER.debug("Could not mark {} as empty", file, e);
        }
    }

    /**
     * Puts a continent's coarsest ground on disk, quietly, if it is not there already.
     *
     * <p>That level is what the map shows the moment a continent is opened — the whole island in a
     * few dozen tiles — and across all five continents it is under two hundred tiles and about a
     * megabyte. Fetching it once in the background after the game starts is what lets opening a
     * continent show the island straight away instead of square by square. Tiles already held are
     * skipped without a request, and the fetches go down the tile lane as background work.
     *
     * @return how many tiles were queued
     */
    public int warmBase(Continent continent, TerrainDownload.Bounds bounds) {
        if (!bounds.valid()) {
            return 0;
        }
        int level = BASE_LEVEL;
        int step = 1 << level;
        int queued = 0;
        int firstX = tileXAt(bounds.minX(), level);
        int lastX = tileXAt(bounds.maxX(), level);
        int firstY = tileYAt(bounds.maxZ(), level);
        int lastY = tileYAt(bounds.minZ(), level);
        for (int tileX = firstX; tileX <= lastX; tileX += step) {
            for (int tileY = firstY; tileY <= lastY; tileY += step) {
                if (onDisk(new Key(continent.world(), level, tileX, tileY))) {
                    continue;
                }
                int x = tileX;
                int y = tileY;
                transport.tileExecutor().execute(() -> download(continent, level, x, y));
                queued++;
            }
        }
        return queued;
    }

    /**
     * Pulls one tile into the cache without uploading it, for a whole-continent download.
     *
     * <p>Blocking, and meant for a background worker. Nothing is decoded and no texture is made:
     * twelve thousand tiles is far more than the graphics card is asked to hold, and the point of a
     * download is what is on disk afterwards.
     *
     * @return how many bytes were fetched, or zero for a tile already settled or found to be sea
     */
    public int download(Continent continent, int level, int tileX, int tileY) {
        Key key = new Key(continent.world(), level, tileX, tileY);
        if (onDisk(key)) {
            return 0;
        }
        byte[] bytes = load(key, true);
        return bytes == null ? 0 : bytes.length;
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

    private void register(Key key, Pixels pixels, long claims) {
        try {
            // Deliberately not closed: the texture takes ownership and frees it on eviction.
            NativeImage image = new NativeImage(pixels.width(), pixels.height(), false);
            for (int y = 0; y < pixels.height(); y++) {
                int row = y * pixels.width();
                for (int x = 0; x < pixels.width(); x++) {
                    image.setPixel(x, y, pixels.argb()[row + x]);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "terrain_"
                    + key.world().toLowerCase(Locale.ROOT) + "_" + key.level()
                    + "_" + tag(key.x()) + "_" + tag(key.y()));
            Minecraft client = Minecraft.getInstance();
            client.getTextureManager().register(id, new SmoothTexture(image));

            // Registering under an id already in use replaces and closes the old texture, which is
            // what lets a repaint swap in place.
            Map.Entry<Key, Uploaded> evicted = null;
            synchronized (textures) {
                textures.put(key, new Uploaded(id, claims));
                if (textures.size() > MAX_TEXTURES) {
                    var iterator = textures.entrySet().iterator();
                    Map.Entry<Key, Uploaded> oldest = iterator.next();
                    evicted = Map.entry(oldest.getKey(), oldest.getValue());
                    iterator.remove();
                }
            }
            if (evicted != null) {
                // Dropped from the atlas as well, or a long pan would grow without bound.
                client.getTextureManager().release(evicted.getValue().id());
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
     * <p>Chosen so a tile lands at roughly the 128 pixels it was drawn at: sharp without asking for
     * four times the tiles to shrink them. {@code BASE_LEVEL} is the floor because the background is
     * already drawn from there, and 0 the ceiling because that is as sharp as Loka renders.
     *
     * <p>The argument is blocks per <em>real</em> pixel, and that distinction is the whole of it. It
     * used to be handed blocks per GUI pixel, which on the interface scale most people play at is
     * three or four real pixels wide — so every tile was magnified three or four times over before
     * it reached the screen and the ground came out in visible squares no matter how far in the map
     * was zoomed. Reading the window's scale and dividing it out picks a level one or two steps
     * finer and is most of why the ground is now sharp.
     */
    public static int levelFor(double blocksPerPixel) {
        int level = 0;
        // Step out while the next level still has ground at least as fine as the screen asks for.
        // Testing the level already reached instead — which is what this did — stops at the first
        // one that is too coarse rather than the last one that is fine enough, so away from the
        // exact powers of two it was checked against it handed back tiles to be stretched: at 1.18
        // blocks a pixel it chose ground drawn at 2.0 and magnified it by three quarters.
        while (level < BASE_LEVEL && blocksPerTile(level + 1) <= blocksPerPixel * TILE_PIXELS) {
            level++;
        }
        return level;
    }

    /**
     * Development only: the level the old core would have picked, for a side-by-side photograph.
     *
     * <p>Two faults at once, which is why it is kept whole rather than described: it was handed
     * blocks per GUI pixel instead of per real pixel, and it stopped at the first level too coarse
     * rather than the last one fine enough.
     */
    public static int devLegacyLevelFor(double blocksPerGuiPixel) {
        int level = 0;
        while (level < BASE_LEVEL && blocksPerTile(level) / blocksPerGuiPixel < TILE_PIXELS) {
            level++;
        }
        return level;
    }

    /** How many blocks one real screen pixel covers, given a zoom stated in GUI pixels. */
    public static double perRealPixel(double blocksPerGuiPixel) {
        Minecraft client = Minecraft.getInstance();
        double scale = client == null || client.getWindow() == null
                ? 1.0 : client.getWindow().getGuiScale();
        return blocksPerGuiPixel / Math.max(1.0, scale);
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
