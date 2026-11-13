package com.betterloka.conquest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The formula against what Loka itself did.
 *
 * <p>Every row is a finished battle from Loka's own records (October and November 2026): the two
 * sides' Strength as the fight began, and what the winner's Strength was by its next fight. Only
 * fights with nothing else running for either side are here, so nothing else moved the numbers.
 */
class StrengthMathTest {
    /** winner's Strength, loser's Strength, what the winner gained and the loser lost. */
    private static final int[][] CONTINENT_FIGHTS = {
            {415, 208, 2},   // Golden Hooligans over Memeryy Alliance, Ascalon 188
            {306, 187, 6},   // Golden Hooligans hold Ascalon 147 against Crownlands
            {307, 252, 9},   // The Dominion over Sunagakure, Garama 76
            {297, 262, 10},
            {293, 267, 11},
            {293, 274, 11},
            {290, 277, 12},
            {286, 274, 12},
            {289, 278, 12},
            {280, 280, 13},  // level on Strength: half of 28, less one
            {280, 287, 13},
            {274, 286, 13},
            {277, 290, 13},
            {273, 287, 13},
            {272, 288, 14},
            {265, 302, 15},
            {263, 304, 15},
            {262, 305, 15},
            {256, 304, 16},
            {243, 316, 17},  // Sunagakure hold Garama 95 against The Dominion
            {317, 497, 22},  // Golden Hooligans over Primate Pact, Ascalon 63
            {339, 475, 20},  // and the two after it, which ran together but were counted in turn
            {359, 455, 18},
            {377, 437, 16},
            {309, 250, 9},
    };

    private static final int[][] BALAK_FIGHTS = {
            {133, 92, 4},
            {120, 97, 5},
            {118, 104, 5},
            {115, 102, 5},
            {113, 101, 5},
            {121, 120, 5},
            {115, 126, 6},
            {109, 132, 6},
            {137, 88, 4},
    };

    @Test
    void matchesEveryCleanFightOnTheContinents() {
        for (int[] fight : CONTINENT_FIGHTS) {
            assertEquals(fight[2], StrengthMath.change(fight[0], fight[1], StrengthMath.MAX_K),
                    fight[0] + " beat " + fight[1]);
        }
    }

    @Test
    void matchesEveryCleanFightOnBalak() {
        for (int[] fight : BALAK_FIGHTS) {
            assertEquals(fight[2], StrengthMath.change(fight[0], fight[1], StrengthMath.BALAK_K),
                    fight[0] + " beat " + fight[1] + " on Balak");
        }
    }

    @Test
    void theWikisOldConstantsDoNotFitTheSameFights() {
        // Its /400 would have given Golden Hooligans 19 for the win that was worth 22.
        double expected = 1.0 / (1.0 + Math.pow(10.0, (317 - 497) / 400.0));
        assertEquals(19, (int) Math.floor(28 * expected) - 1);
        assertEquals(22, StrengthMath.change(317, 497, 28));
    }

    @Test
    void theKFactorFallsWithTheLowerTownLevel() {
        assertEquals(28, StrengthMath.kFactor(25));
        assertEquals(22, StrengthMath.kFactor(19));
        assertEquals(28, StrengthMath.kFactor(40), "nothing above the highest level");
        assertEquals(3, StrengthMath.kFactor(-2));
        assertEquals(22, StrengthMath.kFactor("west", 25, 19.9), "the lower of the two, whole levels only");
        assertEquals(14, StrengthMath.kFactor("bigboi", 25, 3), "Balak does not look at levels");
        // Golden Hooligans (307) over Pantheon (173), a level 18 or 19 town at the time: +3, not the 5 of k 28.
        assertEquals(3, StrengthMath.change(307, 173, StrengthMath.kFactor(19)));
        assertEquals(3, StrengthMath.change(307, 173, StrengthMath.kFactor(18)));
        assertEquals(5, StrengthMath.change(307, 173, 28));
    }

    @Test
    void aHopelessWinIsWorthNothingAndNeverLessThanNothing() {
        assertEquals(0, StrengthMath.change(900, 100, 28));
        assertEquals(26, StrengthMath.change(100, 900, 28), "and the upset is worth k less two at the most");
    }

    @Test
    void stakesGiveBothOutcomesAndSayWhenLokaWouldNotAllowTheFight() {
        StrengthMath.Stakes even = StrengthMath.stakes(377, 437, 28);
        assertEquals(16, even.ifFirstWins());
        assertEquals(9, even.ifSecondWins());
        assertFalse(even.tooLittle());

        // Primate Pact (497) on Golden Hooligans (317): a win worth 3. Three such fights changed nothing.
        StrengthMath.Stakes lopsided = StrengthMath.stakes(497, 317, 28);
        assertEquals(3, lopsided.ifFirstWins());
        assertEquals(22, lopsided.ifSecondWins());
        assertTrue(lopsided.tooLittle());
    }

    @Test
    void rivinaIsNotFoughtForStrength() {
        assertFalse(StrengthMath.countsForStrength("lilboi"));
        assertTrue(StrengthMath.countsForStrength("west"));
        assertTrue(StrengthMath.countsForStrength("bigboi"));
        assertTrue(StrengthMath.isBalak("bigboi"));
        assertFalse(StrengthMath.isBalak("south"));
    }
}
