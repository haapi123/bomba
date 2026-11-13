package com.betterloka.conquest;

import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ScheduledFight;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ascalon as it stood on 12 November 2026, with one town in no alliance added. */
class StandingsTest {
    static LokaTown town(String id, String name, String world, double level, double strength, double balak) {
        return LokaTown.fromSaved(new LokaTown.Saved(id, name, world, null, level, strength, false, 10, 3,
                "1", null, List.of(), balak));
    }

    static LokaAlliance alliance(String id, String name, double strength, double balak, String leader, String... towns) {
        return new LokaAlliance(id, name, strength, balak, 3, leader, List.of(towns));
    }

    static final List<LokaTown> TOWNS = List.of(
            town("silvermoon", "Silvermoon", "west", 25, 100, 100),
            town("proxima", "Proxima", "west", 25, 100, 100),
            town("matrix", "Matrix", "west", 25, 100, 100),
            town("truckia", "Truckia", "west", 25, 100, 100),
            town("vinovia", "Vinovia", "north", 25, 100, 100),
            town("pantheon", "Pantheon", "west", 19, 170, 0),
            town("farum", "Farum Azula", "south", 25, 100, 100),
            town("touchdown", "Touchdown", "south", 25, 100, 100),
            // A Garama town fighting for an Ascalon alliance.
            town("merc", "Sword's Edge", "south", 25, 100, 100));

    static final List<LokaAlliance> ALLIANCES = List.of(
            alliance("gh", "Golden Hooligans", 377, 137, "silvermoon", "silvermoon", "proxima", "merc"),
            alliance("pp", "Primate Pact", 437, 125, "matrix", "matrix"),
            alliance("crown", "Crownlands", 176, 100, "truckia", "truckia"),
            alliance("wolf", "Wolf Force", 300, 86, "vinovia", "vinovia"),
            alliance("dominion", "The Dominion", 318, 84, "farum", "farum"),
            alliance("suna", "Sunagakure", 241, 100, "touchdown", "touchdown"),
            alliance("empty", "nuxva Alliance", 100, 100, "gone"));

    static final Standings STANDINGS = Standings.of(ALLIANCES, TOWNS);

    private static List<String> names(List<Standings.Row> rows) {
        List<String> names = new ArrayList<>();
        for (Standings.Row row : rows) {
            names.add(row.rank() + " " + row.side().name() + " " + (int) row.strength());
        }
        return names;
    }

    @Test
    void aContinentRanksItsAlliancesAndItsLoneTowns() {
        assertEquals(List.of("1 Primate Pact 437", "2 Golden Hooligans 377", "3 Crownlands 176", "4 Pantheon 170"),
                names(STANDINGS.ranking("west")));
        assertEquals(List.of("1 The Dominion 318", "2 Sunagakure 241"), names(STANDINGS.ranking("south")),
                "the mercenary town is counted with its alliance, on Ascalon");
        assertEquals(2, STANDINGS.rankOf("west", STANDINGS.sideOfTown("proxima")));
        assertEquals(0, STANDINGS.rankOf("north", null));
    }

    @Test
    void aTownStandsForItsAllianceAndALoneTownForItself() {
        Standings.Side hooligans = STANDINGS.sideOfTown("merc");
        assertEquals("Golden Hooligans", hooligans.name());
        assertTrue(hooligans.alliance());
        assertEquals("west", hooligans.world(), "the alliance's continent is its leading town's");
        assertEquals(377, hooligans.strengthOn("west"));
        assertEquals(137, hooligans.strengthOn("bigboi"));

        Standings.Side pantheon = STANDINGS.sideOfTown("pantheon");
        assertFalse(pantheon.alliance());
        assertEquals(170, pantheon.strength());
        assertEquals(Standings.BASELINE, pantheon.balakStrength(), "an unknown Balak figure reads as the starting 100");
        assertEquals(19, pantheon.level());
        assertNull(STANDINGS.sideOfTown("nobody"));
        assertEquals("Primate Pact", STANDINGS.side("pp", null).name());
        assertEquals("Pantheon", STANDINGS.side(null, "pantheon").name());
    }

    @Test
    void theRankingAfterAFightMovesTheTwoSidesAndSaysFromWhere() {
        Standings.Side hooligans = STANDINGS.side("gh", null);
        Standings.Side pact = STANDINGS.side("pp", null);
        // The real fight of 12 November: Golden Hooligans won Ascalon 85 and took 16.
        int change = StrengthMath.change(377, 437, 28);
        assertEquals(16, change);
        List<Standings.Row> won = STANDINGS.rankingAfter("west", hooligans, pact, change);
        assertEquals(List.of("1 Primate Pact 421", "2 Golden Hooligans 393", "3 Crownlands 176", "4 Pantheon 170"), names(won));
        assertEquals(-16, won.get(0).change());
        assertEquals(16, won.get(1).change());

        // Twice more and they change places.
        List<Standings.Row> swapped = STANDINGS.rankingAfter("west", hooligans, pact, 40);
        assertEquals(List.of("1 Golden Hooligans 417", "2 Primate Pact 397", "3 Crownlands 176", "4 Pantheon 170"), names(swapped));
        assertEquals(2, swapped.get(0).before());
        assertEquals(1, swapped.get(1).before());
        assertEquals(3, swapped.get(2).before());
        assertEquals(0, swapped.get(2).change());
    }

    @Test
    void balakRanksOnlyThoseWhoHaveFoughtThereAndWhoeverIsAskedAbout() {
        assertEquals(List.of("1 Golden Hooligans 137", "2 Primate Pact 125", "3 Wolf Force 86", "4 The Dominion 84"),
                names(STANDINGS.ranking("bigboi")));
        Standings.Side suna = STANDINGS.side("suna", null);
        assertEquals(List.of("1 Golden Hooligans 137", "2 Primate Pact 125", "3 Sunagakure 100", "4 Wolf Force 86",
                "5 The Dominion 84"), names(STANDINGS.ranking("bigboi", suna)));
        // The Dominion (88) lost to Golden Hooligans (137) on Balak 12: 4 points, k 14.
        List<Standings.Row> after = STANDINGS.rankingAfter("bigboi", STANDINGS.side("gh", null),
                STANDINGS.side("dominion", null), 4);
        assertEquals("1 Golden Hooligans 141", names(after).get(0));
        assertEquals("4 The Dominion 80", names(after).get(3));
    }

    @Test
    void findsASideByTownOrAllianceHoweverItIsTyped() {
        assertEquals("Golden Hooligans", STANDINGS.find("  silvermoon ").side().name());
        assertEquals("Silvermoon", STANDINGS.find("SILVERMOON").town().name());
        assertNull(STANDINGS.find("primate pact").town(), "an alliance was named, not a town");
        assertEquals("Primate Pact", STANDINGS.find("primate").side().name());
        assertEquals("Pantheon", STANDINGS.find("panth").side().name());
        assertEquals("Golden Hooligans", STANDINGS.find("hooligans").side().name(), "the only name containing it");
        assertNull(STANDINGS.find("t"), "fits several: not guessed");
        assertNull(STANDINGS.find(""));
        assertNull(STANDINGS.find("atlantis"));
        assertEquals(List.of("The Dominion", "Touchdown", "Truckia"), STANDINGS.suggestions("t", 3));
        assertTrue(STANDINGS.suggestions("oo", 10).containsAll(List.of("Golden Hooligans", "Silvermoon")));
    }

    static ScheduledFight fight(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return ScheduledFight.fromJson(object);
    }

    @Test
    void forecastsADeclaredFightFromItsOwnFiguresOrTheStandings() {
        // As Loka recorded Ascalon 85: Golden Hooligans 377 on Primate Pact 437.
        Standings.Forecast recorded = STANDINGS.forecast(fight("""
                {"name":"west-85","world":"west","attackerTownId":"proxima","attackerAllianceId":"gh",
                 "defenderTownId":"matrix","defenderAllianceId":"pp","attackerStrength":377.0,"defenderStrength":437.0}"""));
        assertNotNull(recorded);
        assertEquals(28, recorded.k());
        assertEquals(16, recorded.attackerWin());
        assertEquals(9, recorded.defenderWin());
        assertFalse(recorded.tooLittle());
        assertEquals("Golden Hooligans", recorded.attacker().name());

        // Not filled in yet: the standings' figures, and the lower town's level.
        Standings.Forecast pending = STANDINGS.forecast(fight("""
                {"name":"west-100","world":"west","attackerTownId":"silvermoon","attackerAllianceId":"gh",
                 "defenderTownId":"pantheon","attackerStrength":0.0,"defenderStrength":0.0}"""));
        assertEquals(377, pending.attackerStrength());
        assertEquals(170, pending.defenderStrength());
        assertEquals(22, pending.k(), "Pantheon is level 19");
        assertEquals(StrengthMath.change(377, 170, 22), pending.attackerWin());
        assertTrue(pending.tooLittle());

        // Balak counts its own Strength with its own k.
        Standings.Forecast balak = STANDINGS.forecast(fight("""
                {"name":"bigboi-12","world":"bigboi","attackerTownId":"farum","attackerAllianceId":"dominion",
                 "defenderTownId":"silvermoon","defenderAllianceId":"gh"}"""));
        assertEquals(84, balak.attackerStrength());
        assertEquals(137, balak.defenderStrength());
        assertEquals(14, balak.k());
    }

    @Test
    void neutralGroundRivinaAndStrangersForecastNothing() {
        assertNull(STANDINGS.forecast(fight("""
                {"name":"west-39","world":"west","attackerTownId":"truckia","attackerAllianceId":"crown"}""")),
                "nobody defends neutral ground");
        assertNull(STANDINGS.forecast(fight("""
                {"name":"lilboi-9","world":"lilboi","attackerTownId":"truckia","defenderTownId":"matrix"}""")),
                "Rivina is fought for conquest points");
        assertNull(STANDINGS.forecast(fight("""
                {"name":"west-1","world":"west","attackerTownId":"unknown-a","defenderTownId":"unknown-b"}""")));
        assertNull(STANDINGS.forecast(null));
        assertTrue(Standings.EMPTY.isEmpty());
    }

    @Test
    void theServiceReadsLoneTownsAfreshAndAlliancesFromTheList() {
        AtomicInteger townReads = new AtomicInteger();
        List<String> asked = new ArrayList<>();
        StandingsService service = new StandingsService(new StandingsService.Source() {
            @Override
            public List<LokaAlliance> alliances() {
                return ALLIANCES;
            }

            @Override
            public List<LokaTown> towns() {
                return TOWNS;
            }

            @Override
            public LokaTown town(String townId) {
                townReads.incrementAndGet();
                asked.add(townId);
                // The roster's Pantheon is hours old; it has since fought.
                return townId.equals("pantheon") ? StandingsTest.town("pantheon", "Pantheon", "west", 19, 183, 100) : null;
            }
        }, Runnable::run);

        service.watch("silvermoon");
        service.poll(System.currentTimeMillis());
        assertEquals(List.of("pantheon"), asked, "only the lone town near the top: Silvermoon's Strength is its alliance's");
        assertEquals(183, service.standings().sideOfTown("pantheon").strength());
        assertEquals(List.of("1 Primate Pact 437", "2 Golden Hooligans 377", "3 Pantheon 183", "4 Crownlands 176"),
                names(service.standings().ranking("west")));
        assertFalse(service.failed());
        assertTrue(service.updatedAt() > 0);

        // Asked again at once: nothing goes out until the reading is a minute old.
        service.poll(System.currentTimeMillis());
        assertEquals(1, townReads.get());
        service.refreshSoon();
        service.poll(System.currentTimeMillis());
        assertEquals(1, townReads.get(), "and the town's own reading is still good");
    }
}
