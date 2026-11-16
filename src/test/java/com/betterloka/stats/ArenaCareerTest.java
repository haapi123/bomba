package com.betterloka.stats;

import com.betterloka.api.ArenaApi.Ladder;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.ArenaRank;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a duel's summary says of the opponent's ranked record: best ever, and last season. */
class ArenaCareerTest {
    private static final UUID PLAYER = UUID.fromString("c8474bd4-d8e4-4ec0-b691-f6bf2e3a1f01");

    private static ArenaEntry entry(String name, UUID uuid, String rank) {
        return new ArenaEntry(name, uuid, 10, 5, ArenaRank.parse(rank), 0, 0, 1);
    }

    /** Three seasons, the last one published being 24. */
    private static Map<String, ArenaService.Best> index() {
        Map<String, ArenaService.Best> index = new HashMap<>();
        ArenaService.record(index, 22, List.of(entry("OldName", PLAYER, "Gold I")), false);
        ArenaService.record(index, 23, List.of(entry("OldName", PLAYER, "Bedrock")), false);
        ArenaService.record(index, 24, List.of(entry("NewName", PLAYER, "Netherite I"),
                entry("Unranked", null, null)), true);
        return index;
    }

    @Test
    void bestEverAndLastSeasonAreDifferentSeasons() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION, entry("NewName", PLAYER, "Diamond III"),
                index(), "NewName", PLAYER);
        assertEquals(23, career.highest().season());
        assertEquals("Bedrock", career.highest().entry().rank().label());
        assertEquals(24, career.lastSeason().season());
        assertEquals("Netherite I", career.lastSeason().entry().rank().label());
    }

    @Test
    void aRankHigherThisSeasonIsTheHighestEver() {
        Map<String, ArenaService.Best> index = new HashMap<>();
        ArenaService.record(index, 24, List.of(entry("Someone", PLAYER, "Gold I")), true);
        ArenaService.Career career = ArenaService.career(Ladder.BAREBONES, entry("Someone", PLAYER, "Emerald II"),
                index, "Someone", PLAYER);
        assertEquals(ArenaService.THIS_SEASON, career.highest().season());
        assertEquals("Emerald II", career.highest().entry().rank().label());
        assertEquals(24, career.lastSeason().season());
    }

    @Test
    void aTieGoesToThePastSeason() {
        ArenaService.Best past = new ArenaService.Best(20, entry("A", PLAYER, "Diamond I"));
        assertEquals(20, ArenaService.highest(entry("A", PLAYER, "Diamond I"), past).season());
    }

    @Test
    void notInTheLastSeasonIsNoLastSeason() {
        Map<String, ArenaService.Best> index = new HashMap<>();
        ArenaService.record(index, 21, List.of(entry("Vet", PLAYER, "Iron I")), false);
        ArenaService.record(index, 24, List.of(entry("Other", UUID.randomUUID(), "Gold I")), true);
        ArenaService.Career career = ArenaService.career(Ladder.POTION, null, index, "Vet", PLAYER);
        assertEquals(21, career.highest().season());
        assertNull(career.lastSeason());
    }

    @Test
    void foundByNameWhenNoUuidIsKnown() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION, null, index(), "newname", null);
        assertEquals(24, career.lastSeason().season(), "the name it had last season");
        assertEquals(24, career.highest().season(), "and only what that name reached");
    }

    @Test
    void somebodyNeverRankedHasAnEmptyCareer() {
        ArenaService.Career career = ArenaService.career(Ladder.POTION, null, index(), "Stranger", UUID.randomUUID());
        assertTrue(career.isEmpty());
    }
}
