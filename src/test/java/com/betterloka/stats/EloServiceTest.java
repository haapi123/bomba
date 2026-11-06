package com.betterloka.stats;

import com.betterloka.gui.EloColors;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EloServiceTest {
    /** The site's file, cut down: made-up players, the fields that matter and one that does not. */
    private static final String BOARD = """
            {"version": "1", "world": "conquest", "players": [
              {"id": "00000000-0000-0000-0000-000000000001", "name": "Alpha", "aliases": ["OldAlpha", "Shared"],
               "elo": 2067.9, "peak_elo": 2236.5, "games": 243, "last_active": "2026-11-03", "town": "Tyralnia",
               "rank": 81, "delta10": 99.8},
              {"id": "00000000-0000-0000-0000-000000000002", "name": "Beta", "aliases": ["Shared"],
               "elo": 701.0, "peak_elo": 750, "games": 3, "last_active": "2025-01-01", "town": null, "rank": 9000},
              {"id": "00000000-0000-0000-0000-000000000003", "name": "Unrated", "aliases": [], "elo": 750,
               "peak_elo": 750, "games": 0, "rank": 0},
              {"id": "00000000-0000-0000-0000-000000000004", "name": "Hidden", "aliases": [], "elo": 1200,
               "peak_elo": 1200, "games": 10, "rank": 500}
            ]}
            """;

    @Test
    void playersAreFoundByAccountByNameAndByAFormerName() {
        String hidden = EloService.h32("00000000-0000-0000-0000-000000000004");
        EloService service = withBoard(Set.of(hidden));

        EloService.Entry alpha = service.byUuid(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertEquals("Alpha", alpha.name());
        assertEquals(2068, alpha.shown());
        assertEquals(81, alpha.rank());
        assertEquals("Alpha", service.byName("alpha").name(), "case does not matter");
        assertEquals("Alpha", service.byName("OldAlpha").name(), "a former name finds today's player");
        assertEquals("Alpha", service.byName("Shared").name(), "a name two used goes to the busier one");
        assertNull(service.byName("Unrated"), "no games, no rating");
        assertNull(service.byName("Hidden"), "players the site hides stay hidden");
    }

    @Test
    void theHideHashIsTheSitesOwn() {
        // Worked by hand from the site's h32: FNV-1a, 32 bits, over the UUID's text.
        assertEquals("0ddc2193", EloService.h32("033b87b3-8142-450e-aa0e-0e866ca86133"));
    }

    @Test
    void ratingsAreColouredByBand() {
        assertEquals(EloColors.color(750), EloColors.color(700), "the starting band is one colour");
        assertNotEquals(EloColors.color(750), EloColors.color(2068));
        assertEquals(0xFFD24A, EloColors.color(3492.3), "the very top is gold");
        assertEquals(EloColors.color(649.4), EloColors.color(600), "matched on the rounded rating");
    }

    private static EloService withBoard(Set<String> hidden) {
        EloService service = new EloService(null, null, Runnable::run);
        service.useForTest(EloService.parse(BOARD, "1", 0, hidden));
        return service;
    }
}
