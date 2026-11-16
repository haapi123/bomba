package com.betterloka.duel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelTest {
    private static final int ME = 1;
    private static final int THEM = 2;
    private static final int BYSTANDER = 3;

    @Test
    void onlyLokasOwnLineFindsADuel() {
        assertTrue(DuelMessages.isMatchReady("Match Ready! [Click to join]"));
        assertTrue(DuelMessages.isMatchReady("  match ready! [click to join] "));
        assertTrue(DuelMessages.isMatchReady("Match Ready!"));
        assertFalse(DuelMessages.isMatchReady("Steve: Match Ready! [Click to join]"), "somebody typing it in chat");
        assertFalse(DuelMessages.isMatchReady("[Town] Steve » Match Ready! [Click to join]"));
        assertFalse(DuelMessages.isMatchReady("Match Ready! [Click to join] lol"));
        assertFalse(DuelMessages.isMatchReady(null));
    }

    @Test
    void theButtonsOwnJoinIsUsedButNothingElseIs() {
        assertEquals("join", DuelMessages.joinCommand(null));
        assertEquals("join", DuelMessages.joinCommand("/join"));
        assertEquals("join 7f3a", DuelMessages.joinCommand("/join 7f3a"));
        assertEquals("join", DuelMessages.joinCommand("/pay Steve 1000000"), "a button that does anything else");
        assertEquals("join", DuelMessages.joinCommand("/joinTown Evil"));
        assertEquals("join", DuelMessages.joinCommand("/join x\nsay hi"));
    }

    /** Ten swings, six hits of which two crits and one sweep; them eight swings, three hits, one crit. */
    private static DuelStats fight() {
        DuelStats duel = new DuelStats(ME, 0);
        long t = 1000;
        for (int i = 0; i < 10; i++) {
            duel.swing(ME);
        }
        for (int i = 0; i < 8; i++) {
            duel.swing(THEM);
        }
        duel.hit(ME, THEM, t += 500);
        duel.crit(THEM, t);
        duel.hit(ME, THEM, t += 500);
        duel.hit(ME, THEM, t += 500);
        duel.sweep(ME, t);
        duel.hit(THEM, ME, t += 500);
        duel.crit(ME, t);
        duel.hit(THEM, ME, t += 500);
        duel.hit(ME, THEM, t += 500);
        duel.crit(THEM, t);
        duel.hit(ME, THEM, t += 500);
        duel.hit(THEM, ME, t += 500);
        duel.hit(ME, THEM, t += 500);
        return duel;
    }

    @Test
    void hitsCritsAndSweepsForBothSides() {
        DuelStats.Summary summary = fight().summary();
        assertEquals(THEM, summary.opponentId());
        DuelStats.Side you = summary.you();
        assertEquals(10, you.swings());
        assertEquals(6, you.hits());
        assertEquals(60, you.accuracy());
        assertEquals(2, you.crits());
        assertEquals(33, you.critShare(), "of the hits landed");
        assertEquals(1, you.sweeps());
        assertEquals(17, you.sweepShare());
        assertEquals(3, you.bestCombo(), "three in a row before being hit back");

        DuelStats.Side them = summary.them();
        assertEquals(8, them.swings());
        assertEquals(3, them.hits());
        assertEquals(38, them.accuracy());
        assertEquals(1, them.crits());
        assertEquals(2, them.bestCombo());
        assertEquals(4000, summary.millis(), "first hit to last");
        assertEquals(DuelStats.Result.UNKNOWN, summary.result());
    }

    @Test
    void theOpponentIsWhoeverTradedTheMostHits() {
        DuelStats duel = new DuelStats(ME, 0);
        duel.hit(BYSTANDER, ME, 100);
        duel.hit(ME, THEM, 200);
        duel.hit(THEM, ME, 300);
        assertEquals(THEM, duel.opponent());
        duel.hit(BYSTANDER, THEM, 400);
        assertEquals(THEM, duel.opponent(), "a fight between two others is not this player's");
    }

    @Test
    void aDeathDecidesIt() {
        DuelStats won = fight();
        won.died(BYSTANDER, 9000);
        assertFalse(won.decided(), "somebody else dying is nothing to do with it");
        won.died(THEM, 9000);
        assertTrue(won.decided());
        assertTrue(won.over(9000));
        assertEquals(DuelStats.Result.WON, won.summary().result());
        won.hit(ME, THEM, 9500);
        assertEquals(6, won.summary().you().hits(), "nothing counts after the end");

        DuelStats lost = fight();
        lost.died(ME, 9000);
        assertEquals(DuelStats.Result.LOST, lost.summary().result());
    }

    @Test
    void quietForTwentySecondsIsTheEnd() {
        DuelStats duel = fight();
        assertFalse(duel.over(5500 + DuelStats.IDLE_END_MILLIS), "the last hit was at 5.5 s");
        assertTrue(duel.over(5500 + DuelStats.IDLE_END_MILLIS + 1));
    }

    @Test
    void aMatchNobodyFoughtIsForgottenAndNeverReported() {
        DuelStats duel = new DuelStats(ME, 0);
        duel.swing(ME);
        assertFalse(duel.fought());
        assertFalse(duel.over(DuelStats.ARMED_TIMEOUT_MILLIS * 2), "not over: it never started");
        assertFalse(duel.stale(DuelStats.ARMED_TIMEOUT_MILLIS));
        assertTrue(duel.stale(DuelStats.ARMED_TIMEOUT_MILLIS + 1));
        duel.died(ME, 10);
        assertFalse(duel.decided(), "a death before any fight is not a duel lost");
    }

    @Test
    void aCritWithNoHitBeforeItIsNobodys() {
        DuelStats duel = new DuelStats(ME, 0);
        duel.crit(THEM, 100);
        duel.sweep(BYSTANDER, 100);
        assertEquals(0, duel.summary().you().crits());
        assertEquals(0, duel.summary().you().sweeps());
    }

    @Test
    void aCritOrSweepIsOnlyEverAKindOfHit() {
        DuelStats duel = new DuelStats(ME, 0);
        duel.hit(ME, THEM, 1000);
        duel.sweep(ME, 1050);
        duel.sweep(ME, 1100);
        assertEquals(1, duel.summary().you().sweeps(), "one hit, one sweep at most");
        // A blow into the target's flashing frames: the sound, no hit — not a sweep of anything.
        duel.sweep(ME, 1000 + DuelStats.LINK_MILLIS + 200);
        assertEquals(1, duel.summary().you().sweeps());
        duel.hit(ME, THEM, 2000);
        duel.crit(THEM, 2010);
        duel.crit(THEM, 2020);
        duel.crit(THEM, 2000 + DuelStats.LINK_MILLIS + 100);
        assertEquals(1, duel.summary().you().crits());
        assertEquals(50, duel.summary().you().critShare());
        assertEquals(50, duel.summary().you().sweepShare());
    }

    @Test
    void fewerSwingsSeenThanHitsIsNoShareAtAll() {
        DuelStats duel = new DuelStats(ME, 0);
        duel.hit(ME, THEM, 100);
        duel.hit(THEM, ME, 600);
        duel.hit(THEM, ME, 1200);
        duel.swing(THEM);
        assertEquals(1, duel.summary().you().hits());
        assertEquals(-1, duel.summary().you().accuracy(), "a hit with no swing seen: not 100%");
        assertEquals(-1, duel.summary().them().accuracy(), "two hits, one swing seen: not 100% either");
        duel.swing(THEM);
        duel.swing(THEM);
        assertEquals(67, duel.summary().them().accuracy());
        assertEquals(0, new DuelStats(ME, 0).summary().you().accuracy(), "nothing at all is 0, not unknown");
    }

    @Test
    void theSummaryKnowsWhenTheFightWas() {
        DuelStats duel = new DuelStats(ME, 1000);
        duel.hit(ME, THEM, 5000);
        duel.hit(THEM, ME, 9000);
        DuelStats.Summary summary = duel.summary();
        assertEquals(5000, summary.startedAt());
        assertEquals(9000, summary.endedAt());
        assertEquals(4000, summary.millis());
    }
}
