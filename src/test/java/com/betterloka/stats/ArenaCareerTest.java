package com.betterloka.stats;

import com.betterloka.api.ArenaApi.Ladder;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.ArenaRank;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A player's ranked record: best ever, last finished season, and the career added up. */
class ArenaCareerTest {
    private static final UUID PLAYER = UUID.fromString("c8474bd4-d8e4-4ec0-b691-f6bf2e3a1f01");
    private static final ArenaService.Seasons ONGOING = new ArenaService.Seasons(24, 23, true);
    private static final ArenaService.Seasons FINISHED = new ArenaService.Seasons(24, 23, false);

    private static ArenaEntry entry(String name, UUID uuid, String rank, int wins, int losses) {
        return new ArenaEntry(name, uuid, wins, losses, ArenaRank.parse(rank), 0, 0, 1);
    }

    /** Seasons 22 and 23 finished, 24 the latest published. */
    private static Map<String, ArenaService.Best> index() {
        Map<String, ArenaService.Best> index = new HashMap<>();
        ArenaService.record(index, 22, List.of(entry("OldName", PLAYER, "Gold I", 30, 20)), ArenaService.Kind.OLDER);
        ArenaService.record(index, 23, List.of(entry("OldName", PLAYER, "Bedrock", 100, 40)), ArenaService.Kind.PREVIOUS);
        ArenaService.record(index, 24, List.of(entry("NewName", PLAYER, "Netherite I", 70, 40),
                entry("Unranked", null, null, 1, 1)), ArenaService.Kind.LATEST);
        return index;
    }

    @Test
    void whileTheLatestSeasonIsPlayedTheLastSeasonIsTheOneBefore() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION,
                entry("NewName", PLAYER, "Diamond III", 78, 43), index(), "NewName", PLAYER, ONGOING);
        assertEquals(23, career.highest().season());
        assertEquals("Bedrock", career.highest().entry().rank().label());
        assertEquals(23, career.lastSeason().season(), "24 is still going on: its table is a snapshot");
        assertEquals(23, career.lastNumber());
        assertEquals(24, career.season());
        // 22 and 23 finished, plus this season as it stands now — not 24's snapshot as well.
        assertEquals(30 + 100 + 78, career.career().wins());
        assertEquals(20 + 40 + 43, career.career().losses());
        assertEquals(3, career.career().seasons());
    }

    @Test
    void onceANewSeasonHasStartedTheLatestIsFinished() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION,
                entry("NewName", PLAYER, "Iron I", 4, 6), index(), "NewName", PLAYER, FINISHED);
        assertEquals(24, career.lastSeason().season());
        assertEquals("Netherite I", career.lastSeason().entry().rank().label());
        assertEquals(25, career.season());
        assertEquals(30 + 100 + 70 + 4, career.career().wins());
        assertEquals(4, career.career().seasons());
    }

    @Test
    void theBestOfTheSeasonBeingPlayedIsThisSeason() {
        Map<String, ArenaService.Best> index = new HashMap<>();
        ArenaService.record(index, 23, List.of(entry("Someone", PLAYER, "Gold I", 10, 10)), ArenaService.Kind.PREVIOUS);
        ArenaService.record(index, 24, List.of(entry("Someone", PLAYER, "Diamond I", 40, 10)), ArenaService.Kind.LATEST);
        ArenaService.Career career = ArenaService.career(Ladder.BAREBONES,
                entry("Someone", PLAYER, "Emerald II", 45, 12), index, "Someone", PLAYER, ONGOING);
        assertEquals(ArenaService.THIS_SEASON, career.highest().season(), "Diamond I came this season");
        assertEquals("Diamond I", career.highest().entry().rank().label());
        assertEquals("Gold I", career.lastSeason().entry().rank().label());
    }

    @Test
    void aRankHigherNowThanEverBeforeIsTheHighest() {
        ArenaService.Best past = new ArenaService.Best(20, entry("A", PLAYER, "Diamond I", 1, 1));
        assertEquals(ArenaService.THIS_SEASON, ArenaService.highest(entry("A", PLAYER, "Netherite I", 1, 1), past).season());
        assertEquals(20, ArenaService.highest(entry("A", PLAYER, "Diamond I", 1, 1), past).season(), "a tie goes to the past");
    }

    @Test
    void notInTheLastSeasonIsNoLastSeason() {
        Map<String, ArenaService.Best> index = new HashMap<>();
        ArenaService.record(index, 21, List.of(entry("Vet", PLAYER, "Iron I", 12, 9)), ArenaService.Kind.OLDER);
        ArenaService.record(index, 23, List.of(entry("Other", UUID.randomUUID(), "Gold I", 9, 9)), ArenaService.Kind.PREVIOUS);
        ArenaService.Career career = ArenaService.career(Ladder.POTION, null, index, "Vet", PLAYER, ONGOING);
        assertEquals(21, career.highest().season());
        assertNull(career.lastSeason());
        assertEquals(21, career.career().games());
        assertEquals(1, career.career().seasons());
    }

    @Test
    void foundByNameWhenNoUuidIsKnown() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION, null, index(), "oldname", null, ONGOING);
        assertEquals(23, career.lastSeason().season(), "the name it had last season");
        assertEquals(130, career.career().wins(), "the seasons it played under that name");
    }

    @Test
    void somebodyNeverRankedHasAnEmptyCareer() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION, null, index(), "Stranger", UUID.randomUUID(), ONGOING);
        assertTrue(career.isEmpty());
    }

    @Test
    void theLatestSeasonIsOngoingWhenNobodyHasFewerGamesNow() {
        Map<Ladder, Map<String, ArenaService.Best>> index = new EnumMap<>(Ladder.class);
        Map<String, ArenaService.Best> potion = new HashMap<>();
        List<ArenaEntry> snapshot = new java.util.ArrayList<>();
        List<ArenaEntry> grown = new java.util.ArrayList<>();
        List<ArenaEntry> reset = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID id = new UUID(7, i);
            snapshot.add(entry("p" + i, id, "Gold I", 50 + i, 30));
            grown.add(entry("p" + i, id, "Gold I", 52 + i, 31));
            reset.add(entry("p" + i, id, "Iron I", 3, 7));
        }
        ArenaService.record(potion, 24, snapshot, ArenaService.Kind.LATEST);
        index.put(Ladder.POTION, potion);
        assertTrue(ArenaService.latestOngoing(index, Map.of(Ladder.POTION, grown)));
        assertFalse(ArenaService.latestOngoing(index, Map.of(Ladder.POTION, reset)), "everybody back to a handful of games");
        assertTrue(ArenaService.latestOngoing(index, Map.of(Ladder.POTION, grown.subList(0, 5))), "too few to tell: assume so");
    }
}
