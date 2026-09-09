package com.betterloka.grind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cycle rule the shulker timer turns on.
 *
 * <p>Auto-start used to call {@code start()} on every kill, so a good run of shulkers kept pushing
 * the end time forward and the countdown never reached zero for anybody grinding properly.
 */
class GrindTimerTest {
    @Test
    void theShulkerTimerRunsForTwentyMinutes() {
        assertEquals(20 * 60 * 1000L, GrindTimers.SHULKER_MILLIS);
        assertEquals("20:00", new GrindTimer(GrindTimers.SHULKER_MILLIS).durationText());
    }

    @Test
    void aFreshTimerIsIdle() {
        assertEquals(GrindTimer.Cycle.IDLE, new GrindTimer(60_000).cycle());
    }

    @Test
    void theFirstKillBeginsACycle() {
        GrindTimer timer = new GrindTimer(60_000);

        assertTrue(timer.startIfIdle(), "the first kill starts the countdown");
        assertEquals(GrindTimer.Cycle.RUNNING, timer.cycle());
    }

    /** The fault this replaces: every kill pushed the end time forward. */
    @Test
    void furtherKillsDuringACycleDoNotRestartIt() throws Exception {
        GrindTimer timer = new GrindTimer(60_000);
        timer.startIfIdle();
        long afterFirst = timer.remainingMillis();

        Thread.sleep(30);
        assertFalse(timer.startIfIdle(), "a kill mid-cycle must not begin a new one");
        assertTrue(timer.remainingMillis() < afterFirst,
                "the countdown must have kept counting down, not been reset");
        assertEquals(GrindTimer.Cycle.RUNNING, timer.cycle());
    }

    @Test
    void aKillAfterTheCountdownRunsOutStartsAFreshCycle() throws Exception {
        GrindTimer timer = new GrindTimer(1000);
        timer.setDurationMillis(1000);
        timer.startIfIdle();

        Thread.sleep(1100);
        assertEquals(GrindTimer.Cycle.IDLE, timer.cycle(), "a timer that has run out is idle again");
        assertTrue(timer.finished());

        assertTrue(timer.startIfIdle(), "the next kill begins a new cycle");
        assertEquals(GrindTimer.Cycle.RUNNING, timer.cycle());
    }

    @Test
    void aStoppedTimerIsIdleAndCanStartAgain() {
        GrindTimer timer = new GrindTimer(60_000);
        timer.startIfIdle();
        timer.stop();

        assertEquals(GrindTimer.Cycle.IDLE, timer.cycle());
        assertTrue(timer.startIfIdle());
    }

    /** The keybind is a deliberate press, so it still restarts a running countdown. */
    @Test
    void startingByHandStillRestartsARunningTimer() throws Exception {
        GrindTimer timer = new GrindTimer(60_000);
        timer.start();
        Thread.sleep(30);
        long beforeRestart = timer.remainingMillis();

        timer.start();

        assertTrue(timer.remainingMillis() > beforeRestart, "a keybind press restarts the count");
    }
}
