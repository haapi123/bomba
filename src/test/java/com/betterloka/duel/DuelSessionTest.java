package com.betterloka.duel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A duel the way Loka runs it: found on one server, fought on another, over on a third. */
class DuelSessionTest {
    /** The player's entity id on each server: every server numbers its entities its own way. */
    private static final int ON_LOBBY = 10;
    private static final int ON_DUEL = 55;
    private static final int OPPONENT = 56;
    private static final int AFTER = 80;

    private final DuelSession session = new DuelSession();

    /** "Match Ready!" on the lobby, /join, the proxy switching, arriving on the duel server. */
    private void foundAndJoined(long now) {
        assertNull(session.arm(ON_LOBBY, now));
        session.tick(ON_LOBBY, false, now + 50);
        session.moved(null, now + 300);
        assertNotNull(session.duel(), "between servers the duel goes on");
        session.moved(ON_DUEL, now + 1500);
        session.tick(ON_DUEL, false, now + 1550);
    }

    private void fight(long from) {
        DuelStats duel = session.duel();
        for (int i = 0; i < 6; i++) {
            duel.swing(ON_DUEL);
        }
        duel.swing(OPPONENT);
        duel.swing(OPPONENT);
        duel.hit(ON_DUEL, OPPONENT, from);
        duel.crit(OPPONENT, from + 10);
        duel.hit(OPPONENT, ON_DUEL, from + 600);
        duel.hit(ON_DUEL, OPPONENT, from + 1200);
        duel.sweep(ON_DUEL, from + 1210);
        duel.hit(ON_DUEL, OPPONENT, from + 1800);
    }

    @Test
    void theSummaryComesAfterArrivingOnTheNextServer() {
        foundAndJoined(0);
        fight(5000);
        assertNull(session.tick(ON_DUEL, false, 7000));

        // The duel is over and the player is sent on: the proxy switches, then they arrive.
        session.moved(null, 8000);
        assertTrue(session.waiting());
        assertNull(session.duel());
        session.moved(AFTER, 10_000);
        assertNull(session.tick(AFTER, false, 10_000 + DuelSession.POST_AFTER_ARRIVAL_MILLIS - 100),
                "not straight away: the server's own welcome comes first");
        DuelStats.Summary summary = session.tick(AFTER, false, 10_000 + DuelSession.POST_AFTER_ARRIVAL_MILLIS);
        assertNotNull(summary);
        assertFalse(session.waiting(), "said once");

        assertEquals(OPPONENT, summary.opponentId());
        assertEquals(6, summary.you().swings(), "counted on the duel server, under the id it gave the player");
        assertEquals(3, summary.you().hits());
        assertEquals(1, summary.you().crits());
        assertEquals(1, summary.you().sweeps());
        assertEquals(2, summary.them().swings());
        assertEquals(1, summary.them().hits());
        assertNull(session.tick(AFTER, false, 30_000));
    }

    @Test
    void aDeathEndsItAndTheSummaryStillWaitsForTheMove() {
        foundAndJoined(0);
        fight(5000);
        assertNull(session.tick(ON_DUEL, true, 7000), "lost, but still on the duel server");
        assertTrue(session.waiting());
        assertNull(session.tick(ON_DUEL, true, 9000));
        session.moved(null, 10_000);
        session.moved(AFTER, 11_000);
        DuelStats.Summary summary = session.tick(AFTER, false, 11_000 + DuelSession.POST_AFTER_ARRIVAL_MILLIS);
        assertNotNull(summary);
        assertEquals(DuelStats.Result.LOST, summary.result());
    }

    @Test
    void withNoMoveItIsSaidWhereTheDuelWas() {
        foundAndJoined(0);
        fight(5000);
        long quiet = 6800 + DuelStats.IDLE_END_MILLIS + 1;
        assertNull(session.tick(ON_DUEL, false, quiet));
        assertTrue(session.waiting());
        assertNull(session.tick(ON_DUEL, false, quiet + DuelSession.WAIT_FOR_MOVE_MILLIS - 1));
        assertNotNull(session.tick(ON_DUEL, false, quiet + DuelSession.WAIT_FOR_MOVE_MILLIS));
    }

    @Test
    void swingsOnTheLobbyBeforeTheMoveDoNotCount() {
        session.arm(ON_LOBBY, 0);
        session.duel().swing(ON_LOBBY);
        session.duel().swing(ON_LOBBY);
        session.moved(null, 100);
        session.moved(ON_DUEL, 1000);
        session.duel().swing(ON_DUEL);
        session.duel().hit(ON_DUEL, OPPONENT, 2000);
        session.duel().hit(ON_LOBBY, OPPONENT, 2100);
        assertEquals(1, session.duel().summary().you().swings());
        assertEquals(1, session.duel().summary().you().hits(), "the old id is somebody else now");
    }

    @Test
    void aMatchNeverFoughtIsNeverSaid() {
        session.arm(ON_LOBBY, 0);
        session.moved(null, 100);
        session.moved(ON_DUEL, 1000);
        session.moved(null, 60_000);
        session.moved(AFTER, 61_000);
        assertFalse(session.waiting());
        assertNull(session.tick(AFTER, false, 70_000));
        assertNotNull(session.duel(), "still waiting for its fight");
        assertNull(session.tick(AFTER, false, DuelStats.ARMED_TIMEOUT_MILLIS + 1));
        assertNull(session.duel(), "and forgotten in the end");
    }

    @Test
    void theNextMatchSaysThePreviousOneFirst() {
        foundAndJoined(0);
        fight(5000);
        session.tick(ON_DUEL, true, 7000);
        assertTrue(session.waiting());
        DuelStats.Summary previous = session.arm(ON_DUEL, 7500);
        assertNotNull(previous, "queued again before being moved: said at once");
        assertFalse(session.waiting());
        assertNotNull(session.duel());
        assertFalse(session.duel().fought());
    }

    @Test
    void forgettingDropsEverything() {
        foundAndJoined(0);
        fight(5000);
        session.forget();
        assertNull(session.duel());
        assertFalse(session.waiting());
    }
}
