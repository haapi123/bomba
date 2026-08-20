package com.betterloka.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranked ladders are the one place Loka publishes 1v1 records, and they key rows by a packed
 * UUID rather than a name — names change between seasons, so decoding it wrong means following the
 * wrong player across a career.
 */
class ArenaParsingTest {

    @Test
    void readsALadderRow() {
        JsonObject row = JsonParser.parseString("""
                {"name":"Deivi_17","uuid":"w0gp61W9IbtLcUlXW4a8hw==",
                 "arenaStats":{"potionranked":{"losses":134,"rank":"Netherite III","streak":3,"wins":236}},
                 "surplus":0}
                """).getAsJsonObject();

        ArenaEntry entry = ArenaEntry.fromJson(row, "potionranked", 2);
        assertNotNull(entry);
        assertEquals("Deivi_17", entry.name());
        assertEquals(236, entry.wins());
        assertEquals(134, entry.losses());
        assertEquals(370, entry.duels(), "duels are wins plus losses; 1v1 has no draws");
        assertEquals("63.8%", entry.winRatioText());
        assertEquals(2, entry.position());
        assertEquals(ArenaRank.Tier.NETHERITE, entry.rank().tier());
        assertEquals(3, entry.rank().level());
    }

    @Test
    void ignoresARowFromTheOtherLadder() {
        JsonObject row = JsonParser.parseString("""
                {"name":"Zqion320","uuid":"P0eVzrRRoqJ253rlAsNMgA==",
                 "arenaStats":{"barebonesranked":{"losses":5,"rank":"Gold III","streak":11,"wins":28}}}
                """).getAsJsonObject();

        assertNull(ArenaEntry.fromJson(row, "potionranked", 1),
                "a barebones-only player has no potion standing");
        assertNotNull(ArenaEntry.fromJson(row, "barebonesranked", 1));
    }

    /** The site packs the UUID as two little-endian longs, base64'd. */
    @Test
    void decodesThePackedUuid() {
        assertEquals(UUID.fromString("3979d47b-602b-4c75-9af1-a11d06a2056e"),
                ArenaEntry.decodeUuid("dUwrYHvUeTluBaIGHaHxmg=="));
        assertNull(ArenaEntry.decodeUuid(null));
        assertNull(ArenaEntry.decodeUuid("not base64 at all !!"));
        assertNull(ArenaEntry.decodeUuid("YWJj"), "three bytes is not a UUID");
    }

    @Test
    void ordersRanksTheWayTheLeaderboardDoes() {
        String[] descending = {
                "Bedrock", "Netherite III", "Netherite II", "Netherite I",
                "Diamond III", "Diamond I", "Emerald II", "Gold III", "Iron II", "Copper", "Wood"
        };
        for (int i = 1; i < descending.length; i++) {
            ArenaRank higher = ArenaRank.parse(descending[i - 1]);
            ArenaRank lower = ArenaRank.parse(descending[i]);
            assertTrue(higher.compareTo(lower) > 0,
                    descending[i - 1] + " should outrank " + descending[i]);
        }
    }

    @Test
    void readsATierWithNoLevel() {
        ArenaRank bedrock = ArenaRank.parse("Bedrock");
        assertEquals(ArenaRank.Tier.BEDROCK, bedrock.tier());
        assertEquals(0, bedrock.level());
        assertEquals("Bedrock", bedrock.label());
    }

    @Test
    void returnsNullForRanksItDoesNotKnow() {
        assertNull(ArenaRank.parse(null));
        assertNull(ArenaRank.parse(""));
        assertNull(ArenaRank.parse("Unranked"));
    }
}
