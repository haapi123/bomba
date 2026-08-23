package com.betterloka.bot;

import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ObjectIds;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

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

    /** A town whose roster is {@code member0..memberN}, with the given owner and sub-owners. */
    private static LokaTown town(int members, int subOwners) {
        JsonObject json = new JsonObject();
        json.addProperty("id", "68a6c30a0000000000000000");
        json.addProperty("name", "Test");
        json.addProperty("world", "north");
        json.addProperty("owner", "member0");

        JsonObject roster = new JsonObject();
        for (int i = 0; i < members; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("subowner", i > 0 && i <= subOwners);
            roster.add("member" + i, entry);
        }
        json.add("members", roster);
        return LokaTown.fromJson(json);
    }

    @Test
    void theOwnerAndSubOwnersSurviveAnyCap() {
        LokaTown big = town(1000, 20);
        List<String> selected = TownReport.select(big, 25);

        assertEquals(25, selected.size());
        assertTrue(selected.contains("member0"), "the owner must never be cut");
        for (int i = 1; i <= 20; i++) {
            assertTrue(selected.contains("member" + i), "sub-owner member" + i + " must never be cut");
        }
    }

    @Test
    void aCapSmallerThanTheOfficersStillKeepsThemAll() {
        // Concord has one owner and twenty-three sub-owners; a cap of five must not lose any.
        List<String> selected = TownReport.select(town(1000, 23), 5);
        assertEquals(24, selected.size());
    }

    @Test
    void theOrdinaryMembersAreSpreadRatherThanTakenFromTheFront() {
        // Loka stores members in the order they joined, so the first N are the oldest accounts —
        // the group most likely to be inactive, which made the quiet count read far too badly.
        List<String> selected = TownReport.select(town(100, 0), 11);
        List<String> ordinary = selected.subList(1, selected.size());

        assertEquals(10, ordinary.size());
        assertTrue(ordinary.contains("member90") || ordinary.contains("member91")
                        || ordinary.contains("member99"),
                "the sample must reach the far end of the roster, got " + ordinary);
    }

    @Test
    void aCapOfZeroChecksEverybody() {
        assertEquals(300, TownReport.select(town(300, 4), 0).size());
    }

    @Test
    void aRosterSmallerThanTheCapIsNotSampled() {
        assertEquals(12, TownReport.select(town(12, 2), 100).size());
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
                3);

        assertEquals(newer, report.lastActive());
        // "a" and "c" have not been seen this month; "b" was seen within the window in these terms
        // only if the test runs close enough to it, so the assertion is on the pair that cannot move.
        assertTrue(report.quietFor(30) >= 2);
    }
}
