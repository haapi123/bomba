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
            new TypeToken<Map<String, List<StoredTerritory>>>() { }.getType();
    private static final Type WAYPOINT_PAYLOAD = new TypeToken<List<Waypoint>>() { }.getType();

    /**
     * The disk form of a territory.
     *
     * <p>A record of its own rather than {@link MapTerritory} directly, because arrays in a record
     * do not survive a Gson round trip as anything worth trusting — lists do.
     */
    private record StoredTerritory(String number, String areaName, String owner, String alliance,
                                   String mutator, List<Double> xs, List<Double> zs,
                                   double centerX, double centerZ, int fillColor) {
    }

    private final DynmapApi api;
    private final JsonStore<Map<String, List<StoredTerritory>>> territoryStore;
    private final JsonStore<List<Waypoint>> waypointStore;

    private final Map<Continent, List<MapTerritory>> loaded = new EnumMap<>(Continent.class);
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
            List<MapTerritory> cached = fromDisk(continent);
            if (cached != null) {
                return remember(continent, cached);
            }
            try {
                return remember(continent, api.fetchTerritories(continent));
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

    private List<MapTerritory> fromDisk(Continent continent) {
        Map<String, List<StoredTerritory>> saved = territoryStore.read();
        if (saved == null) {
            return null;
        }
        List<StoredTerritory> stored = saved.get(continent.name());
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        List<MapTerritory> territories = new ArrayList<>(stored.size());
        for (StoredTerritory one : stored) {
            territories.add(new MapTerritory(one.number(), one.areaName(), one.owner(),
                    one.alliance(), one.mutator(), toArray(one.xs()), toArray(one.zs()),
                    one.centerX(), one.centerZ(), one.fillColor()));
        }
        return territories;
    }

    private void saveToDisk() {
        Map<String, List<StoredTerritory>> out = new java.util.LinkedHashMap<>();
        synchronized (loaded) {
            loaded.forEach((continent, territories) -> {
                List<StoredTerritory> stored = new ArrayList<>(territories.size());
                for (MapTerritory territory : territories) {
                    stored.add(new StoredTerritory(territory.number(), territory.areaName(),
                            territory.owner(), territory.alliance(), territory.mutator(),
                            toList(territory.xs()), toList(territory.zs()),
                            territory.centerX(), territory.centerZ(), territory.fillColor()));
                }
                out.put(continent.name(), stored);
            });
        }
        try {
            territoryStore.write(out);
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not cache the map", e);
        }
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
