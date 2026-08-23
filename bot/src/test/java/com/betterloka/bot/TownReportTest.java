package com.betterloka.bot;

import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ObjectIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownReportTest {
    @Test
    void readsEldritchBotsDateFormat() {
        Instant parsed = TownReport.parseFightDate("Wed Aug 12 2026");
        assertNotNull(parsed);
        ZonedDateTime utc = parsed.atZone(ZoneOffset.UTC);
        assertEquals(2026, utc.getYear());
        assertEquals(8, utc.getMonthValue());
        assertEquals(12, utc.getDayOfMonth());
    }

    @Test
    void anythingThatIsNotADateIsNoDate() {
        assertNull(TownReport.parseFightDate(null));
        assertNull(TownReport.parseFightDate(""));
        assertNull(TownReport.parseFightDate("never"));
        assertNull(TownReport.parseFightDate("12/08/2026"));
    }

    @Test
    void aTownsFoundingComesOutOfItsObjectId() {
        // An ObjectID stamped 21 August 2025 at 06:56:10 UTC.
        LokaTown town = LokaTown.deleted("68a6c30a0000000000000000", "Test", "north");
        assertNotNull(town.founded());
        assertEquals(ObjectIds.timestamp("68a6c30a0000000000000000"), town.founded());
    }

    @Test
    void theMayTwentyTwentyOneClusterIsFlaggedAsAnImportRatherThanAFounding() {
        // Sixteen live towns carry IDs stamped inside these five seconds; none was founded then.
        LokaTown imported = LokaTown.deleted("6090042a0000000000000000", "Hilo", "north");
        assertTrue(imported.foundedIsImport(),
                "a town stamped 2021-05-03 14:09:46 should read as an import");

        LokaTown founded = LokaTown.deleted("68a6c30a0000000000000000", "Somewhere", "north");
        assertFalse(founded.foundedIsImport());
    }

    @Test
    void lastSeenTakesWhicheverSignalIsNewer() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-08-01T00:00:00Z");

        TownReport.Member fightNewer =
                new TownReport.Member("a", null, false, false, null, newer, older);
        assertEquals(newer, fightNewer.lastSeen());
        assertEquals("fight", fightNewer.lastSeenSource());

        TownReport.Member marketNewer =
                new TownReport.Member("b", null, false, false, null, older, newer);
        assertEquals(newer, marketNewer.lastSeen());
        assertEquals("market", marketNewer.lastSeenSource());

        TownReport.Member neither =
                new TownReport.Member("c", null, false, false, null, null, null);
        assertNull(neither.lastSeen());
        assertNull(neither.lastSeenSource());
    }

    @Test
    void aTownsClockIsItsMostRecentlyActiveMember() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-08-01T00:00:00Z");
        TownReport.Report report = new TownReport.Report(
                LokaTown.deleted("68a6c30a0000000000000000", "Test", "north"),
                java.util.List.of(
                        new TownReport.Member("a", null, true, false, null, older, null),
                        new TownReport.Member("b", null, false, false, null, newer, null),
                        new TownReport.Member("c", null, false, false, null, null, null)),
                0);

        assertEquals(newer, report.lastActive());
        // "a" and "c" have not been seen this month; "b" was seen within the window in these terms
        // only if the test runs close enough to it, so the assertion is on the pair that cannot move.
        assertTrue(report.quietFor(30) >= 2);
    }
}
