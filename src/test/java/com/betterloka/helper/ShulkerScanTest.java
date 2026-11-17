package com.betterloka.helper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Shulker Scanner's cut-offs, and what counts as a heal. */
class ShulkerScanTest {
    @Test
    void overFifteenIsGreenUpToFifteenYellowNoneRed() {
        assertEquals(ShulkerScan.Level.STOCKED, ShulkerScan.level(16));
        assertEquals(ShulkerScan.Level.STOCKED, ShulkerScan.level(27));
        assertEquals(ShulkerScan.Level.LOW, ShulkerScan.level(15), "fifteen is not more than fifteen");
        assertEquals(ShulkerScan.Level.LOW, ShulkerScan.level(1));
        assertEquals(ShulkerScan.Level.NONE, ShulkerScan.level(0));
    }

    @Test
    void onlySplashInstantHealthTwoOrMoreCounts() {
        int heals = ShulkerScan.heals(List.of(
                new ShulkerScan.Stack(true, 2, 1),
                new ShulkerScan.Stack(true, 2, 1),
                new ShulkerScan.Stack(true, 3, 4),   // stronger counts, and a stack counts every one in it
                new ShulkerScan.Stack(true, 1, 1),   // Instant Health I
                new ShulkerScan.Stack(false, 2, 1),  // drinkable
                new ShulkerScan.Stack(false, 0, 64)  // anything else
        ));
        assertEquals(6, heals);
        assertEquals(0, ShulkerScan.heals(List.of()), "an empty shulker");
    }
}
