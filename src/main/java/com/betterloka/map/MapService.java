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

    /**
     * Adds a waypoint and returns it as it was stored.
     *
     * <p>Never replaces or toggles off another: a label already used on that world gets a number
     * after it — {@code Forest 65}, then {@code Forest 65 (2)} — so a second mark in one territory
     * is a second waypoint, and each can still be found and deleted by its own name.
     */
    public synchronized Waypoint add(Waypoint waypoint) {
        String label = uniqueLabel(waypoint.world(), waypoint.label());
        Waypoint stored = label.equals(waypoint.label()) ? waypoint
                : new Waypoint(label, waypoint.world(), waypoint.x(), waypoint.y(), waypoint.z(),
                        waypoint.color(), waypoint.icon());
        waypoints.add(stored);
        saveWaypoints();
        return stored;
    }

    private String uniqueLabel(String world, String base) {
        String label = base;
        for (int n = 2; isSet(world, label); n++) {
            label = base + " (" + n + ")";
        }
        return label;
    }

    /**
     * The waypoints in the order a list shows them: continent by continent in the map's own order,
     * then by name with numbers counted as numbers, so Forest 9 comes before Forest 10. Waypoints on
     * a world the map does not know come last.
     */
    public static List<Waypoint> forListing(List<Waypoint> waypoints) {
        List<Waypoint> sorted = new ArrayList<>(waypoints);
        sorted.sort(java.util.Comparator
                .comparingInt((Waypoint w) -> continentOrder(w.world()))
                .thenComparing(Waypoint::world, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Waypoint::label, MapService::compareNatural));
        return sorted;
    }

    private static int continentOrder(String world) {
        Continent continent = Continent.ofWorld(world);
        return continent == null ? Continent.values().length : continent.ordinal();
    }

    /** Text compared with runs of digits taken as numbers, ignoring case. */
    static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String runA = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String runB = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                int byLength = Integer.compare(runA.length(), runB.length());
                if (byLength != 0) {
                    return byLength;
                }
                int byDigits = runA.compareTo(runB);
                if (byDigits != 0) {
                    return byDigits;
                }
                continue;
            }
            int byChar = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (byChar != 0) {
                return byChar;
            }
            i++;
            j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
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
