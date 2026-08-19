package com.betterloka.data;

import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleIndexStoreTest {
    private static final UUID ALICE = UUID.fromString("7de98534-bfde-442e-8d3f-8898c6d942a2");
    private static final UUID BOB = UUID.fromString("6f2f247d-9c3d-4cf1-a8ce-afd8b4b0d620");

    private static BattleZone battle(String id, long endedAt, BattleParticipant... participants) {
        int attackers = 0;
        int defenders = 0;
        for (BattleParticipant participant : participants) {
            if (participant.participated()) {
                if (participant.attacker()) {
                    attackers++;
                } else {
                    defenders++;
                }
            }
        }
        return new BattleZone(id, "south-14", endedAt - 1000, endedAt, false,
                "townA", "townB", null, null, List.of(participants), attackers, defenders);
    }

    @Test
    void indexesParticipantsByPlayerNewestFirst() {
        BattleIndex index = new BattleIndex();
        index.add(battle("a", 1000, new BattleParticipant(ALICE, 3, 1, true, true, "townA")));
        index.add(battle("b", 3000, new BattleParticipant(ALICE, 5, 0, false, true, "townB")));
        index.add(battle("c", 2000, new BattleParticipant(BOB, 1, 1, true, true, "townA")));

        List<BattleIndex.Entry> alice = index.entriesFor(ALICE);
        assertEquals(2, alice.size());
        assertEquals("b", alice.get(0).battle().id(), "newest battle should come first");
        assertEquals("a", alice.get(1).battle().id());
        assertEquals(1, index.entriesFor(BOB).size());
        assertEquals(3, index.size());
    }

    @Test
    void ignoresDuplicateBattles() {
        BattleIndex index = new BattleIndex();
        assertTrue(index.add(battle("a", 1000, new BattleParticipant(ALICE, 3, 1, true, true, "townA"))));
        assertFalse(index.add(battle("a", 1000, new BattleParticipant(ALICE, 3, 1, true, true, "townA"))));
        assertEquals(1, index.size());
        assertEquals(1, index.entriesFor(ALICE).size(), "a re-fetched page must not double-count kills");
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path directory) {
        BattleIndex original = new BattleIndex();
        original.add(battle("a", 1000,
                new BattleParticipant(ALICE, 3, 1, true, true, "townA"),
                new BattleParticipant(BOB, 0, 2, false, true, "townB")));
        original.add(battle("b", 2000, new BattleParticipant(ALICE, 7, 0, false, false, null)));
        original.setSyncedTotalElements(42);

        BattleIndexStore store = new BattleIndexStore(directory.resolve("battles.bin"));
        store.save(original);
        BattleIndex loaded = store.load();

        assertEquals(original.size(), loaded.size());
        assertEquals(42, loaded.syncedTotalElements());

        List<BattleIndex.Entry> alice = loaded.entriesFor(ALICE);
        assertEquals(2, alice.size());
        assertEquals("b", alice.get(0).battle().id());
        assertEquals(7, alice.get(0).participant().kills());
        assertFalse(alice.get(0).participant().participated(), "registered-only flag must survive the round trip");
        assertEquals("townA", alice.get(1).participant().townId());
        assertTrue(alice.get(1).participant().attacker());
        assertEquals("south-14", alice.get(1).battle().territory());
        assertEquals(1, alice.get(1).battle().attackerCount());
        assertEquals(1, alice.get(1).battle().defenderCount());
    }

    @Test
    void missingCacheYieldsEmptyIndex(@TempDir Path directory) {
        BattleIndex loaded = new BattleIndexStore(directory.resolve("nope.bin")).load();
        assertEquals(0, loaded.size());
        assertEquals(0, loaded.syncedTotalElements());
    }
}
