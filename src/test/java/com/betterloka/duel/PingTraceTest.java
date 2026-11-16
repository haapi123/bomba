package com.betterloka.duel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The opponent's ping over a duel, from what the player list says and when. */
class PingTraceTest {
    @Test
    void nothingReadIsNothingSaid() {
        assertNull(new PingTrace().summary(0, 1000));
        PingTrace zeros = new PingTrace();
        zeros.sample(0, 0);
        zeros.sample(10, -1);
        assertTrue(zeros.isEmpty(), "0 is a server that has not measured yet, not 0 ms");
    }

    @Test
    void aSteadyPingIsItsOwnAverageWithNoSpikes() {
        PingTrace trace = new PingTrace();
        for (long t = 0; t < 60_000; t += 50) {
            trace.sample(t, 45);
        }
        PingTrace.Summary summary = trace.summary(10_000, 50_000);
        assertEquals(1, summary.readings(), "the same value again is not a new reading");
        assertEquals(45, summary.average());
        assertEquals(45, summary.max());
        assertEquals(0, summary.spikeCount());
        assertEquals(-1, summary.everySeconds());
    }

    @Test
    void theAverageWeighsEachValueByHowLongItStood() {
        PingTrace trace = new PingTrace();
        trace.sample(0, 40);       // standing when the duel starts at 10 s
        trace.sample(40_000, 100); // the last 20 s of a 60 s duel
        PingTrace.Summary summary = trace.summary(10_000, 60_000);
        assertEquals(2, summary.readings());
        assertEquals((40 * 30 + 100 * 20) / 50, summary.average());
        assertEquals(100, summary.max());
    }

    @Test
    void spikesAreChangesOfTwentyOrMoreEitherWay() {
        PingTrace trace = new PingTrace();
        trace.sample(0, 50);
        trace.sample(30_000, 65);   // +15: not a spike
        trace.sample(60_000, 110);  // +45
        trace.sample(90_000, 80);   // -30
        trace.sample(120_000, 100); // +20: exactly the line
        PingTrace.Summary summary = trace.summary(0, 130_000);
        assertEquals(5, summary.readings());
        assertEquals(3, summary.spikeCount());
        assertEquals(2, summary.up());
        assertEquals(1, summary.down());
        assertEquals(45, summary.biggest());
        assertEquals(110, summary.max());
        assertEquals(60_000, summary.spikes().get(0).afterMillis());
        assertEquals(-30, summary.spikes().get(1).change());
        assertEquals(30.0, summary.everySeconds(), 1e-9, "60 s from the first spike to the last, two gaps");
    }

    @Test
    void theBiggestSpikeKeepsItsDirection() {
        PingTrace trace = new PingTrace();
        trace.sample(0, 200);
        trace.sample(30_000, 120);
        trace.sample(60_000, 150);
        PingTrace.Summary summary = trace.summary(0, 70_000);
        assertEquals(-80, summary.biggest());
        assertEquals(1, summary.up());
        assertEquals(1, summary.down());
    }

    @Test
    void onlyTheDuelsOwnWindowCounts() {
        PingTrace trace = new PingTrace();
        trace.sample(0, 300);       // on the lobby, long before
        trace.sample(5_000, 60);    // standing when the duel starts
        trace.sample(20_000, 90);   // during it: +30
        trace.sample(40_000, 400);  // after it ended
        PingTrace.Summary summary = trace.summary(10_000, 30_000);
        assertEquals(2, summary.readings());
        assertEquals(90, summary.max(), "neither the 300 before nor the 400 after");
        assertEquals(1, summary.spikeCount());
        assertEquals(10_000, summary.spikes().get(0).afterMillis());
        assertEquals(75, summary.average());
    }

    @Test
    void aFirstValueThatCameLateStandsInForTheStart() {
        PingTrace trace = new PingTrace();
        trace.sample(25_000, 70);
        PingTrace.Summary summary = trace.summary(10_000, 40_000);
        assertEquals(1, summary.readings());
        assertEquals(70, summary.average());
        assertEquals(0, summary.spikeCount());
    }
}
