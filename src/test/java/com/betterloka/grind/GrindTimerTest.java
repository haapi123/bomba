package com.betterloka.grind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrindTimerTest {
    @Test
    void showsMinutesBelowAnHourAndHoursAboveIt() {
        assertEquals("17:00", new GrindTimer(GrindTimers.SHULKER_MILLIS).durationText());
        assertEquals("3:00:00", new GrindTimer(GrindTimers.GLOWSTONE_MILLIS).durationText());
    }

    @Test
    void aFreshTimerIsNeitherRunningNorFinished() {
        GrindTimer timer = new GrindTimer(60_000L);
        assertFalse(timer.running());
        assertFalse(timer.finished());
        assertEquals(0f, timer.progress());
    }

    @Test
    void startingMakesItRunAndStoppingClearsIt() {
        GrindTimer timer = new GrindTimer(60_000L);
        timer.start();
        assertTrue(timer.running());
        assertFalse(timer.finished());

        timer.stop();
        assertFalse(timer.running());
        assertFalse(timer.finished());
    }

    @Test
    void aTimerThatHasRunOutReadsAsFinishedRatherThanRunning() {
        GrindTimer timer = new GrindTimer(1000L);
        timer.setDurationMillis(-5);
        timer.start();
        // setDurationMillis clamps to a second, so this has to actually elapse.
        while (timer.remainingMillis() > 0) {
            Thread.onSpinWait();
        }
        assertFalse(timer.running());
        assertTrue(timer.finished());
        assertEquals("0:00", timer.remainingText());
    }

    @Test
    void changingTheLengthDoesNotDisturbARunningTimer() {
        GrindTimer timer = new GrindTimer(60_000L);
        timer.start();
        long before = timer.remainingMillis();
        timer.setDurationMillis(3 * 60 * 60 * 1000L);
        assertTrue(timer.remainingMillis() <= before);
        assertTrue(timer.running());
        // The new length only takes effect on the next start.
        timer.start();
        assertTrue(timer.remainingMillis() > 60_000L);
    }
}
