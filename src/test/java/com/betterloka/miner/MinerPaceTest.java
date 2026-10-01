package com.betterloka.miner;

import com.betterloka.config.BetterLokaConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gap the miner leaves between two clicks inside a container.
 *
 * <p>Worth pinning because the number is set against something outside the game: a round trip to
 * the server. The first version ran at one or two ticks, which is fine against a server on the same
 * machine and wrong against a real one, where a hundred milliseconds is already two or three ticks.
 */
class MinerPaceTest {
    /** A tick is fifty milliseconds, which is the unit the screen shows and a ping is quoted in. */
    private static final int MILLIS_PER_TICK = 50;

    @Test
    void theDefaultLeavesRoomForALatencyWellOverAHundredMilliseconds() {
        int defaultTicks = new BetterLokaConfig().minerPaceTicks();
        assertTrue(defaultTicks * MILLIS_PER_TICK >= 400,
                "the default pace is " + defaultTicks * MILLIS_PER_TICK
                        + " ms, which leaves no room over a 100 ms link");
    }

    @Test
    void thePaceIsHeldInsideItsRange() {
        BetterLokaConfig config = new BetterLokaConfig();
        config.setMinerPaceTicks(-5);
        assertEquals(BetterLokaConfig.OreKindLimits.MIN_PACE, config.minerPaceTicks());
        config.setMinerPaceTicks(10_000);
        assertEquals(BetterLokaConfig.OreKindLimits.MAX_PACE, config.minerPaceTicks());
        config.setMinerPaceTicks(6);
        assertEquals(6, config.minerPaceTicks());
    }

    /** Even the fastest setting must still be a real pause, not a free-for-all. */
    @Test
    void evenTheFastestSettingLeavesAGap() {
        assertTrue(BetterLokaConfig.OreKindLimits.MIN_PACE >= 2);
        assertTrue(BetterLokaConfig.OreKindLimits.MAX_PACE
                >= BetterLokaConfig.OreKindLimits.MIN_PACE * 5,
                "there should be at least a fivefold range to slow down into");
    }
}
