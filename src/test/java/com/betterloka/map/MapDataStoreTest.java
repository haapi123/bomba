package com.betterloka.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the store notices between two fetches, and what it leaves alone. */
class MapDataStoreTest {
    private static MapTerritory hex(String number, String owner, double x) {
        return new MapTerritory(number, "Test", owner, owner == null ? null : owner + " Alliance",
                null,
                new double[] {x, x + 100, x + 100, x},
                new double[] {0, 0, 100, 100},
                x + 50, 50, 0x3AB3DA, 0x09090B,
                owner == null ? "territory_neutral" : "territory_owned", -1, false, null);
    }

    private static MapDataStore.Snapshot snapshot(List<MapTerritory> territories) {
        return MapDataStore.Snapshot.of(territories, List.of(), 1000);
    }

    @Test
    void aCaptureIsReportedAsOneChange() {
        MapDataStore.Snapshot before = snapshot(List.of(
                hex("1", null, 0), hex("2", "Corvus", 100), hex("3", "Invicta", 200)));
        MapDataStore.Snapshot after = snapshot(List.of(
                hex("1", null, 0), hex("2", "Targon", 100), hex("3", "Invicta", 200)));

        List<MapDataStore.Change> changes =
                MapDataStore.diff(Continent.KALROS, before, after);

        assertEquals(1, changes.size());
        assertEquals("2", changes.get(0).after().number());
        assertEquals("Corvus", changes.get(0).before().owner());
        assertEquals("Targon", changes.get(0).after().owner());
    }

    /** A refresh that changed nothing must be silent, or every poll would look like a capture. */
    @Test
    void anUnchangedRefreshReportsNothing() {
        List<MapTerritory> same = List.of(hex("1", null, 0), hex("2", "Corvus", 100));

        assertTrue(MapDataStore.diff(Continent.KALROS, snapshot(same), snapshot(same)).isEmpty());
    }

    @Test
    void groundBeingTakenAndGivenUpBothCount() {
        MapDataStore.Snapshot before = snapshot(List.of(hex("1", null, 0), hex("2", "Corvus", 100)));
        MapDataStore.Snapshot after = snapshot(List.of(hex("1", "Targon", 0), hex("2", null, 100)));

        List<MapDataStore.Change> changes = MapDataStore.diff(Continent.KALROS, before, after);

        assertEquals(2, changes.size());
    }

    /** The first fetch of a session is not a hundred and forty captures. */
    @Test
    void theFirstFetchIsNotAFloodOfChanges() {
        MapDataStore.Snapshot after = snapshot(List.of(hex("1", "Corvus", 0)));

        assertTrue(MapDataStore.diff(Continent.KALROS,
                MapDataStore.Snapshot.empty(), after).isEmpty());
    }

    @Test
    void aSnapshotCarriesItsBordersPaletteAndIndexAlready() {
        MapDataStore.Snapshot snapshot = snapshot(List.of(
                hex("1", "Corvus", 0), hex("2", "Corvus", 100), hex("3", null, 200)));

        // Worked out once here rather than per frame; the screen reads them as they are.
        assertTrue(snapshot.borders().isInternal(100, 0, 100, 100), "the seam between them");
        assertEquals(snapshot.palette().colorOf("Corvus"), snapshot.palette().colorOf("corvus"));
        assertSame(snapshot.territories().get(0), snapshot.index().at(50, 50));
        assertNull(snapshot.index().at(-500, -500), "open sea");
    }

    @Test
    void freshDataIsNotReportedAsStale() {
        long now = System.currentTimeMillis();
        assertTrue(MapDataStore.Snapshot.of(List.of(hex("1", null, 0)), List.of(),
                now - MapDataStore.STALE_AFTER_MILLIS - 1000).stale(now));
        assertTrue(!MapDataStore.Snapshot.of(List.of(hex("1", null, 0)), List.of(), now).stale(now));
    }
}
