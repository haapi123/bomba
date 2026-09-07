package com.betterloka.async;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Typing "Diamond Sword" should cost one lookup, not one per letter. */
class DebounceTest {
    private static final long DELAY = 250;
    private static final long DEDUPE = 2000;

    @Test
    void holdsBackUntilTypingStops() {
        Debounce debounce = new Debounce(DELAY, DEDUPE);
        long now = 1000;

        // Thirteen keystrokes, one every 40 ms, as typing goes.
        String typed = "Diamond Sword";
        int lookups = 0;
        for (int i = 1; i <= typed.length(); i++) {
            now += 40;
            debounce.offer(typed.substring(0, i), now);
            if (debounce.take(now) != null) {
                lookups++;
            }
        }
        assertEquals(0, lookups, "nothing should go out while the field is still changing");

        now += DELAY;
        assertEquals("Diamond Sword", debounce.take(now));
    }

    @Test
    void deliversOnlyOncePerPause() {
        Debounce debounce = new Debounce(DELAY, DEDUPE);
        debounce.offer("iron", 0);
        assertEquals("iron", debounce.take(DELAY));
        assertNull(debounce.take(DELAY + 1), "the same pause must not fire twice");
    }

    /**
     * Asking the same thing twice running is the waste worth stopping: it happens when the debounce
     * fires and the player then presses Enter on the text it just looked up.
     */
    @Test
    void dropsTheSameQueryAskedTwiceRunning() {
        Debounce debounce = new Debounce(DELAY, DEDUPE);
        debounce.offer("gold", 0);
        assertEquals("gold", debounce.take(DELAY));

        debounce.offer("gold", DELAY + 10);
        assertNull(debounce.take(DELAY * 2 + 10), "the same query straight after is not a new one");
    }

    /**
     * Going away and coming back does have to refetch, though — the answer on screen is the other
     * query's by then, so declining to look again would leave the wrong list showing.
     */
    @Test
    void refetchesAQueryReturnedToAfterAnother() {
        Debounce debounce = new Debounce(DELAY, DEDUPE);
        debounce.offer("gold", 0);
        assertEquals("gold", debounce.take(DELAY));

        debounce.offer("silver", DELAY + 10);
        assertEquals("silver", debounce.take(DELAY * 2 + 10));

        debounce.offer("gold", DELAY * 2 + 20);
        assertEquals("gold", debounce.take(DELAY * 3 + 20),
                "the view now shows silver's answer, so gold has to be fetched again");
    }

    @Test
    void allowsTheSameQueryOnceTheWindowHasPassed() {
        Debounce debounce = new Debounce(DELAY, DEDUPE);
        debounce.offer("gold", 0);
        assertEquals("gold", debounce.take(DELAY));

        long later = DELAY + DEDUPE + 1;
        debounce.offer("gold", later);
        assertEquals("gold", debounce.take(later + DELAY), "a deliberate retry later is a new one");
    }

    @Test
    void clearingDropsWhatWasWaiting() {
        Debounce debounce = new Debounce(DELAY, DEDUPE);
        debounce.offer("diamond", 0);
        debounce.clear();
        assertNull(debounce.take(DELAY * 4));
    }
}
