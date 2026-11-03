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
    void theAreaIsTwentyBlocksSquareAroundTheCoordinates() {
        Shrine ghast1 = shrine("ghast-1");
        assertTrue(ghast1.contains(1037.5, 1190.5));
        assertTrue(ghast1.contains(1047.4, 1200.4), "ten blocks out on both axes is still inside");
        assertFalse(ghast1.contains(1049, 1190.5), "twelve blocks out is not");
        assertFalse(ghast1.contains(1037.5, 1179), "nor on the other axis");
        assertEquals(ghast1, Shrines.at(1040, 1185));
        assertNull(Shrines.at(0, 0));
    }

    @Test
    void noTwoShrineAreasOverlap() {
        for (Shrine a : Shrines.ALL) {
            for (Shrine b : Shrines.ALL) {
                if (a != b) {
                    boolean overlap = Math.abs(a.x() - b.x()) <= Shrine.AREA_HALF * 2
                            && Math.abs(a.z() - b.z()) <= Shrine.AREA_HALF * 2;
                    assertFalse(overlap, a.label() + " overlaps " + b.label());
                }
            }
        }
    }

    // --- reading the purge message ---

    @Test
    void theNameComesOutOfThePurgeLine() {
        assertEquals("Ghast Shrine 1", Shrines.purgedName("Ghast Shrine 1 was purged!"));
        assertEquals("Infernal Altar", Shrines.purgedName("§6Infernal Altar §ewas purged!"));
        assertEquals("Blaze Shrine", Shrines.purgedName("[Loka] Blaze Shrine was purged!"));
        assertEquals("Magma Shrine 3", Shrines.purgedName("Shrines » Magma Shrine 3 was purged! Back in 15m"));
        assertEquals("The Ghast Spire", Shrines.purgedName("  The Ghast Spire has been purged  "));
    }

    @Test
    void otherLinesAreNotPurges() {
        assertNull(Shrines.purgedName("haapi: who purged the ghast shrine?"));
        assertNull(Shrines.purgedName("was purged!"));
        assertNull(Shrines.purgedName("Welcome to Loka"));
        assertNull(Shrines.purgedName(null));
    }

    @Test
    void aNameThatSaysTheMobAndNumberIsThatShrineWhereverThePlayerIs() {
        assertEquals(shrine("ghast-2"), Shrines.resolve("Ghast Shrine 2", null, Map.of()));
        assertEquals(shrine("magma_cube-3"), Shrines.resolve("Magma Cube Shrine #3", shrine("ghast-1"), Map.of()),
                "what the line says beats where the player stands");
        assertEquals(shrine("blaze-4"), Shrines.resolve("Blaze Shrine IV", null, Map.of()));
        assertTrue(Shrines.explicit("Ghast Shrine 2"));
        assertFalse(Shrines.explicit("Infernal Altar"));
    }

    @Test
    void aNameThatDoesNotSayIsTheShrineThePlayerIsAt() {
        assertEquals(shrine("blaze-1"), Shrines.resolve("Infernal Altar", shrine("blaze-1"), Map.of()));
        assertEquals(shrine("blaze-1"), Shrines.resolve("Blaze Shrine", shrine("blaze-1"), Map.of()));
        assertNull(Shrines.resolve("Ghast Shrine", shrine("blaze-1"), Map.of()),
                "a Ghast shrine purged elsewhere is not the Blaze shrine the player stands at");
        assertNull(Shrines.resolve("Infernal Altar", null, Map.of()), "with no clue, nothing is guessed");
    }

    @Test
    void aNameLearnedAtAShrineIsKnownFromAnywhereAfterwards() {
        Map<String, String> learned = Map.of(Shrines.key("Infernal Altar"), "magma_cube-2");
        assertEquals(shrine("magma_cube-2"), Shrines.resolve("§cInfernal  altar", null, learned));
    }

    @Test
    void standingAtAShrineBeatsALearnedName() {
        // So a name the server uses for more than one shrine gets the chance to show it.
        Map<String, String> learned = Map.of(Shrines.key("Infernal Altar"), "magma_cube-2");
        assertEquals(shrine("ghast-1"), Shrines.resolve("Infernal Altar", shrine("ghast-1"), learned));
    }

    @Test
    void onlyANameOfItsOwnIsLearned() {
        assertTrue(Shrines.learnable("Infernal Altar"));
        assertFalse(Shrines.learnable("Ghast Shrine"), "every Ghast shrine is probably called that");
        assertFalse(Shrines.learnable("Ghast Shrine 2"), "says which it is already");
        assertFalse(Shrines.learnable("Shrine 2"));
        assertFalse(Shrines.learnable("§c"));
    }

    @Test
    void aNameHeardAtTwoShrinesStopsBeingTrusted(@TempDir Path dir) {
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        timers.learn("Shrine", shrine("blaze-1"));
        assertEquals(shrine("blaze-1"), Shrines.resolve("Shrine", null, timers.learned()));
        timers.learn("Shrine", shrine("blaze-1"));
        assertEquals(shrine("blaze-1"), Shrines.resolve("Shrine", null, timers.learned()), "the same shrine again is fine");
        timers.learn("Shrine", shrine("ghast-3"));
        assertNull(Shrines.resolve("Shrine", null, timers.learned()), "a name for several shrines points at none");
        timers.learn("Shrine", shrine("blaze-1"));
        assertNull(Shrines.resolve("Shrine", null, timers.learned()), "and stays that way");
        assertEquals(shrine("ghast-3"), Shrines.resolve("Shrine", shrine("ghast-3"), timers.learned()),
                "while standing at a shrine still works");
    }

    // --- the timers ---

    @Test
    void aPurgeStartsFifteenMinutesThatSurviveARestart(@TempDir Path dir) {
        Shrine ghast1 = shrine("ghast-1");
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(ShrineTimers.State.UNKNOWN, timers.state(ghast1, 0));

        timers.purged(ghast1, 1_000_000);
        assertEquals(ShrineTimers.State.COOLDOWN, timers.state(ghast1, 1_000_000));
        assertEquals(15 * 60 * 1000L, timers.remaining(ghast1, 1_000_000));

        ShrineTimers reloaded = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(60_000L, reloaded.remaining(ghast1, 1_000_000 + 14 * 60 * 1000L));
        assertEquals(ShrineTimers.State.READY, reloaded.state(ghast1, 1_000_000 + 15 * 60 * 1000L));
        assertEquals(ShrineTimers.State.UNKNOWN, reloaded.state(shrine("ghast-2"), 1_000_000));
    }

    @Test
    void clearingAndLearningAreKept(@TempDir Path dir) {
        ShrineTimers timers = new ShrineTimers(dir.resolve("shrines.json"));
        timers.purged(shrine("blaze-3"), 0);
        timers.clear(shrine("blaze-3"));
        timers.learn("Infernal Altar", shrine("magma_cube-1"));
        ShrineTimers reloaded = new ShrineTimers(dir.resolve("shrines.json"));
        assertEquals(ShrineTimers.State.UNKNOWN, reloaded.state(shrine("blaze-3"), 0));
        assertEquals("magma_cube-1", reloaded.learned().get(Shrines.key("Infernal Altar")));
    }

    @Test
    void theClockReadsMinutesAndSeconds() {
        assertEquals("15:00", ShrineTimers.clock(15 * 60 * 1000L));
        assertEquals("12:31", ShrineTimers.clock(12 * 60 * 1000L + 31_000));
        assertEquals("0:01", ShrineTimers.clock(400), "a part second rounds up, so 0:00 means done");
        assertEquals("0:00", ShrineTimers.clock(0));
    }
}
