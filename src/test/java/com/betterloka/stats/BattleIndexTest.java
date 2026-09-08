package com.betterloka.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the battle archive is folded by.
 *
 * <p>The sweep itself needs the network and is exercised in the client; what is decided here is the
 * arithmetic and the Rivi/Conquest divide, which is what the two totals on the profile rest on.
 */
class BattleIndexTest {
    /**
     * Loka names a battle for the hex it was fought over, and the prefix is the world. Rivina is
     * {@code lilboi}; Balak, whose battles look similar and are not Rivi, is {@code bigboi}.
     */
    @Test
    void rivinaBattlesAreTheRiviOnes() {
        assertTrue(BattleIndex.isRivi("lilboi-9"));
        assertTrue(BattleIndex.isRivi("lilboi-15"));

        assertFalse(BattleIndex.isRivi("bigboi-14"), "Balak is a Conquest continent, not Rivi");
        assertFalse(BattleIndex.isRivi("west-125"));
        assertFalse(BattleIndex.isRivi("north-3"));
        assertFalse(BattleIndex.isRivi("south-88"));
    }

    @Test
    void aBattleWithNoNameIsNotRivi() {
        assertFalse(BattleIndex.isRivi(null));
        assertFalse(BattleIndex.isRivi(""));
    }

    @Test
    void monthsAreKeyedSortablyAndZeroPadded() {
        assertEquals("2026-09", BattleIndex.monthKey(LocalDate.of(2026, 9, 8)));
        assertEquals("2026-01", BattleIndex.monthKey(LocalDate.of(2026, 1, 31)));
        assertTrue("2026-09".compareTo("2026-10") < 0, "keys have to sort as dates do");
    }

    @Test
    void bucketsAddUp() {
        BattleIndex.Bucket first = new BattleIndex.Bucket(3, 12, 4);
        BattleIndex.Bucket second = new BattleIndex.Bucket(2, 5, 6);

        BattleIndex.Bucket sum = first.plus(second);

        assertEquals(5, sum.fights());
        assertEquals(17, sum.kills());
        assertEquals(10, sum.deaths());
    }

    /** A player who never died has a ratio, not a division by zero. */
    @Test
    void aFlawlessRecordCountsItsKills() {
        assertEquals(7.0, new BattleIndex.Bucket(2, 7, 0).killDeathRatio());
        assertEquals("7.00", new BattleIndex.Bucket(2, 7, 0).killDeathText());
    }

    @Test
    void anEmptyBucketSaysSo() {
        assertTrue(BattleIndex.Bucket.EMPTY.isEmpty());
        assertFalse(new BattleIndex.Bucket(1, 0, 0).isEmpty());
    }

    @Test
    void aPlayerWithNoRecordIsEmptyRatherThanNull() {
        assertTrue(BattleIndex.PlayerRecord.EMPTY.isEmpty());
        assertTrue(BattleIndex.PlayerRecord.EMPTY.rivi("2026-09").isEmpty());
        assertTrue(BattleIndex.PlayerRecord.EMPTY.conquest("2026-09").isEmpty());
    }

    @Test
    void progressReportsHowFarTheSweepHasGot() {
        BattleIndex.Progress half = new BattleIndex.Progress(true, 4000, 8000, 211, 423);

        assertEquals(50, half.percent());
        assertFalse(half.complete());
        assertTrue(new BattleIndex.Progress(false, 8000, 8445, 423, 423).complete());
    }

    /** Before the first page lands there is no total, and a percentage of nothing is not 100. */
    @Test
    void progressBeforeAnythingIsKnownIsZero() {
        BattleIndex.Progress nothing = new BattleIndex.Progress(false, 0, 0, 0, 0);

        assertEquals(0, nothing.percent());
        assertFalse(nothing.complete());
    }

    /**
     * Progress is counted in pages, not in battles folded.
     *
     * <p>Battles nobody turned up to contribute nothing to anybody's totals, so a finished build
     * has read every page while having folded fewer battles than the archive holds. Counting the
     * folded ones left it looking unfinished for ever, and stalled the build at a quarter of the
     * archive while it re-read pages it already had.
     */
    @Test
    void aFinishedBuildIsCompleteEvenWithBattlesThatFoldedNothing() {
        BattleIndex.Progress done = new BattleIndex.Progress(false, 7100, 8445, 423, 423);

        assertTrue(done.complete(), "every page read is a finished build");
        assertEquals(100, done.percent());
    }
}
