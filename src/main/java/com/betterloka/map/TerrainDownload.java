package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.api.HttpTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pulls a whole continent's ground onto the disk ahead of anybody looking at it.
 *
 * <p>The map already keeps every tile it draws, so this changes nothing about what is possible —
 * only about when the waiting happens. Panning across ground nobody has visited fetches it there and
 * then, which after the lane was widened is a second or two; downloading the continent first moves
 * that second or two to a minute somebody chose to spend, and thereafter the map is instant and works
 * with the server unreachable.
 *
 * <p>What it cannot do is improve the picture. {@link MapTerrain#BASE_LEVEL} down to level 0 is the
 * whole of what Loka renders, and the map already draws the finest level the screen can show. A
 * download that went deeper would have nothing to fetch.
 *
 * <p>It is polite about it. Tiles go down the map's own lane marked as background work, so a view
 * being looked at is served first; requests are capped in flight; every tile already settled is
 * skipped without a request, so stopping and starting again resumes rather than repeats.
 */
public final class TerrainDownload {
    /**
     * How deep a download goes by default: ground at one block per pixel.
     *
     * <p>Two levels short of the sharpest Loka renders, and the reason is arithmetic. Each level in
     * is four times the tiles: across the five continents level 2 is about 12,000 tiles and 48 MB,
     * level 1 is 49,000 and 187 MB, and level 0 is 194,000 and the better part of a gigabyte. Level
     * 2 covers every zoom from the whole island down to close work; the last two levels are what the
     * live fetch handles in the second it takes to lean in.
     */
    public static final int DEFAULT_DEEPEST_LEVEL = 2;

    /** How many tiles may be outstanding, so a queue cannot grow to the size of the continent. */
    private static final int IN_FLIGHT = 24;

    /** What a download is doing, as one immutable reading for the screen to draw. */
    public record Progress(Continent continent, int done, int total, long bytes, int fetched,
                           boolean running, boolean cancelled) {
        public static final Progress IDLE = new Progress(null, 0, 0, 0, 0, false, false);

        /** How far along, from 0 to 1. */
        public double fraction() {
            return total <= 0 ? 0 : Math.min(1.0, done / (double) total);
        }

        public boolean finished() {
            return !running && total > 0 && done >= total;
        }
    }

    private final HttpTransport transport;
    private final MapTerrain terrain;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger fetched = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private volatile int total;
    private volatile Continent continent;
    private volatile boolean everRan;
    private volatile long finishedAt;

    public TerrainDownload(HttpTransport transport, MapTerrain terrain) {
        this.transport = transport;
        this.terrain = terrain;
    }

    public boolean running() {
        return running.get();
    }

    /** Which continent is being downloaded, or was last, or {@code null} if none ever was. */
    public Continent continent() {
        return continent;
    }

    /** When the last download stopped, running out or cancelled, as a wall clock time. */
    public long finishedAt() {
        return finishedAt;
    }

    public Progress progress() {
        if (!everRan) {
            return Progress.IDLE;
        }
        return new Progress(continent, done.get(), total, bytes.get(), fetched.get(),
                running.get(), cancelled.get());
    }

    /** Asks the download to stop. It stops between tiles, so within a moment rather than instantly. */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * How many tiles a continent of this size would be, without fetching anything.
     *
     * <p>For telling somebody what they are about to spend before they spend it.
     */
    public static int tileCount(Bounds bounds, int deepestLevel) {
        int count = 0;
        for (int level = MapTerrain.BASE_LEVEL; level >= deepestLevel; level--) {
            count += rowsAndColumns(bounds, level);
        }
        return count;
    }

    /** The ground a continent covers, as the corners of its claims. */
    public record Bounds(double minX, double maxX, double minZ, double maxZ) {
        public boolean valid() {
            return maxX >= minX && maxZ >= minZ;
        }
    }

    private static int rowsAndColumns(Bounds bounds, int level) {
        int step = 1 << level;
        long columns = (MapTerrain.tileXAt(bounds.maxX(), level)
                - MapTerrain.tileXAt(bounds.minX(), level)) / step + 1;
        // Rows are numbered the other way up, so the northern edge gives the larger index.
        long rows = (MapTerrain.tileYAt(bounds.minZ(), level)
                - MapTerrain.tileYAt(bounds.maxZ(), level)) / step + 1;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, columns * rows));
    }

    /**
     * Starts a download, unless one is already running.
     *
     * @return whether this call started one
     */
    public boolean start(Continent continent, Bounds bounds, int deepestLevel) {
        if (!bounds.valid() || !running.compareAndSet(false, true)) {
            return false;
        }
        this.continent = continent;
        this.cancelled.set(false);
        this.done.set(0);
        this.fetched.set(0);
        this.bytes.set(0);
        this.total = tileCount(bounds, deepestLevel);
        this.everRan = true;

        Thread worker = new Thread(() -> {
            try {
                sweep(continent, bounds, deepestLevel);
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.warn("[betterloka] the map download stopped early", e);
            } finally {
                finishedAt = System.currentTimeMillis();
                running.set(false);
            }
        }, "BetterLoka-MapDownload");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * Walks the pyramid coarsest first.
     *
     * <p>That order is deliberate: the coarse levels are a handful of tiles and are what the map
     * shows when it opens, so a download stopped part-way still leaves the continent complete at the
     * zooms it had reached rather than sharp in one corner and blank everywhere else.
     */
    private void sweep(Continent continent, Bounds bounds, int deepestLevel) {
        Semaphore slots = new Semaphore(IN_FLIGHT);
        for (int level = MapTerrain.BASE_LEVEL; level >= deepestLevel; level--) {
            if (cancelled.get()) {
                break;
            }
            int step = 1 << level;
            int firstX = MapTerrain.tileXAt(bounds.minX(), level);
            int lastX = MapTerrain.tileXAt(bounds.maxX(), level);
            int firstY = MapTerrain.tileYAt(bounds.maxZ(), level);
            int lastY = MapTerrain.tileYAt(bounds.minZ(), level);

            List<int[]> batch = new ArrayList<>();
            for (int tileX = firstX; tileX <= lastX; tileX += step) {
                for (int tileY = firstY; tileY <= lastY; tileY += step) {
                    batch.add(new int[] {tileX, tileY});
                }
            }
            for (int[] tile : batch) {
                if (cancelled.get()) {
                    break;
                }
                submit(slots, continent, level, tile[0], tile[1]);
            }
        }
        // Wait for what is still in the air, so "running" means what it says.
        slots.acquireUninterruptibly(IN_FLIGHT);
    }

    private void submit(Semaphore slots, Continent continent, int level, int tileX, int tileY) {
        slots.acquireUninterruptibly();
        try {
            transport.tileExecutor().execute(() -> {
                try {
                    int size = terrain.download(continent, level, tileX, tileY);
                    if (size > 0) {
                        bytes.addAndGet(size);
                        fetched.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    BetterLoka.LOGGER.debug("Could not download tile {} {},{}", level, tileX, tileY, e);
                } finally {
                    done.incrementAndGet();
                    slots.release();
                }
            });
        } catch (RuntimeException e) {
            // The pool refused the work — shutting down. Give the slot back or the wait never ends.
            slots.release();
            throw e;
        }
    }
}
