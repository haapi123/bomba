package com.betterloka.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which territory is at a world position, without asking all of them.
 *
 * <p>Finding the hex under the pointer used to walk every territory on the continent and ray-cast
 * its outline: 143 polygons of twenty-six edges, every frame, while dragging. This buckets them
 * into a coarse grid once, so the same question costs a lookup and one or two ray casts.
 *
 * <p>The grid is deliberately crude. Territories are hexes a few hundred blocks across and a cell
 * holds every territory whose bounding box touches it, so a cell near a corner may list three or
 * four — which is still two orders of magnitude fewer than the whole continent.
 */
public final class TerritoryIndex {
    /** About half a territory across, so a hex spans a handful of cells rather than hundreds. */
    private static final int CELL_BLOCKS = 256;

    private record Cell(int x, int z) {
    }

    private final Map<Cell, List<MapTerritory>> cells;
    private final List<MapTerritory> all;

    private TerritoryIndex(Map<Cell, List<MapTerritory>> cells, List<MapTerritory> all) {
        this.cells = cells;
        this.all = all;
    }

    public static TerritoryIndex of(List<MapTerritory> territories) {
        Map<Cell, List<MapTerritory>> cells = new HashMap<>();
        for (MapTerritory territory : territories) {
            int fromX = cellOf(territory.minX());
            int toX = cellOf(territory.maxX());
            int fromZ = cellOf(territory.minZ());
            int toZ = cellOf(territory.maxZ());
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    cells.computeIfAbsent(new Cell(x, z), key -> new ArrayList<>()).add(territory);
                }
            }
        }
        return new TerritoryIndex(Map.copyOf(cells), List.copyOf(territories));
    }

    /** The territory containing a world position, or {@code null} for open sea. */
    public MapTerritory at(double worldX, double worldZ) {
        List<MapTerritory> candidates = cells.get(new Cell(cellOf(worldX), cellOf(worldZ)));
        if (candidates == null) {
            return null;
        }
        for (MapTerritory territory : candidates) {
            if (territory.contains(worldX, worldZ)) {
                return territory;
            }
        }
        return null;
    }

    /** Everything the index holds, in the order it was given. */
    public List<MapTerritory> all() {
        return all;
    }

    private static int cellOf(double world) {
        return (int) Math.floor(world / CELL_BLOCKS);
    }
}
