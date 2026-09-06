package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The waypoints a player has set on Loka's map.
 *
 * <p>Loka's own data — the outlines, the towns, who holds what — lives in {@link MapDataStore},
 * which keeps it current whether or not the map is open. This is the other half: the marks the
 * player put down themselves, which nothing external ever changes and which expire when they say
 * so rather than on a clock.
 */
public final class MapService {
    private static final Type WAYPOINT_PAYLOAD = new TypeToken<List<Waypoint>>() { }.getType();

    private final JsonStore<List<Waypoint>> waypointStore;
    private final List<Waypoint> waypoints = new ArrayList<>();

    public MapService(Path waypointFile) {
        this.waypointStore = new JsonStore<>(waypointFile,
                JsonStore.envelopeOf(WAYPOINT_PAYLOAD), Long.MAX_VALUE);
        loadWaypoints();
    }

    public synchronized List<Waypoint> waypoints() {
        return List.copyOf(waypoints);
    }

    /** @return true if the waypoint is now set, false if this toggled an existing one off. */
    public synchronized boolean toggle(Waypoint waypoint) {
        boolean removed = waypoints.removeIf(existing ->
                existing.world().equals(waypoint.world())
                        && existing.label().equals(waypoint.label()));
        if (!removed) {
            waypoints.add(waypoint);
        }
        saveWaypoints();
        return !removed;
    }

    /** Removes one waypoint by the label it was set under. */
    public synchronized void remove(Waypoint waypoint) {
        waypoints.removeIf(existing -> existing.world().equals(waypoint.world())
                && existing.label().equals(waypoint.label()));
        saveWaypoints();
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
}
