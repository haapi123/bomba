package com.betterloka.map;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which edges run inside a town's holding and which are the edge of it.
 *
 * <p>Loka's map has no notion of a town's outer boundary — it publishes hexes, each with its own
 * closed outline — so a town holding six territories is drawn as six rings and reads as six
 * separate claims. Working out which edges two of its own hexes share is what lets the outer
 * boundary be drawn heavily and the seams inside it faintly, so a holding reads as one shape.
 *
 * <p>Adjacent hexes share their vertices exactly: of 2151 distinct edges on Kalros, 1571 appear in
 * two territories. Coordinates are rounded to the block anyway, since an edge that failed to match
 * would only be drawn as a boundary — the safe way for this to be wrong.
 */
public final class TerritoryBorders {
    /** One edge, with its endpoints in a fixed order so both territories produce the same key. */
    private record Edge(int x1, int z1, int x2, int z2) {
    }

    private final Set<Edge> internal;

    private TerritoryBorders(Set<Edge> internal) {
        this.internal = internal;
    }

    public static TerritoryBorders of(List<MapTerritory> territories) {
        Map<Edge, String> owners = new HashMap<>();
        Set<Edge> internal = new HashSet<>();

        for (MapTerritory territory : territories) {
            if (territory.neutral()) {
                // Neutral hexes are all outlined the same way, so their seams need no marking.
                continue;
            }
            String owner = territory.owner().toLowerCase(Locale.ROOT);
            double[] xs = territory.xs();
            double[] zs = territory.zs();
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                Edge edge = key(xs[j], zs[j], xs[i], zs[i]);
                String seen = owners.put(edge, owner);
                if (seen != null && seen.equals(owner)) {
                    internal.add(edge);
                }
            }
        }
        return new TerritoryBorders(Set.copyOf(internal));
    }

    /** Whether this edge has the same town on both sides. */
    public boolean isInternal(double ax, double az, double bx, double bz) {
        return internal.contains(key(ax, az, bx, bz));
    }

    private static Edge key(double ax, double az, double bx, double bz) {
        int x1 = (int) Math.round(ax);
        int z1 = (int) Math.round(az);
        int x2 = (int) Math.round(bx);
        int z2 = (int) Math.round(bz);
        if (x1 > x2 || (x1 == x2 && z1 > z2)) {
            return new Edge(x2, z2, x1, z1);
        }
        return new Edge(x1, z1, x2, z2);
    }
}
