package com.betterloka.fights;

import com.betterloka.api.ApiException;
import com.betterloka.api.model.ScheduledFight;
import com.betterloka.api.model.TerritoryDetails;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a declared fight starts, and how the map keeps the list current.
 *
 * <p>The ids are real ones from {@code /battlezones}: {@code 6aea2fc4...} was declared at
 * 19:02:28 UTC on 3 November 2026, and west-147, defended, was due an hour later.
 */
class FightTimingTest {
    private static final String WEST_147 = "6aea2fc4d1cf0f95bf836b9f";
    private static final long WEST_147_DECLARED = Instant.parse("2026-11-03T19:02:28Z").toEpochMilli();

    private static ScheduledFight fight(String id, String world, String number, String defenderTownId,
                                        boolean started, long timeStarted) {
        String json = """
                {"name": "%s-%s", "world": "%s", "territoryId": "t-%s",
                 "attackerName": "Primate Pact", "attackerTownId": "a",
                 "defenderTownId": %s, "reins": true, "started": %s, "timeStarted": %d,
                 "attackingPlayers": {"u1": {"participationState": "REGISTERED", "identityId": "i1"},
                                      "u2": {"participationState": "REGISTERED", "identityId": "i2"}},
                 "defendingPlayers": {"u3": {"participationState": "ENTERED", "identityId": "i3"}},
                 "_links": {"self": {"href": "http://api.lokamc.com/battlezones/%s"}}}
                """.formatted(world, number, world, number,
                defenderTownId == null ? "null" : "\"" + defenderTownId + "\"", started, timeStarted, id);
        return ScheduledFight.fromJson(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void theIdAndTheWarpersComeOffTheRecord() {
        ScheduledFight fight = fight(WEST_147, "west", "147", "d", false, 0);
        assertEquals(WEST_147, fight.id());
        assertEquals("147", fight.territoryNumber());
        assertEquals(2, fight.attackers().signedUp());
        assertEquals(List.of("i1", "i2"), fight.attackers().players().stream()
                .map(ScheduledFight.Warper::identityId).sorted().toList());
        assertEquals(1, fight.defenders().present());
        assertEquals(3, fight.warping());
        assertTrue(fight.reinforcementsAllowed());
        assertTrue(fight.defended());
    }

    @Test
    void aDefendedFightStartsAnHourAfterItIsDeclared() {
        ScheduledFight fight = fight(WEST_147, "west", "147", "d", false, 0);
        assertEquals(WEST_147_DECLARED, FightTiming.declaredAt(fight));
        assertEquals(WEST_147_DECLARED + 60 * 60 * 1000L, FightTiming.startsAt(fight));
    }

    @Test
    void neutralGroundWaitsAQuarterOfAnHourAndRivinaHalfAnHour() {
        assertEquals(WEST_147_DECLARED + 15 * 60 * 1000L,
                FightTiming.startsAt(fight(WEST_147, "west", "147", null, false, 0)));
        assertEquals(WEST_147_DECLARED + 30 * 60 * 1000L,
                FightTiming.startsAt(fight(WEST_147, "lilboi", "7", "d", false, 0)));
        assertEquals(WEST_147_DECLARED + 60 * 60 * 1000L,
                FightTiming.startsAt(fight(WEST_147, "bigboi", "9", "d", false, 0)), "Balak waits the hour");
        assertEquals(FightTiming.NEUTRAL_MILLIS, FightTiming.delay("lilboi", false));
    }

    @Test
    void onceUnderWayTheRealStartIsUsed() {
        long realStart = WEST_147_DECLARED + 61 * 60 * 1000L;
        assertEquals(realStart, FightTiming.startsAt(fight(WEST_147, "west", "147", "d", true, realStart)));
    }

    @Test
    void anIdThatIsNotAnObjectIdHasNoTime() {
        assertEquals(0, FightTiming.startsAt(fight("not-an-id", "west", "1", "d", false, 0)));
    }

    @Test
    void theClockShowsMinutesOrHours() {
        assertEquals("52:10", FightTiming.clock(52 * 60_000L + 10_000));
        assertEquals("1:00:00", FightTiming.clock(60 * 60_000L));
        assertEquals("0:01", FightTiming.clock(1));
        assertEquals("0:00", FightTiming.clock(-5));
    }

    @Test
    void aTerritoryRecordSaysItsMutatorBuffAndWhatItMakes() {
        TerritoryDetails details = TerritoryDetails.fromJson(JsonParser.parseString("""
                {"id": "t", "areaName": "balak_northfields", "world": "bigboi", "num": "9",
                 "tg": {"townId": "x", "moduleResource": "Kelp", "reins": true,
                        "capBountifulItem": null, "infestedType": null},
                 "mutator": "CAPPED_FIGHT", "conquestPoints": 0, "buff": "NETHER_INDUSTRY"}
                """).getAsJsonObject());
        assertEquals("CAPPED_FIGHT", details.mutator());
        assertEquals("Capped Fight", TerritoryDetails.pretty(details.mutator()));
        assertEquals("Nether Industry", TerritoryDetails.pretty(details.buff()));
        assertEquals("Balak Northfields", TerritoryDetails.pretty(details.areaName()));
        assertEquals("Kelp", details.resource());
        assertTrue(details.reins());
        assertNull(details.infestedType());
        assertNull(TerritoryDetails.pretty(null));
    }

    // --- keeping the list current ---

    private static final class FakeSource implements FightWatch.Source {
        List<ScheduledFight> fights = new ArrayList<>();
        int territoryCalls;

        @Override
        public List<ScheduledFight> fights() {
            return fights;
        }

        @Override
        public TerritoryDetails territory(String territoryId) throws ApiException {
            territoryCalls++;
            return new TerritoryDetails(territoryId, "west", "147", "forest", "x", "DOGPILE", null,
                    false, "Rabbit Hides", 0, null, null);
        }
    }

    @Test
    void aFightDeclaredWhileWatchingIsMarkedNewAndTheFirstOnesAreNot() {
        FakeSource source = new FakeSource();
        FightWatch watch = new FightWatch(source, Runnable::run);
        ScheduledFight old = fight(WEST_147, "west", "147", "d", false, 0);
        source.fights.add(old);

        watch.poll(1_000_000);
        assertEquals(1, watch.fights().size());
        assertFalse(watch.isNew(old, System.currentTimeMillis()), "there when watching began");
        assertEquals("DOGPILE", watch.territory("t-147").mutator());

        ScheduledFight fresh = fight("6aea30000000000000000000", "bigboi", "9", "d", false, 0);
        source.fights.add(fresh);
        watch.poll(1_000_000 + 5_000);
        assertEquals(1, watch.fights().size(), "not asked again inside fifteen seconds");
        watch.poll(1_000_000 + FightWatch.POLL_MILLIS);
        assertEquals(2, watch.fights().size());
        assertTrue(watch.isNew(watch.byId(fresh.id()), System.currentTimeMillis()));
        assertEquals(fresh.id(), watch.at("bigboi", "9").id());
        assertEquals(1, watch.on("west").size());
        assertEquals(2, source.territoryCalls, "each territory once, not once a poll");
    }

    @Test
    void aFightThatEndsLeavesTheList() {
        FakeSource source = new FakeSource();
        FightWatch watch = new FightWatch(source, Runnable::run);
        source.fights.add(fight(WEST_147, "west", "147", "d", false, 0));
        watch.poll(0 + FightWatch.POLL_MILLIS);
        source.fights.clear();
        watch.refreshSoon();
        watch.poll(FightWatch.POLL_MILLIS + 1);
        assertTrue(watch.fights().isEmpty());
        assertNull(watch.at("west", "147"));
    }
}
