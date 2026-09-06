package com.betterloka.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** How a battle record turns into the line the Fight Manager shows. */
class ScheduledFightTest {
    private static ScheduledFight parse(String json) {
        return ScheduledFight.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    /**
     * The turnout split, from the states Loka records against each player.
     *
     * <p>{@code REGISTERED} is somebody down for the fight; {@code ENTERED} and
     * {@code PARTICIPATED} are somebody who actually warped in. This is the only honest pair of
     * numbers available — Loka publishes nothing about who is online.
     */
    @Test
    void turnoutSeparatesWhoCameFromWhoSignedUp() {
        ScheduledFight fight = parse("""
                {
                  "name": "lilboi-9",
                  "attackerName": "Justice League",
                  "defenderName": "Helian League",
                  "attackingPlayers": {
                    "a": {"participationState": "PARTICIPATED"},
                    "b": {"participationState": "ENTERED"},
                    "c": {"participationState": "REGISTERED"},
                    "d": {"participationState": "NONE"}
                  },
                  "defendingPlayers": {
                    "e": {"participationState": "PARTICIPATED"},
                    "f": {"participationState": "REGISTERED"}
                  }
                }
                """);

        assertEquals(2, fight.attackers().present());
        assertEquals(4, fight.attackers().signedUp());
        assertEquals(1, fight.defenders().present());
        assertEquals(2, fight.defenders().signedUp());
        assertEquals(3, fight.total());
    }

    @Test
    void bothSidesAreNamedFromTheRecordItself() {
        ScheduledFight fight = parse("""
                {"name": "lilboi-9", "attackerName": "Justice League",
                 "defenderName": "Helian League"}
                """);

        assertEquals("Justice League", fight.attackerName());
        assertEquals("Helian League", fight.defenderName());
    }

    /** Loka colours the defending region's name, and a section sign is not something to show. */
    @Test
    void colourCodesAreStrippedFromTheNames() {
        ScheduledFight fight = parse("""
                {"name": "bigboi-14", "attackerName": "Helian League",
                 "defenderName": "§6Desolate Coast"}
                """);

        assertEquals("Desolate Coast", fight.defenderName());
    }

    /** The fallback when nobody is named: readable, never raw snake_case. */
    @Test
    void theFightsOwnNameIsMadeReadable() {
        assertEquals("The Verdant Hallows",
                parse("{\"name\": \"the_verdant_hallows\"}").readableName());
        assertEquals("Lilboi 9", parse("{\"name\": \"lilboi-9\"}").readableName());
        assertNull(parse("{}").readableName());
    }

    /** A relocated fight is named for where it is actually being fought. */
    @Test
    void aRelocatedFightPrefersItsNewName() {
        assertEquals("North 143",
                parse("{\"name\": \"lilboi-9\", \"relocatedFightName\": \"north-143\"}")
                        .readableName());
    }

    @Test
    void anEmptyRecordCountsNobodyRatherThanThrowing() {
        ScheduledFight fight = parse("{\"name\": \"lilboi-1\"}");

        assertEquals(0, fight.attackers().present());
        assertEquals(0, fight.attackers().signedUp());
        assertEquals(0, fight.total());
    }
}
