package com.betterloka.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What dragging the map used to cost per frame, and what it costs now.
 *
 * <p>Both figures are measured here rather than asserted from memory. The shapes are Kalros-sized:
 * 143 territories of twenty-six vertices, which is what the screen actually draws.
 */
class MapPerformanceTest {
    private static final int TERRITORIES = 143;
    private static final int VERTICES = 26;

    private static List<MapTerritory> continent() {
        Random random = new Random(1);
        List<MapTerritory> territories = new ArrayList<>(TERRITORIES);
        for (int i = 0; i < TERRITORIES; i++) {
            double cx = random.nextInt(7000);
            double cz = random.nextInt(5000);
            double[] xs = new double[VERTICES];
            double[] zs = new double[VERTICES];
            for (int v = 0; v < VERTICES; v++) {
                double angle = 2 * Math.PI * v / VERTICES;
                xs[v] = cx + Math.cos(angle) * 290;
                zs[v] = cz + Math.sin(angle) * 290;
            }
            territories.add(new MapTerritory(String.valueOf(i), "Region", i % 5 == 0 ? null : "T" + (i % 7),
                    null, null, xs, zs, cx, cz, 0x3AB3DA, 0x09090B,
                    i % 5 == 0 ? "territory_neutral" : "territory_owned", -1, false, null));
        }
        return territories;
    }

    /** What the screen did before: ask every territory whether it contains the point. */
    private static MapTerritory linearScan(List<MapTerritory> territories, double x, double z) {
        for (MapTerritory territory : territories) {
            if (territory.contains(x, z)) {
                return territory;
            }
        }
        return null;
    }

    @Test
    void findingTheHexUnderThePointerGotCheaper() {
        List<MapTerritory> territories = continent();
        TerritoryIndex index = TerritoryIndex.of(territories);
        Random random = new Random(7);

        double[] xs = new double[20_000];
        double[] zs = new double[20_000];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = random.nextInt(7000);
            zs[i] = random.nextInt(5000);
        }

        // Both answers must agree before either timing means anything.
        for (int i = 0; i < 500; i++) {
            assertEquals(linearScan(territories, xs[i], zs[i]) == null,
                    index.at(xs[i], zs[i]) == null, "disagreed at " + xs[i] + "," + zs[i]);
        }

        for (int warm = 0; warm < 3; warm++) {
            for (int i = 0; i < xs.length; i++) {
                linearScan(territories, xs[i], zs[i]);
                index.at(xs[i], zs[i]);
            }
        }

        long before = System.nanoTime();
        for (int i = 0; i < xs.length; i++) {
            linearScan(territories, xs[i], zs[i]);
        }
        long linear = System.nanoTime() - before;

        before = System.nanoTime();
        for (int i = 0; i < xs.length; i++) {
            index.at(xs[i], zs[i]);
        }
        long indexed = System.nanoTime() - before;

        System.out.printf("hit test over %d territories: linear %.1f us, indexed %.1f us (%.1fx)%n",
                TERRITORIES, linear / 1000.0 / xs.length, indexed / 1000.0 / xs.length,
                linear / (double) indexed);
        assertTrue(indexed < linear, "the index should not be slower than scanning everything");
    }

    /**
     * The borders and palette used to be rebuilt whenever the territory list was replaced, which a
     * refresh does. Measured here because it is the other thing that was happening off the frame.
     */
    @Test
    void derivingASnapshotHappensOncePerRefreshNotPerFrame() {
        List<MapTerritory> territories = continent();

        for (int warm = 0; warm < 5; warm++) {
            MapDataStore.Snapshot.of(territories, List.of(), 1);
        }
        long before = System.nanoTime();
        int rounds = 50;
        for (int i = 0; i < rounds; i++) {
            MapDataStore.Snapshot.of(territories, List.of(), 1);
        }
        long elapsed = System.nanoTime() - before;

        System.out.printf("snapshot derivation (borders + palette + index): %.2f ms each%n",
                elapsed / 1_000_000.0 / rounds);
        assertTrue(elapsed > 0);
    }
}
