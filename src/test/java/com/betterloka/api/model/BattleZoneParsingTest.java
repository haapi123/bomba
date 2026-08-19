package com.betterloka.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing checks against payloads shaped exactly like the ones api.lokamc.com returns. */
class BattleZoneParsingTest {
    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void readsKillsDeathsAndHeadcounts() {
        BattleZone battle = BattleZone.fromJson(parse("""
                {
                  "territoryId": "6123974116376e26f1a0d271",
                  "name": "lilboi-9",
                  "defendingPlayers": {
                    "6738ef39-4050-4ee4-bc79-5e81a9d4a28c": {
                      "deaths": 2, "kills": 0, "participationState": "PARTICIPATED",
                      "uuid": "6738ef39-4050-4ee4-bc79-5e81a9d4a28c",
                      "identityId": "622bf47e5073ed7c6ef9d722", "townId": "67180381251cd44e010b9d1b"
                    },
                    "d526803b-f536-4949-90d9-ba1354f07017": {
                      "deaths": 0, "kills": 0, "participationState": "REGISTERED",
                      "uuid": null, "identityId": null, "townId": null
                    }
                  },
                  "attackingPlayers": {
                    "a85b9c05-1cf2-4966-83d5-185fe658761c": {
                      "deaths": 1, "kills": 4, "participationState": "PARTICIPATED",
                      "uuid": null, "identityId": null, "townId": "6442ab66d2d38e05840d20ae"
                    }
                  },
                  "attackerName": "Justice League",
                  "attackerTownId": "6442ab66d2d38e05840d20ae",
                  "defenderName": null,
                  "defenderTownId": "6640193d7617b548c0793068",
                  "timeStarted": 1738169000000,
                  "timeEnded": 1738173450169,
                  "active": false,
                  "_links": { "self": { "href": "http://api.lokamc.com/battlezones/679a63695bd6ac33c81d1b8f" } }
                }
                """));

        assertNotNull(battle);
        assertEquals("679a63695bd6ac33c81d1b8f", battle.id(), "the id only exists in the HAL self link");
        assertEquals("lilboi-9", battle.territory());
        assertEquals(1738173450169L, battle.timeEnded());
        assertEquals("Justice League", battle.attackerName());
        assertNull(battle.defenderName(), "newer records leave the name null and only carry the town id");
        assertEquals("6640193d7617b548c0793068", battle.defenderTownId());

        assertEquals(3, battle.participants().size());
        assertEquals(1, battle.attackerCount());
        assertEquals(1, battle.defenderCount(), "a REGISTERED no-show must not inflate the head count");

        BattleParticipant attacker = battle.participants().stream()
                .filter(BattleParticipant::attacker)
                .findFirst()
                .orElseThrow();
        assertEquals(UUID.fromString("a85b9c05-1cf2-4966-83d5-185fe658761c"), attacker.uuid(),
                "the map key is authoritative when the inline uuid field is null");
        assertEquals(4, attacker.kills());
        assertEquals(1, attacker.deaths());
        assertTrue(attacker.participated());
        assertEquals("6442ab66d2d38e05840d20ae", attacker.townId());

        BattleParticipant noShow = battle.participants().stream()
                .filter(p -> !p.attacker() && !p.participated())
                .findFirst()
                .orElseThrow();
        assertFalse(noShow.participated());
        assertNull(noShow.townId());
    }

    @Test
    void rejectsRecordsWithoutASelfLink() {
        assertNull(BattleZone.fromJson(parse("{\"name\":\"south-1\",\"timeEnded\":1}")),
                "without an id a battle cannot be de-duplicated, so it must be dropped");
    }

    @Test
    void readsPlayerIdentityAndFirstSeen() {
        LokaPlayer player = LokaPlayer.fromJson(parse("""
                {
                  "id": "66c3e25f915250421d786273",
                  "identityId": "62dc21f402091d552f431c6d",
                  "name": "cusecarb",
                  "rank": "sentry",
                  "uuid": "7de98534-bfde-442e-8d3f-8898c6d942a2",
                  "town": { "id": null, "name": null }
                }
                """));

        assertEquals("cusecarb", player.name());
        assertEquals(UUID.fromString("7de98534-bfde-442e-8d3f-8898c6d942a2"), player.uuid());
        assertNull(player.inlineTownName(), "the inline town is routinely null and must not be trusted");

        // The identity ObjectID is older than the account ObjectID for a player with alts, and it is
        // the identity that marks when the person first appeared on Loka.
        assertEquals(2022, player.firstSeen().atZone(ZoneOffset.UTC).getYear());
        assertEquals(2024, player.accountCreated().atZone(ZoneOffset.UTC).getYear());
    }

    @Test
    void decodesObjectIdTimestamps() {
        // Cryptite, the server owner, holds the oldest player document on Loka.
        Instant created = ObjectIds.timestamp("5462b7c5e4b0cad9a0f3e6a4");
        assertNotNull(created);
        assertEquals(2014, created.atZone(ZoneOffset.UTC).getYear());

        assertNull(ObjectIds.timestamp(null));
        assertNull(ObjectIds.timestamp("tooshort"));
        assertNull(ObjectIds.timestamp("zzzzzzzze4b0cad9a0f3e6a4"));
    }

    @Test
    void readsTownDetails() {
        LokaTown town = LokaTown.fromJson(parse("""
                {
                  "id": "65fcb14f46b4da104eb4021a",
                  "name": "Dawnstar",
                  "strength": 136.0,
                  "townLevel": 25.0,
                  "world": "south",
                  "recruiting": false,
                  "deleted": false,
                  "members": { "a": { "subowner": false }, "b": { "subowner": true } }
                }
                """));

        assertEquals("Dawnstar", town.name());
        assertEquals("Garama", town.continentName());
        assertEquals(2, town.memberCount());
        assertEquals(25.0, town.townLevel());
        assertFalse(town.recruiting());
    }
}
