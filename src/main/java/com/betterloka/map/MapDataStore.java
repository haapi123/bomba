package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loka's map, held in memory for as long as the game is running.
 *
 * <p>The map screen used to own this: opening it started a download, and closing it threw the
 * result away. That made every visit a wait, and made a border that changed while the screen was
 * shut invisible until the next visit. The data lives here instead and the screen only draws it, so
 * opening the map is instant and what it shows is never older than the last refresh.
 *
 * <p>Refreshing is deliberately quiet. Dynmap answers "has anything changed since?" in about three
 * hundred bytes, so that question is asked on a short timer and the hundred-and-twenty-kilobyte
 * marker file is only refetched when the answer is yes — or when the data has gone stale enough
 * that asking again is worth it regardless. One scheduler, one request in flight per continent, and
 * a continent that keeps failing is backed off rather than hammered.
 *
 * <p>Nothing here blocks the render thread. {@link #snapshot} returns whatever is currently known,
 * immediately, including an empty snapshot before the first fetch lands.
 */
public final class MapDataStore {
    /** How long a disk copy is worth showing while a fresh one is fetched. */
    private static final long DISK_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** Refetch the whole marker file at least this often, whatever the pulse says. */
    private static final long MAX_AGE_MILLIS = 5 * 60 * 1000L;

    /** After this long without a successful fetch, the screen says so. */
    public static final long STALE_AFTER_MILLIS = 10 * 60 * 1000L;

    /** Failures back off from one interval to at most this many. */
    private static final int MAX_BACKOFF_TICKS = 10;

    private static final Type PAYLOAD = new TypeToken<StoredContinent>() { }.getType();

    /**
     * One continent, ready to draw.
     *
     * <p>The borders and the palette are worked out once here rather than per frame: both depend
     * only on the territory list, and rebuilding them while panning was costing the frame.
     */
    public record Snapshot(List<MapTerritory> territories, List<MapTown> towns,
                           TerritoryBorders borders, TownPalette palette,
                           TerritoryIndex index, long fetchedAt) {

        /** Nothing known yet: what a screen draws before the first fetch has landed. */
        public static Snapshot empty() {
            return EMPTY;
        }

        static Snapshot of(List<MapTerritory> territories, List<MapTown> towns, long fetchedAt) {
            List<String> owners = new ArrayList<>(territories.size());
            for (MapTerritory territory : territories) {
                owners.add(territory.owner());
            }
            return new Snapshot(List.copyOf(territories), List.copyOf(towns),
                    TerritoryBorders.of(territories), TownPalette.of(owners),
                    TerritoryIndex.of(territories), fetchedAt);
        }

        public boolean isEmpty() {
            return territories.isEmpty();
        }

        /** Whether this is old enough that the screen should say so. */
        public boolean stale(long now) {
            return fetchedAt > 0 && now - fetchedAt > STALE_AFTER_MILLIS;
        }
    }

    private static final Snapshot EMPTY =
            Snapshot.of(List.of(), List.of(), 0);

    /** One territory changing hands, for anything that wants to react to it. */
    public record Change(Continent continent, MapTerritory before, MapTerritory after) {
    }

    @FunctionalInterface
    public interface Listener {
        void onChanged(Continent continent, List<Change> changes);
    }

    /** The disk form: a continent's outlines and town cards, as they were last seen. */
    private record StoredContinent(List<StoredTerritory> territories, List<MapTown> towns) {
    }

    private record StoredTerritory(String number, String areaName, String owner, String alliance,
                                   String mutator, List<Double> xs, List<Double> zs,
                                   double centerX, double centerZ, int fillColor, int strokeColor,
                                   String icon, int conquestPoints) {
    }

    private final DynmapApi api;
    private final Path cacheDir;
    private final int refreshSeconds;

    private final Map<Continent, Snapshot> snapshots = new EnumMap<>(Continent.class);
    private final Map<Continent, Long> pulseStamps = new EnumMap<>(Continent.class);
    private final Map<Continent, Boolean> inFlight = new EnumMap<>(Continent.class);
    private final Map<Continent, Integer> backoff = new EnumMap<>(Continent.class);
    private final Map<Continent, Integer> skipped = new EnumMap<>(Continent.class);

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    public MapDataStore(DynmapApi api, Path cacheDir, int refreshSeconds) {
        this.api = api;
        this.cacheDir = cacheDir;
        this.refreshSeconds = Math.max(10, refreshSeconds);
    }

    /**
     * Brings up the store: yesterday's copy first, then the network.
     *
     * <p>Reading the cache happens off the calling thread too. It is fast, but "fast" on somebody
     * else's disk is not a promise worth making on the thread that starts the game.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BetterLoka-Map");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(this::loadFromDisk);
        // A short first delay so joining a server does not compete with everything else starting.
        scheduler.scheduleWithFixedDelay(this::tick, 5, refreshSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** What is known about a continent right now. Never blocks, never null. */
    public Snapshot snapshot(Continent continent) {
        synchronized (snapshots) {
            return snapshots.getOrDefault(continent, EMPTY);
        }
    }

    /** The card for the town holding a territory, if this continent's markers named one. */
    public MapTown town(Continent continent, String name) {
        if (name == null) {
            return null;
        }
        for (MapTown town : snapshot(continent).towns()) {
            if (town.name() != null && town.name().equalsIgnoreCase(name)) {
                return town;
            }
        }
        return null;
    }

    /**
     * The town whose seat this territory is, if any.
     *
     * <p>Loka's map never says so outright; what it publishes is a town marker at a position. The
     * territory that position falls inside is the one the town sits on.
     */
    public MapTown seatOf(Continent continent, MapTerritory territory) {
        if (territory == null) {
            return null;
        }
        for (MapTown town : snapshot(continent).towns()) {
            if (territory.contains(town.x(), town.z())) {
                return town;
            }
        }
        return null;
    }

    // --- refreshing ---

    /** One scheduler tick: ask each continent whether it needs anything, cheaply. */
    private void tick() {
        for (Continent continent : Continent.values()) {
            try {
                pollOne(continent);
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.debug("Map refresh failed for {}", continent, e);
            }
        }
    }

    private void pollOne(Continent continent) {
        synchronized (snapshots) {
            if (Boolean.TRUE.equals(inFlight.get(continent))) {
                return;
            }
            int wait = backoff.getOrDefault(continent, 0);
            if (wait > 0) {
                int done = skipped.merge(continent, 1, Integer::sum);
                if (done < wait) {
                    return;
                }
                skipped.put(continent, 0);
            }
            inFlight.put(continent, true);
        }

        try {
            Snapshot current = snapshot(continent);
            long age = current.fetchedAt() == 0
                    ? Long.MAX_VALUE : System.currentTimeMillis() - current.fetchedAt();

            boolean refetch = current.isEmpty() || age > MAX_AGE_MILLIS;
            if (!refetch) {
                long since = pulseStamps.getOrDefault(continent, 0L);
                DynmapApi.Pulse pulse = api.pulse(continent, since);
                pulseStamps.put(continent, pulse.timestamp());
                refetch = pulse.worthRefetching();
            }
            if (refetch) {
                fetchAndMerge(continent);
            }
            synchronized (snapshots) {
                backoff.put(continent, 0);
                skipped.put(continent, 0);
            }
        } catch (ApiException | RuntimeException e) {
            synchronized (snapshots) {
                int next = Math.min(MAX_BACKOFF_TICKS,
                        Math.max(1, backoff.getOrDefault(continent, 0) * 2));
                backoff.put(continent, next);
                skipped.put(continent, 0);
            }
            // Not a warning: the map being briefly unreachable is normal and the screen already
            // says how old what it is showing is.
            BetterLoka.LOGGER.debug("Could not refresh {}", continent, e);
        } finally {
            synchronized (snapshots) {
                inFlight.put(continent, false);
            }
        }
    }

    /**
     * Fetches a continent and folds it into what is already held.
     *
     * <p>The snapshot is replaced in one assignment rather than cleared and refilled, so a frame
     * drawn mid-refresh sees either the old map or the new one and never an empty one.
     */
    private void fetchAndMerge(Continent continent) throws ApiException {
        DynmapApi.ContinentData data = api.fetchContinent(continent);
        Snapshot before = snapshot(continent);
        Snapshot after = Snapshot.of(data.territories(), data.towns(), System.currentTimeMillis());

        synchronized (snapshots) {
            snapshots.put(continent, after);
        }
        saveToDisk(continent, after);

        List<Change> changes = diff(continent, before, after);
        if (!changes.isEmpty()) {
            for (Change change : changes) {
                BetterLoka.LOGGER.info("{} {} is now held by {}", continent.displayName(),
                        change.after().label(),
                        change.after().neutral() ? "nobody" : change.after().owner());
            }
            for (Listener listener : listeners) {
                try {
                    listener.onChanged(continent, changes);
                } catch (RuntimeException e) {
                    BetterLoka.LOGGER.debug("A map listener threw", e);
                }
            }
        }
    }

    /**
     * Feeds a continent a territory list directly, as if it had just been fetched.
     *
     * <p>Development only. It goes through the same merge and diff as a real refresh, which is the
     * point: it is how a capture can be shown to repaint one hex rather than the map.
     */
    public List<Change> devApply(Continent continent, List<MapTerritory> territories) {
        Snapshot before = snapshot(continent);
        Snapshot after = Snapshot.of(territories, before.towns(), System.currentTimeMillis());
        synchronized (snapshots) {
            snapshots.put(continent, after);
        }
        List<Change> changes = diff(continent, before, after);
        for (Listener listener : listeners) {
            listener.onChanged(continent, changes);
        }
        return changes;
    }

    /** Which territories changed hands between two snapshots. */
    static List<Change> diff(Continent continent, Snapshot before, Snapshot after) {
        if (before.isEmpty()) {
            return List.of();
        }
        Map<String, MapTerritory> old = new HashMap<>();
        for (MapTerritory territory : before.territories()) {
            old.put(territory.number(), territory);
        }
        List<Change> changes = new ArrayList<>();
        for (MapTerritory territory : after.territories()) {
            MapTerritory was = old.get(territory.number());
            if (was != null && !sameHolder(was, territory)) {
                changes.add(new Change(continent, was, territory));
            }
        }
        return List.copyOf(changes);
    }

    private static boolean sameHolder(MapTerritory a, MapTerritory b) {
        return java.util.Objects.equals(lower(a.owner()), lower(b.owner()))
                && java.util.Objects.equals(lower(a.alliance()), lower(b.alliance()));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    // --- disk ---

    private JsonStore<StoredContinent> storeFor(Continent continent) {
        return new JsonStore<>(cacheDir.resolve(continent.name().toLowerCase(Locale.ROOT) + ".json"),
                JsonStore.envelopeOf(PAYLOAD), DISK_TTL_MILLIS);
    }

    private void loadFromDisk() {
        for (Continent continent : Continent.values()) {
            try {
                StoredContinent stored = storeFor(continent).read();
                if (stored == null || stored.territories() == null
                        || stored.territories().isEmpty()) {
                    continue;
                }
                List<MapTerritory> territories = new ArrayList<>(stored.territories().size());
                for (StoredTerritory one : stored.territories()) {
                    territories.add(new MapTerritory(one.number(), one.areaName(), one.owner(),
                            one.alliance(), one.mutator(), toArray(one.xs()), toArray(one.zs()),
                            one.centerX(), one.centerZ(), one.fillColor(), one.strokeColor(),
                            one.icon(), one.conquestPoints()));
                }
                // Dated deliberately: a restored map should read as old until the network confirms
                // it, which is what puts the "as of" note on screen instead of a false all-clear.
                Snapshot restored = Snapshot.of(territories,
                        stored.towns() == null ? List.of() : stored.towns(), 1);
                synchronized (snapshots) {
                    snapshots.putIfAbsent(continent, restored);
                }
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.debug("Could not restore {} from disk", continent, e);
            }
        }
    }

    private void saveToDisk(Continent continent, Snapshot snapshot) {
        List<StoredTerritory> stored = new ArrayList<>(snapshot.territories().size());
        for (MapTerritory territory : snapshot.territories()) {
            stored.add(new StoredTerritory(territory.number(), territory.areaName(),
                    territory.owner(), territory.alliance(), territory.mutator(),
                    toList(territory.xs()), toList(territory.zs()),
                    territory.centerX(), territory.centerZ(), territory.fillColor(),
                    territory.strokeColor(), territory.icon(), territory.conquestPoints()));
        }
        try {
            storeFor(continent).write(new StoredContinent(stored, snapshot.towns()));
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not cache {}", continent, e);
        }
    }

    private static double[] toArray(List<Double> values) {
        if (values == null) {
            return new double[0];
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static List<Double> toList(double[] values) {
        List<Double> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(value);
        }
        return out;
    }
}
