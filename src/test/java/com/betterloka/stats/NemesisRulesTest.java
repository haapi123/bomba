package com.betterloka.stats;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Nemesis rule: the conquest month it is reckoned over, and how close a chase has to be. */
class NemesisRulesTest {
    @Test
    void theConquestMonthRunsFromTheFirstToTheLast() {
        assertEquals("2026-09", NemesisIndex.monthKey(LocalDate.of(2026, 9, 1)));
        assertEquals("2026-09", NemesisIndex.monthKey(LocalDate.of(2026, 9, 30)));
        assertEquals("2026-10", NemesisIndex.monthKey(LocalDate.of(2026, 10, 1)),
                "the first of the month starts a new reckoning");
    }

    @Test
    void monthKeysSortAsDatesDo() {
        assertTrue(NemesisIndex.monthKey(LocalDate.of(2026, 9, 30))
                .compareTo(NemesisIndex.monthKey(LocalDate.of(2026, 10, 1))) < 0);
    }

    @Test
    void onlyChasesWithinTwoKillsAreWorthShowing() {
        assertEquals(2, NemesisIndex.MAX_KILLS_BEHIND);
    }

    @Test
    void anEmptyResultIsEmptyRatherThanNull() {
        assertTrue(NemesisIndex.Result.EMPTY.nemesisOf().isEmpty());
        assertTrue(NemesisIndex.Result.EMPTY.couldBecome().isEmpty());
        assertEquals(0, NemesisIndex.Result.EMPTY.fightsRead());
    }
}
