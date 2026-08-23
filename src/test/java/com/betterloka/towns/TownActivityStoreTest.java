package com.betterloka.towns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownActivityStoreTest {
    private static final long DAY = 24 * 60 * 60 * 1000L;

    private static TownInfoReading reading(String town, int active, long daysAgo) {
        return new TownInfoReading(town, 69, active, System.currentTimeMillis() - daysAgo * DAY);
    }

    @Test
    void countsTheRunOfZeroesFromTheOldestUnbrokenOne(@TempDir Path dir) {
        TownActivityStore store = new TownActivityStore(dir.resolve("activity.json"));
        store.record(reading("Duskfall", 4, 20));
        store.record(reading("Duskfall", 0, 12));
        store.record(reading("Duskfall", 0, 6));
        store.record(reading("Duskfall", 0, 0));

        assertEquals(12, store.daysAtZero("Duskfall"));
    }

    @Test
    void anyActiveMemberResetsTheClock(@TempDir Path dir) {
        TownActivityStore store = new TownActivityStore(dir.resolve("activity.json"));
        store.record(reading("Duskfall", 0, 20));
        store.record(reading("Duskfall", 1, 10));
        store.record(reading("Duskfall", 0, 3));

        // The town was alive ten days ago, so the fortnight before it does not count towards
        // Loka's timer — only the three days since.
        assertEquals(3, store.daysAtZero("Duskfall"));
    }

    @Test
    void aTownThatIsNotAtZeroHasNoDeletionDate(@TempDir Path dir) {
        TownActivityStore store = new TownActivityStore(dir.resolve("activity.json"));
        store.record(reading("Alive", 0, 9));
        store.record(reading("Alive", 2, 1));

        assertEquals(0L, store.zeroSince("Alive"));
        assertEquals(0L, store.deletionNoEarlierThan("Alive"));
        assertEquals(0L, store.daysAtZero("Alive"));
    }

    @Test
    void theDeletionDateIsThirtyDaysAfterTheFirstZeroSeen(@TempDir Path dir) {
        TownActivityStore store = new TownActivityStore(dir.resolve("activity.json"));
        store.record(reading("Duskfall", 0, 10));
        store.record(reading("Duskfall", 0, 0));

        long expected = store.zeroSince("Duskfall")
                + TownActivityStore.DAYS_AT_ZERO_BEFORE_DELETION * DAY;
        assertEquals(expected, store.deletionNoEarlierThan("Duskfall"));
        // Ten days in, twenty to go — floor division lands on 19 when the clock has moved on a
        // millisecond since the reading was stamped, which is every run of this.
        long remaining = (store.deletionNoEarlierThan("Duskfall") - System.currentTimeMillis()) / DAY;
        assertTrue(remaining == 19 || remaining == 20, "expected about twenty days left, got " + remaining);
    }

    @Test
    void thesameReadingTwiceInADayIsNotNews(@TempDir Path dir) {
        TownActivityStore store = new TownActivityStore(dir.resolve("activity.json"));
        assertTrue(store.record(reading("Duskfall", 0, 0)));
        assertFalse(store.record(reading("Duskfall", 0, 0)));
        assertEquals(1, store.history("Duskfall").size());

        // A changed count on the same day is news, because that is the thing being watched.
        assertTrue(store.record(reading("Duskfall", 1, 0)));
        assertEquals(2, store.history("Duskfall").size());
    }

    @Test
    void survivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("activity.json");
        TownActivityStore store = new TownActivityStore(file);
        store.record(reading("Duskfall", 0, 14));
        store.record(reading("Duskfall", 0, 0));

        // A run of zeroes is worth nothing if it starts again every time the game does: nothing can
        // re-fetch a reading nobody took.
        TownActivityStore reopened = new TownActivityStore(file);
        assertEquals(2, reopened.history("Duskfall").size());
        assertEquals(14, reopened.daysAtZero("Duskfall"));
        assertNotNull(reopened.latest("Duskfall"));
        assertEquals(0, reopened.latest("Duskfall").active());
    }

    @Test
    void townNamesAreMatchedWhateverTheirCase(@TempDir Path dir) {
        TownActivityStore store = new TownActivityStore(dir.resolve("activity.json"));
        store.record(reading("Duskfall", 0, 5));

        assertEquals(1, store.history("duskfall").size());
        assertEquals(5, store.daysAtZero("DUSKFALL"));
    }
}
