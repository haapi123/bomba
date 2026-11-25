package com.betterloka.grind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShrinesTest {
    private static Shrine shrine(String id) {
        return Shrines.byId(id);
    }

    @Test
    void everyShrineTheUserGaveIsThereWithItsCoordinates() {
        assertEquals(11, Shrines.ALL.size());
        assertEquals(4, Shrines.ALL.stream().filter(s -> s.kind() == ShrineKind.GHAST).count());
        assertEquals(4, Shrines.ALL.stream().filter(s -> s.kind() == ShrineKind.BLAZE).count());
        assertEquals(3, Shrines.ALL.stream().filter(s -> s.kind() == ShrineKind.MAGMA_CUBE).count());
        Shrine first = shrine("ghast-1");
        assertEquals(1037, first.x());
        assertEquals(177, first.y());
        assertEquals(1190, first.z());
        assertEquals("Ghast Shrine 1", first.label());
        assertEquals("1347 178 1346", shrine("magma_cube-2").coordinates());
    }

    @Test
    void theShrineGivenWithoutAHeightKeepsItUnknown() {
        Shrine blaze2 = shrine("blaze-2");
        assertFalse(blaze2.hasY());
        assertEquals(864, blaze2.x());
        assertEquals(908, blaze2.z());
        assertEquals("864 ~ 908", blaze2.coordinates());
    }

    @Test
    void theAreaIsThirtyBlocksSquareAroundTheCoordinates() {
        Shrine ghast2 = shrine("ghast-2");
        assertTrue(ghast2.contains(527.5, 963.5));
        assertTrue(ghast2.contains(542.4, 978.4), "fifteen blocks out on both axes is still inside");
        assertFalse(ghast2.contains(544, 963.5), "seventeen blocks out is not");
        assertFalse(ghast2.contains(527.5, 946), "nor on the other axis");
        assertEquals(ghast2, Shrines.at(540, 950));
        assertNull(Shrines.at(0, 0));
    }

    @Test
    void whereTwoAreasOverlapTheNearerShrineHasTheGround() {
        // Ghast Shrine 1 and Magma Cube Shrine 1 are twenty-one blocks apart, so at thirty blocks
        // square their areas share a strip. It is the only pair that does.
        assertEquals(shrine("ghast-1"), Shrines.at(1030, 1195));
        assertEquals(shrine("magma_cube-1"), Shrines.at(1025, 1199));
        assertEquals(shrine("magma_cube-1"), Shrines.at(1022, 1200));
        int pairs = 0;
        for (Shrine a : Shrines.ALL) {
            for (Shrine b : Shrines.ALL) {
                if (a != b && Math.abs(a.x() - b.x()) <= Shrine.AREA_HALF * 2
                        && Math.abs(a.z() - b.z()) <= Shrine.AREA_HALF * 2) {
                    pairs++;
                }
            }
        }
        assertEquals(2, pairs, "one overlapping pair, counted from each side");
    }

    // --- the timers ---

    @Test
    void startingATimerCountsTwentyMinutesThatSurviveARestart(@TempDir Path dir) {
        Shrine ghast1 = shrine("ghast-1");
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(ShrineTimers.State.UNKNOWN, timers.state(ghast1, 0));

        timers.start(ghast1, 1_000_000);
        assertEquals(ShrineTimers.State.COOLDOWN, timers.state(ghast1, 1_000_000));
        assertEquals(20 * 60 * 1000L, timers.remaining(ghast1, 1_000_000));

        ShrineTimers reloaded = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(60_000L, reloaded.remaining(ghast1, 1_000_000 + 19 * 60 * 1000L));
        assertEquals(ShrineTimers.State.COOLDOWN, reloaded.state(ghast1, 1_000_000 + 15 * 60 * 1000L),
                "fifteen minutes is no longer enough");
        assertEquals(ShrineTimers.State.READY, reloaded.state(ghast1, 1_000_000 + 20 * 60 * 1000L));
        assertEquals(ShrineTimers.State.UNKNOWN, reloaded.state(shrine("ghast-2"), 1_000_000));
    }

    @Test
    void clearingIsKept(@TempDir Path dir) {
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        timers.start(shrine("blaze-3"), 0);
        timers.clear(shrine("blaze-3"));
        ShrineTimers reloaded = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(ShrineTimers.State.UNKNOWN, reloaded.state(shrine("blaze-3"), 0));
    }

    /** A file written before 0.65, with the names the purge line used to teach, still loads. */
    @Test
    void anOlderFileWithLearnedNamesStillLoads(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("shrines.json"),
                "{\"fetchedAt\":1000,\"value\":{\"purgedAt\":{\"ghast-1\":1000},"
                        + "\"learned\":{\"infernal altar\":\"magma_cube-1\"},\"stats\":{}}}");
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(1000 + Shrines.COOLDOWN_MILLIS, timers.endsAt(shrine("ghast-1")));
    }

    @Test
    void theCooldownIsTheSettingAndMovesTimersAlreadyRunning(@TempDir Path dir) {
        long[] minutes = {15};
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"), () -> minutes[0] * 60_000L);
        Shrine blaze1 = shrine("blaze-1");
        timers.start(blaze1, 0);
        assertEquals(15 * 60_000L, timers.remaining(blaze1, 0));
        minutes[0] = 20;
        assertEquals(20 * 60_000L, timers.remaining(blaze1, 0), "set to twenty, the running timer follows");
        assertEquals(ShrineTimers.State.COOLDOWN, timers.state(blaze1, 16 * 60_000L));
        minutes[0] = 5;
        assertEquals(ShrineTimers.State.READY, timers.state(blaze1, 6 * 60_000L));
    }

    @Test
    void runsAddUpPerShrineAndSurviveARestart(@TempDir Path dir) {
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        Shrine ghast1 = shrine("ghast-1");
        timers.recordRun(ghast1, Map.of("minecraft:ghast_tear", 3, "minecraft:gunpowder", 5), 1000);
        timers.recordRun(ghast1, Map.of("minecraft:ghast_tear", 2), 2000);
        ShrineLoot.Stats stats = new ShrineTimers(dir.resolve("shrines.json")).stats(ghast1);
        assertEquals(2, stats.runs());
        assertEquals(2000, stats.lastRunAt());
        assertEquals(5L, stats.totals().get("minecraft:ghast_tear"));
        assertEquals(5L, stats.totals().get("minecraft:gunpowder"));
        assertEquals(Map.of("minecraft:ghast_tear", 2), stats.lastRun());
        assertEquals(0, timers.stats(shrine("ghast-2")).runs(), "each shrine keeps its own");
        timers.clearStats(ghast1);
        assertEquals(0, timers.stats(ghast1).runs());
    }

    @Test
    void theClockReadsMinutesAndSeconds() {
        assertEquals("15:00", ShrineTimers.clock(15 * 60 * 1000L));
        assertEquals("12:31", ShrineTimers.clock(12 * 60 * 1000L + 31_000));
        assertEquals("0:01", ShrineTimers.clock(400), "a part second rounds up, so 0:00 means done");
        assertEquals("0:00", ShrineTimers.clock(0));
    }
}
