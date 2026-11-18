package com.betterloka.duel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telling who won from what the server says and from the ranked record. */
class DuelOutcomeTest {
    private static final DuelStats.Result WON = DuelStats.Result.WON;
    private static final DuelStats.Result LOST = DuelStats.Result.LOST;

    private static DuelStats.Result read(String text) {
        return DuelOutcome.fromText(text, "Me_123", "Knucior");
    }

    @Test
    void titlesAddressedToThePlayer() {
        assertEquals(WON, read("§6§lVICTORY!"));
        assertEquals(WON, read("You won the duel!"));
        assertEquals(WON, read("You have defeated Knucior"));
        assertEquals(LOST, read("§c§lDEFEAT"));
        assertEquals(LOST, read("You lost!"));
        assertEquals(LOST, read("You were slain"));
        assertEquals(WON, read("Wygrałeś!"));
        assertEquals(LOST, read("Przegrałeś pojedynek"));
        assertEquals(WON, read("Zwycięstwo"));
    }

    @Test
    void linesThatNameTheWinnerOrTheLoser() {
        assertEquals(LOST, read("Winner: Knucior"));
        assertEquals(WON, read("Winner: Me_123"));
        assertEquals(LOST, read("Knucior has won the duel against Me_123!"));
        assertEquals(WON, read("Me_123 defeated Knucior (4.5 ❤)"));
        assertEquals(WON, read("Knucior was slain by Me_123"));
        assertEquals(LOST, read("Me_123 was slain by Knucior using [Diamond Sword]"));
        assertEquals(WON, read("Knucior died"));
        assertEquals(LOST, read("Me_123 lost 12 ELO"), "a name first: theirs, not the rating rule");
        assertEquals(LOST, read("knucior wins!"), "names in any case");
    }

    @Test
    void ratingChanges() {
        assertEquals(WON, read("+15 ELO"));
        assertEquals(LOST, read("-12 rating"));
        assertEquals(WON, read("ELO: 1215 (+15)"));
        assertNull(read("Knucior: -15 ELO"), "somebody else's change");
        assertNull(read("+15 ELO, -15 ELO"));
        assertNull(read("+0 ELO"));
    }

    @Test
    void whatSaysNothingOrBoth() {
        assertNull(read("Match Ready! [Click to join]"));
        assertNull(read("Fight starts in 5 seconds"));
        assertNull(read("3"));
        assertNull(read("Steve won the lottery"), "somebody else won something");
        assertEquals(LOST, read("Winner: Knucior · Loser: Me_123 · You lost"), "named and addressed agree");
        assertNull(read("Winner: Knucior · Winner: Me_123"), "both named winners: no answer");
        assertNull(read("Victory or defeat?"));
        assertNull(read("You lost connection"));
        assertNull(read(null));
    }

    @Test
    void playerChatIsToldApart() {
        Set<String> online = Set.of("Steve", "Knucior");
        assertTrue(DuelOutcome.looksLikePlayerChat("Steve: you lost lol", online::contains));
        assertTrue(DuelOutcome.looksLikePlayerChat("[Town] Steve » gg", online::contains));
        assertTrue(DuelOutcome.looksLikePlayerChat("<Knucior> victory", online::contains));
        assertFalse(DuelOutcome.looksLikePlayerChat("Winner: Knucior", online::contains), "Winner is nobody");
        assertFalse(DuelOutcome.looksLikePlayerChat("VICTORY!", online::contains));
    }

    @Test
    void oneGameMoreOnOneLadder() {
        Map<String, int[]> before = Map.of("pot", new int[]{78, 43}, "barebones", new int[]{9, 1});
        assertEquals(new DuelOutcome.Recorded(WON, "pot"),
                DuelOutcome.fromRecords(before, Map.of("pot", new int[]{79, 43}, "barebones", new int[]{9, 1})));
        assertEquals(new DuelOutcome.Recorded(LOST, "barebones"),
                DuelOutcome.fromRecords(before, Map.of("pot", new int[]{78, 43}, "barebones", new int[]{9, 2})));
        assertEquals(new DuelOutcome.Recorded(WON, "pot"),
                DuelOutcome.fromRecords(Map.of(), Map.of("pot", new int[]{1, 0})), "the first game of the season");
        assertNull(DuelOutcome.fromRecords(before, before), "not counted yet");
        assertNull(DuelOutcome.fromRecords(before, Map.of("pot", new int[]{80, 43}, "barebones", new int[]{9, 1})), "two games");
        assertNull(DuelOutcome.fromRecords(before, Map.of("pot", new int[]{79, 44}, "barebones", new int[]{9, 1})));
        assertNull(DuelOutcome.fromRecords(before, Map.of("pot", new int[]{0, 0})), "a new season");
        assertNull(DuelOutcome.fromRecords(null, before));
    }
}
