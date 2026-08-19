package com.betterloka.api;

import com.betterloka.api.model.EldritchStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser checks against markup shaped exactly like eldritchbot.com's, so a change to their templates
 * shows up here rather than as silently zeroed statistics in game.
 */
class EldritchParsingTest {

    /** Trimmed from a real player page, keeping every element the parser looks at. */
    private static final String PLAYER_PAGE = """
            <!DOCTYPE html><html><head><title>Rezorie&#39;s Stats | EldritchBot</title></head><body>
            <h1 id="player" style="margin-bottom: 0" data-toggle="tooltip" ><span>Rezorie</span></h1>
            <h3 style="margin: 0; color: #617487">New Silverhand</h3>
            <h4 style="margin: 0; color: #617487">Wins: <span style="color: #2c3e50">440</span></h4>
            <h4 class="h4-resize"><span>Last Fight: <span style="color: #2c3e50">Wed Aug 12 2026</span></span></h4>
            <table class="table recentFights">
              <tr>
                <td><a href="../fight?id=N2cbtNrqf" class="block"><h5>8/12</h5></a></td>
                <td><a href="../fight?id=N2cbtNrqf" class="block"><h5><span class='defeat'>defeat</span></h5></a></td>
                <td><a href="../fight?id=N2cbtNrqf" class="block"><h5><span class='assists'>Unwierdly&#39;s Town</h5></a></td>
                <td><a href="../fight?id=N2cbtNrqf" class="block"><h5>Targon</h5></a></td>
              </tr>
              <tr>
                <td><a href="../fight?id=kh9qnFpyY" class="block"><h5>8/8</h5></a></td>
                <td><a href="../fight?id=kh9qnFpyY" class="block"><h5><span class='victory'>victory</span></h5></a></td>
                <td><a href="../fight?id=kh9qnFpyY" class="block"><h5>Invicta</h5></a></td>
                <td><a href="../fight?id=kh9qnFpyY" class="block"><h5><span class='assists'>Sword&#39;s Edge</h5></a></td>
              </tr>
            </table>
            <h4 style="margin-bottom: 0;">Nemesis:</h4>
            <h2 id="nemesis" style="margin-top: 0;"><span>Blissterz</span></h2>
            <h3 style="margin-bottom: 0;"><span class="deaths">7 Deaths</span></h3>
            <h4>Combat</h4>
            <table class="table player-stats">
              <tr><td>Kills: </td><td class="text-right player-stats-value">1151</td></tr>
              <tr><td>Deaths: </td><td class="text-right player-stats-value">349</td></tr>
              <tr><td>Assists: </td><td class="text-right player-stats-value">463</td></tr>
            </table>
            <h4>Consumables</h4>
            <table class="table player-stats">
              <tr><td>Food: </td><td class="text-right player-stats-value">9756</td></tr>
              <tr><td>Potions: </td><td class="text-right player-stats-value">50111</td></tr>
              <tr><td>Pearls: </td><td class="text-right player-stats-value">10310</td></tr>
              <tr><td>Ancient Ingots: </td><td class="text-right player-stats-value">3050</td></tr>
            </table>
            <h4>Conquest</h4>
            <table class="table player-stats">
              <tr><td>Wins: </td><td class="text-right player-stats-value">440</td></tr>
              <tr><td>Losses: </td><td class="text-right player-stats-value">180</td></tr>
              <tr><td>Golems: </td><td class="text-right player-stats-value">321</td></tr>
              <tr><td>Lamps: </td><td class="text-right player-stats-value">290</td></tr>
              <tr><td>First Bloods: </td><td class="text-right player-stats-value">14</td></tr>
              <tr><td>Close Calls: </td><td class="text-right player-stats-value">1264</td></tr>
            </table>
            </body></html>
            """;

    private static final String FIGHT_PAGE = """
            <html><body><script>var stats = {"players":15838};</script>
            <script>var fightData = {"playerNames":["Rezorie","Dnnh"],"length":8,"location":"ice_taiga",
            "startTime":888.7,"attackersWon":false,
            "attackers":{"players":[
              {"name":"Dnnh","uuid":"b9c5","town":"Vanguard","pkills":5,"gkills":0,"assists2":2,"lamps":0,
               "deaths":0,"charges":[],"potions":89,"pearls":16,"closeCalls":1},
              {"name":"Rezorie","uuid":"17a8","town":"New Silverhand","pkills":3,"gkills":1,"assists2":4,
               "lamps":2,"deaths":1,"charges":[{"time":0.7}],"potions":44,"pearls":8,"closeCalls":4}
            ]},
            "defenders":{"players":[
              {"name":"Ghuraa","uuid":"aaaa","town":"Targon","pkills":1,"gkills":0,"assists2":0,"lamps":0,
               "deaths":2,"charges":[],"potions":10,"pearls":1,"closeCalls":0}
            ]}};</script>
            </body></html>
            """;

    @Test
    void readsEveryStatTable() {
        EldritchStats stats = EldritchApi.parsePlayerPage(PLAYER_PAGE);

        assertEquals("Rezorie", stats.name());
        assertEquals("New Silverhand", stats.town());
        assertEquals("Wed Aug 12 2026", stats.lastFight());

        assertEquals(1151, stats.kills());
        assertEquals(349, stats.deaths());
        assertEquals(463, stats.assists());

        assertEquals(9756, stats.food());
        assertEquals(50111, stats.potions());
        assertEquals(10310, stats.pearls());
        assertEquals(3050, stats.ancientIngots());

        assertEquals(440, stats.wins());
        assertEquals(180, stats.losses());
        assertEquals(620, stats.totalFights());
        assertEquals(321, stats.golems());
        assertEquals(290, stats.lamps());
        assertEquals(14, stats.firstBloods());
        assertEquals(1264, stats.closeCalls());

        assertEquals("Blissterz", stats.nemesisName());
        assertEquals(7, stats.nemesisDeaths());

        assertEquals(3.29, stats.killDeathRatio(), 0.01);
        assertEquals(0.709, stats.winRate(), 0.01);
    }

    @Test
    void readsRecentFightsInOrderWithoutDuplicates() {
        EldritchStats stats = EldritchApi.parsePlayerPage(PLAYER_PAGE);

        assertEquals(2, stats.recentFights().size(), "each row links its id four times but is one fight");

        EldritchStats.RecentFight newest = stats.recentFights().get(0);
        assertEquals("N2cbtNrqf", newest.id());
        assertEquals("8/12", newest.date());
        assertEquals(false, newest.victory());
        assertEquals("Unwierdly's Town", newest.attackerTown(), "HTML entities in town names must be decoded");
        assertEquals("Targon", newest.defenderTown());
        assertEquals("Unwierdly's Town", newest.ownTown(),
                "the cell carrying the assists class marks the side this player fought on");
        assertEquals("Targon", newest.opponent());

        EldritchStats.RecentFight older = stats.recentFights().get(1);
        assertEquals("Sword's Edge", older.ownTown(),
                "the marked cell moves between columns when the player defends instead of attacks");
        assertEquals("Invicta", older.opponent());

        assertEquals("kh9qnFpyY", stats.recentFights().get(1).id());
        assertEquals(true, stats.recentFights().get(1).victory());
    }

    @Test
    void missingPlayerPageIsNotFound() {
        ApiException error = assertThrows(ApiException.class,
                () -> EldritchApi.requirePlayerPage("<html><body>Redirecting</body></html>", "Nobody"));
        assertTrue(error.notFound());
    }

    @Test
    void readsOnePlayersLineFromAFight() {
        var detail = EldritchApi.parseFightData(FIGHT_PAGE, "abc123", "Rezorie");

        assertEquals("ice_taiga", detail.location());
        assertEquals(false, detail.attackersWon());
        assertEquals(2, detail.attackerCount());
        assertEquals(1, detail.defenderCount());

        assertEquals(true, detail.playerAttacked());
        assertEquals("New Silverhand", detail.playerTown());
        assertEquals(3, detail.kills());
        assertEquals(1, detail.golemKills());
        assertEquals(1, detail.deaths());
        assertEquals(4, detail.assists());
        assertEquals(2, detail.lamps());
        assertEquals(44, detail.potions());
        assertEquals(8, detail.pearls());
        assertEquals(4, detail.closeCalls());

        assertEquals(2, detail.ownSideCount());
        assertEquals(1, detail.enemySideCount());
        assertEquals(false, detail.playerWon(), "they attacked and the attackers lost");
    }

    @Test
    void findsPlayersOnTheDefendingSideToo() {
        var detail = EldritchApi.parseFightData(FIGHT_PAGE, "abc123", "ghuraa");

        assertEquals(false, detail.playerAttacked(), "name matching must ignore casing");
        assertEquals("Targon", detail.playerTown());
        assertEquals(2, detail.deaths());
        assertEquals(1, detail.ownSideCount());
        assertEquals(2, detail.enemySideCount());
        assertEquals(true, detail.playerWon(), "they defended and the attackers lost");
    }

    @Test
    void returnsNullForAPlayerNotInTheFight() {
        assertEquals(null, EldritchApi.parseFightData(FIGHT_PAGE, "abc123", "SomeoneElse"));
    }

}
