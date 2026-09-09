package com.betterloka.towns;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the fallen-town list is ordered, and why it was wrong.
 *
 * <p>{@code newestFirst} reversed the list rather than sorting it, so it showed whatever order the
 * events had been appended in — and the first sweep appends every standing fallen town at once, in
 * the alphabetical order {@link FallenTowns#detect} hands them over. "Newest" was really "last
 * alphabetically", which is how a town that fell two days ago sat above one that fell today.
 */
class TownLogOrderTest {
    private static TownLogEvent fell(String name, long at) {
        return new TownLogEvent(at, TownLogEvent.Kind.TOWN_FELL, "id-" + name, name,
                "north", null, null, 0, 0, 0, null);
    }

    private static TownLogEvent fellHolding(String name, long at, String num) {
        return new TownLogEvent(at, TownLogEvent.Kind.TOWN_FELL, "id-" + name, name,
                "north", num, "Region", 1, 2, 3, null);
    }

    @Test
    void theNewestComesFirstWhateverOrderTheyWereAppendedIn() {
        // Appended alphabetically with equal-looking timestamps, as a first sweep would.
        List<TownLogEvent> ordered = TownLogger.newestFirst(List.of(
                fell("Aqronso's Town", 300),
                fell("New Verdanthia", 100),
                fell("Drovath", 500)));

        assertEquals("Drovath", ordered.get(0).townName(), "the most recent fall is the newest");
        assertEquals("Aqronso's Town", ordered.get(1).townName());
        assertEquals("New Verdanthia", ordered.get(2).townName());
    }

    @Test
    void reversingAloneWouldHaveGotItWrong() {
        List<TownLogEvent> byName = List.of(
                fell("Aqronso's Town", 300), fell("Drovath", 500), fell("New Verdanthia", 100));

        // What the old code did: the last one appended, whatever its date.
        assertEquals("New Verdanthia", byName.get(byName.size() - 1).townName());
        // What it does now.
        assertEquals("Drovath", TownLogger.newestFirst(byName).get(0).townName());
    }

    @Test
    void anEmptyLogIsHarmless() {
        assertTrue(TownLogger.newestFirst(List.of()).isEmpty());
    }

    /**
     * A town seen to appear in the deleted listing names no territory: the fall is the event, and
     * the land it leaves is a separate question with its own list.
     */
    @Test
    void aTownSeenFallingCarriesNoTerritory() {
        assertFalse(fell("Drovath", 500).hasTerritory());
        assertTrue(fellHolding("New Verdanthia", 100, "124").hasTerritory());
    }

    @Test
    void aTownDeletedEventIsAFall() {
        TownLogEvent event = TownLogEvent.townDeleted("id", "Drovath", "north");

        assertEquals(TownLogEvent.Kind.TOWN_FELL, event.kind());
        assertTrue(event.kind().isTownGone());
        assertEquals("Drovath", event.townName());
        assertEquals("Kalros", event.continent());
        assertFalse(event.hasTerritory());
    }

    /** Two towns falling in the same poll must not collapse into one another in the log. */
    @Test
    void eachFallenTownKeepsItsOwnIdentity() {
        assertFalse(TownLogEvent.townDeleted("a", "Drovath", "north").identity()
                .equals(TownLogEvent.townDeleted("b", "Kaytopia", "north").identity()));
    }
}
