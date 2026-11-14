package com.betterloka.conquest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ascalon as it stood on 12 November 2026 (see {@link StandingsTest}), played forward. */
class SimulationTest {
    private static final Standings STANDINGS = StandingsTest.STANDINGS;
    private static final Standings.Side HOOLIGANS = STANDINGS.side("gh", null);

    private static List<Simulation.Fight> times(int count, String opponent, boolean win) {
        return new ArrayList<>(Collections.nCopies(count, new Simulation.Fight(opponent, win)));
    }

    @Test
    void fiveWinsInARowEachCountFromWhereTheLastLeftOff() {
        Simulation.Result result = Simulation.run(STANDINGS, "west", HOOLIGANS, null, times(5, "pp", true));
        List<Integer> changes = new ArrayList<>();
        for (Simulation.Step step : result.steps()) {
            changes.add(step.change());
            assertTrue(step.allowed());
            assertEquals(28, step.k());
        }
        assertEquals(List.of(16, 14, 13, 11, 9), changes, "every win is worth less than the one before");
        assertEquals(377, result.start());
        assertEquals(440, result.end());
        assertEquals(63, result.net());
        assertEquals(5, result.counted());

        Simulation.Step second = result.steps().get(1);
        assertEquals(393, second.youBefore());
        assertEquals(407, second.youAfter());
        assertEquals(421, second.themBefore());
        assertEquals(407, second.themAfter());
        assertEquals(2, second.rankBefore());
        assertEquals(1, second.rankAfter(), "level on Strength, and ahead by name");

        assertEquals(2, result.startRank());
        assertEquals(1, result.endRank());
        assertEquals("Golden Hooligans", result.ranking().get(0).side().name());
        assertEquals(440, result.ranking().get(0).strength());
        assertEquals(2, result.ranking().get(0).before());
        assertEquals(63, result.ranking().get(0).change());
        assertEquals("Primate Pact", result.ranking().get(1).side().name());
        assertEquals(374, result.ranking().get(1).strength());
        assertEquals(-63, result.ranking().get(1).change());
        assertEquals(0, result.ranking().get(2).change(), "nobody else moves");
    }

    @Test
    void lossesGrowWhileWinsShrink() {
        Simulation.Result result = Simulation.run(STANDINGS, "west", HOOLIGANS, null, times(3, "pp", false));
        assertEquals(List.of(9, 8, 7), result.steps().stream().map(Simulation.Step::change).toList());
        assertEquals(353, result.end());
        assertEquals(461, result.ranking().get(0).strength());
        assertEquals(2, result.endRank());
    }

    @Test
    void aFightLokaWouldNotAllowMovesNothingAndIsSaidSo() {
        // Pantheon (170, level 19): a Hooligans win would be worth 1, so the fight cannot be placed.
        List<Simulation.Fight> fights = new ArrayList<>(times(1, "pantheon", true));
        fights.add(new Simulation.Fight("pp", true));
        Simulation.Result result = Simulation.run(STANDINGS, "west", HOOLIGANS, null, fights);
        Simulation.Step blocked = result.steps().get(0);
        assertFalse(blocked.allowed());
        assertEquals(22, blocked.k());
        assertEquals(0, blocked.change());
        assertEquals(377, blocked.youAfter());
        assertEquals(1, result.counted());
        assertEquals(393, result.end(), "the win over Primate Pact still counts, from the unchanged 377");
    }

    @Test
    void mixedOpponentsAndOutcomesAndThingsThatCannotBePlayed() {
        List<Simulation.Fight> fights = List.of(
                new Simulation.Fight("pp", true),
                new Simulation.Fight("pp", false),
                new Simulation.Fight("gh", true),        // itself: skipped
                new Simulation.Fight("nobody", true),    // not in the standings: skipped
                new Simulation.Fight("crown", false));   // 393 against 176: not allowed
        Simulation.Result result = Simulation.run(STANDINGS, "west", HOOLIGANS, null, fights);
        assertEquals(3, result.steps().size());
        assertEquals(List.of(1, 2, 3), result.steps().stream().map(Simulation.Step::number).toList());
        Simulation.Step loss = result.steps().get(1);
        assertFalse(loss.win());
        assertEquals(StrengthMath.change(421, 393, 28), loss.change(), "Primate Pact's win, from where the first fight left both");
        assertEquals(11, loss.change());
        assertEquals(382, loss.youAfter());
        assertEquals(432, loss.themAfter());
        Simulation.Step crown = result.steps().get(2);
        assertEquals("Crownlands", crown.opponent().name());
        assertFalse(crown.allowed());
        assertEquals(382, result.end());
    }

    @Test
    void balakCountsItsOwnStrengthAndRanksWhoeverIsFought() {
        Standings.Side suna = STANDINGS.side("suna", null);
        Simulation.Result result = Simulation.run(STANDINGS, "bigboi", suna, null, times(2, "dominion", true));
        assertEquals(100, result.start(), "Sunagakure has not fought on Balak");
        assertEquals(14, result.steps().get(0).k());
        assertEquals(StrengthMath.change(100, 84, 14), result.steps().get(0).change());
        assertTrue(result.ranking().stream().anyMatch(row -> row.side().id().equals("suna")));
    }

    @Test
    void stopsAtThirtyFights() {
        Simulation.Result result = Simulation.run(STANDINGS, "west", HOOLIGANS, null, times(50, "pp", true));
        assertEquals(Simulation.MAX_FIGHTS, result.steps().size());
    }
}
