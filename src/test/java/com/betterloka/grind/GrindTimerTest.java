package com.betterloka.grind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grinding timers, which only ever start from the player's own click or key: nothing in the game
 * starts one by itself.
 */
class GrindTimerTest {
    @Test
    void theShulkerTimerRunsForTwentyMinutes() {
        assertEquals(20 * 60 * 1000L, GrindTimers.SHULKER_MILLIS);
        assertEquals("20:00", new GrindTimer(GrindTimers.SHULKER_MILLIS).durationText());
    }

    @Test
    void aFreshTimerIsIdle() {
        GrindTimer timer = new GrindTimer(60_000);
        assertEquals(GrindTimer.Cycle.IDLE, timer.cycle());
        assertFalse(timer.running());
        assertFalse(timer.finished());
    }

    @Test
    void startingByHandBeginsACycle() {
        GrindTimer timer = new GrindTimer(60_000);
        timer.start();
        assertEquals(GrindTimer.Cycle.RUNNING, timer.cycle());
        assertTrue(timer.running());
    }

    @Test
    void aTimerThatRunsOutIsFinishedUntilStartedAgain() throws Exception {
        GrindTimer timer = new GrindTimer(1000);
        timer.start();
        Thread.sleep(1100);
        assertEquals(GrindTimer.Cycle.IDLE, timer.cycle(), "a timer that has run out is idle again");
        assertTrue(timer.finished());

        timer.start();
        assertEquals(GrindTimer.Cycle.RUNNING, timer.cycle());
        assertFalse(timer.finished());
    }

    @Test
    void aStoppedTimerIsIdle() {
        GrindTimer timer = new GrindTimer(60_000);
        timer.start();
        timer.stop();
        assertEquals(GrindTimer.Cycle.IDLE, timer.cycle());
        assertFalse(timer.finished());
    }

    /** The keybind is a deliberate press, so it restarts a running countdown. */
    @Test
    void startingByHandRestartsARunningTimer() throws Exception {
        GrindTimer timer = new GrindTimer(60_000);
        timer.start();
        Thread.sleep(30);
        long beforeRestart = timer.remainingMillis();
        timer.start();

        assertTrue(timer.remainingMillis() > beforeRestart, "a keybind press restarts the count");
    }
}
