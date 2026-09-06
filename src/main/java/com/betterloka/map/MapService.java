package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Holds the map: the territories of each continent, and the waypoints set on them.
 *
 * <p>Outlines are cached to disk for a day. Borders move when a territory is captured, which is
 * often enough to matter and nowhere near often enough to re-download half a megabyte every time
 * somebody opens the screen.
 */
public final class MapService {
    /** Long enough that opening the map is free all evening, short enough to notice a capture. */
    private static final long TERRITORY_TTL_MILLIS = 24 * 60 * 60 * 1000L;

    private static final Type TERRITORY_PAYLOAD =
            new TypeToken<Map<String, StoredContinent>>() { }.getType();
    private static final Type WAYPOINT_PAYLOAD = new TypeToken<List<Waypoint>>() { }.getType();

    /**
     * One continent on disk: its outlines and its town cards together.
     *
     * <p>The towns used to be dropped on the way to disk, so a map restored from cache knew every
     * border and no owner — which left the panel unable to say whose seat a territory was for a day
     * at a time. A cache written in the older shape fails to parse and is simply refetched.
     */
    private record StoredContinent(List<StoredTerritory> territories, List<MapTown> towns) {
    }

    /**
     * The disk form of a territory.
     *
     * <p>A record of its own rather than {@link MapTerritory} directly, because arrays in a record
     * do not survive a Gson round trip as anything worth trusting — lists do.
     */
    private record StoredTerritory(String number, String areaName, String owner, String alliance,
                                   String mutator, List<Double> xs, List<Double> zs,
                                   double centerX, double centerZ, int fillColor, int strokeColor,
                                   String icon) {
    }

    private final DynmapApi api;
    private final JsonStore<Map<String, StoredContinent>> territoryStore;
    private final JsonStore<List<Waypoint>> waypointStore;

    private final Map<Continent, List<MapTerritory>> loaded = new EnumMap<>(Continent.class);
    /** Town cards, by lower-case name, so a territory's owner can be looked up on hover. */
    private final Map<Continent, Map<String, MapTown>> towns = new EnumMap<>(Continent.class);
    private final List<Waypoint> waypoints = new ArrayList<>();

    public MapService(DynmapApi api, Path territoryFile, Path waypointFile) {
        this.api = api;
        this.territoryStore = new JsonStore<>(territoryFile,
                JsonStore.envelopeOf(TERRITORY_PAYLOAD), TERRITORY_TTL_MILLIS);
        // Waypoints are the player's own and expire when they say so, not on a clock.
        this.waypointStore = new JsonStore<>(waypointFile,
                JsonStore.envelopeOf(WAYPOINT_PAYLOAD), Long.MAX_VALUE);
        loadWaypoints();
    }

    /**
     * The territories of one continent, from memory, then disk, then Loka's map.
     *
     * <p>Never runs on the render thread: the first call for a continent is an HTTP request.
     */
    public CompletableFuture<List<MapTerritory>> territories(Continent continent) {
        synchronized (loaded) {
            List<MapTerritory> inMemory = loaded.get(continent);
            if (inMemory != null) {
                return CompletableFuture.completedFuture(inMemory);
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            Map<String, StoredContinent> saved = territoryStore.read();
            StoredContinent cached = saved == null ? null : saved.get(continent.name());
            List<MapTerritory> restored = fromDisk(cached);
            if (restored != null) {
                rememberTowns(continent, cached.towns() == null ? List.of() : cached.towns());
                return remember(continent, restored);
            }
            try {
                DynmapApi.ContinentData data = api.fetchContinent(continent);
                rememberTowns(continent, data.towns());
                return remember(continent, data.territories());
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        }, api.executor());
    }

    private List<MapTerritory> remember(Continent continent, List<MapTerritory> territories) {
        synchronized (loaded) {
            loaded.put(continent, territories);
        }
        saveToDisk();
        return territories;
    }

    private static List<MapTerritory> fromDisk(StoredContinent cached) {
        if (cached == null) {
            return null;
        }
        List<StoredTerritory> stored = cached.territories();
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        List<MapTerritory> territories = new ArrayList<>(stored.size());
        for (StoredTerritory one : stored) {
            territories.add(new MapTerritory(one.number(), one.areaName(), one.owner(),
                    one.alliance(), one.mutator(), toArray(one.xs()), toArray(one.zs()),
                    one.centerX(), one.centerZ(), one.fillColor(), one.strokeColor(), one.icon()));
        }
        return territories;
    }

    private void saveToDisk() {
        Map<String, StoredContinent> out = new java.util.LinkedHashMap<>();
        synchronized (loaded) {
            loaded.forEach((continent, territories) -> {
                List<StoredTerritory> stored = new ArrayList<>(territories.size());
                for (MapTerritory territory : territories) {
                    stored.add(new StoredTerritory(territory.number(), territory.areaName(),
                            territory.owner(), territory.alliance(), territory.mutator(),
                            toList(territory.xs()), toList(territory.zs()),
                            territory.centerX(), territory.centerZ(), territory.fillColor(),
                            territory.strokeColor(), territory.icon()));
                }
                out.put(continent.name(), new StoredContinent(stored, townsOf(continent)));
            });
        }
        try {
            territoryStore.write(out);
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not cache the map", e);
        }
    }

    private void rememberTowns(Continent continent, List<MapTown> found) {
        Map<String, MapTown> byName = new java.util.HashMap<>();
        for (MapTown town : found) {
            if (town.name() != null) {
                byName.put(town.name().toLowerCase(java.util.Locale.ROOT), town);
            }
        }
        synchronized (towns) {
            towns.put(continent, byName);
        }
    }

    private List<MapTown> townsOf(Continent continent) {
        synchronized (towns) {
            Map<String, MapTown> byName = towns.get(continent);
            return byName == null ? List.of() : List.copyOf(byName.values());
        }
    }

    /** The card for the town holding a territory, if this continent's markers named one. */
    public MapTown town(Continent continent, String name) {
        if (name == null) {
            return null;
        }
        synchronized (towns) {
            Map<String, MapTown> byName = towns.get(continent);
            return byName == null ? null : byName.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * The town whose seat this territory is, if any.
     *
     * <p>Loka's map never says so outright; what it publishes is a town marker at a position. The
     * territory that position falls inside is the one the town sits on, and every other territory
     * that town holds has no marker of its own.
     */
    public MapTown seatOf(Continent continent, MapTerritory territory) {
        if (territory == null) {
            return null;
        }
        for (MapTown town : townsOf(continent)) {
            if (territory.contains(town.x(), town.z())) {
                return town;
            }
        }
        return null;
    }

    /** Removes one waypoint by the label it was set under. */
    public synchronized void remove(Waypoint waypoint) {
        waypoints.removeIf(existing -> existing.world().equals(waypoint.world())
                && existing.label().equals(waypoint.label()));
        saveWaypoints();
    }

    // --- waypoints ---

    public synchronized List<Waypoint> waypoints() {
        return List.copyOf(waypoints);
    }

    /** @return true if the waypoint is now set, false if this toggled an existing one off. */
    public synchronized boolean toggle(Waypoint waypoint) {
        boolean removed = waypoints.removeIf(existing ->
                existing.world().equals(waypoint.world()) && existing.label().equals(waypoint.label()));
        if (!removed) {
            waypoints.add(waypoint);
        }
        saveWaypoints();
        return !removed;
    }

    public synchronized boolean isSet(String world, String label) {
        return waypoints.stream()
                .anyMatch(w -> w.world().equals(world) && w.label().equals(label));
    }

    public synchronized void clear() {
        waypoints.clear();
        saveWaypoints();
    }

    private void loadWaypoints() {
        List<Waypoint> saved = waypointStore.read();
        if (saved != null) {
            waypoints.addAll(saved);
        }
    }

    private void saveWaypoints() {
        try {
            waypointStore.write(List.copyOf(waypoints));
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not save waypoints", e);
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
