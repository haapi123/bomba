package com.betterloka.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading who killed whom off a fight page.
 *
 * <p>A page's {@code deathInfo} is the only place on either service that attributes a kill to a
 * person — Loka's battle records count kills and deaths per player and never name an opponent — so
 * this is the whole basis of Nemesis.
 */
class KillAttributionTest {
    /** Verbatim shape from a real fight page, trimmed to what the parser reads. */
    private static final String PAGE = """
            <script>var fightData = {"playerNames":["7sew","Direct1mpact","haapi"],
            "location":"marred_steppes","attackersWon":true,
            "attackers":{"players":[
              {"name":"7sew","deaths":1,"deathInfo":[{"killedBy":"Direct1mpact","playerName":"7sew","time":15.0}]},
              {"name":"haapi","deaths":2,"deathInfo":[
                 {"killedBy":"Direct1mpact","playerName":"haapi","time":3.0},
                 {"killedBy":"Vayshee","playerName":"haapi","time":9.0}]}]},
            "defenders":{"players":[
              {"name":"Direct1mpact","deaths":1,"deathInfo":[{"killedBy":"haapi","playerName":"Direct1mpact","time":11.0}]}]}};
            </script>""";

    @Test
    void everyDeathNamesItsKiller() {
        List<EldritchApi.Kill> kills = EldritchApi.parseKills(PAGE);

        assertEquals(4, kills.size());
        assertTrue(kills.contains(new EldritchApi.Kill("Direct1mpact", "7sew")));
        assertTrue(kills.contains(new EldritchApi.Kill("Direct1mpact", "haapi")));
        assertTrue(kills.contains(new EldritchApi.Kill("Vayshee", "haapi")));
        assertTrue(kills.contains(new EldritchApi.Kill("haapi", "Direct1mpact")));
    }

    /** A death to the environment or a golem names no player and is nobody's kill. */
    @Test
    void aDeathWithNoKillerIsNotAKill() {
        String page = """
                var fightData = {"attackers":{"players":[
                  {"name":"haapi","deathInfo":[{"killedBy":null,"playerName":"haapi"},
                                               {"killedBy":"","playerName":"haapi"}]}]},
                "defenders":{"players":[]}};""";

        assertTrue(EldritchApi.parseKills(page).isEmpty());
    }

    @Test
    void aPageWithNoFightDataYieldsNothing() {
        assertTrue(EldritchApi.parseKills("<html>nothing here</html>").isEmpty());
    }

}
