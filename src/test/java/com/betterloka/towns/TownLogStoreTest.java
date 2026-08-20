package com.betterloka.towns;

import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log is the module's memory: the baseline it diffs against, the towns it has already reported,
 * and the alliance history it can only build by watching. All of it is written as records, which the
 * serialiser has to be able to read back — a silent failure here would look like a logger that
 * forgets everything each time the game restarts.
 */
class TownLogStoreTest {

    @Test
    void survivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("town-log.json");
        TownLogStore store = new TownLogStore(file);
        store.load();

        Territory held = territory("north", "74", "icetaiga", "dead-town");
        store.record(Map.of(held.key(), held),
                List.of(TownLogEvent.of(TownLogEvent.Kind.TOWN_FELL, held, "dead-town", "Brickstone", null)),
                Map.of("dead-town", LokaTown.deleted("dead-town", "Brickstone", "north")),
                1_700_000_000_000L);

        TownLogStore reopened = new TownLogStore(file);
        reopened.load();

        assertEquals(1, reopened.events().size());
        TownLogEvent event = reopened.events().get(0);
        assertEquals(TownLogEvent.Kind.TOWN_FELL, event.kind());
        assertEquals("Brickstone", event.townName());
        assertEquals("Kalros", event.continent());
        assertEquals("74", event.num());
        assertEquals("2571, 114, 3196", event.coordinates());

        Territory restored = reopened.territories().get("north/74");
        assertEquals("dead-town", restored.townId(), "the baseline has to survive, or every restart re-diffs");
        assertEquals("Brickstone", reopened.deletedTowns().get("dead-town").name());
        assertTrue(reopened.deletedTowns().get("dead-town").deleted());
        assertEquals(1_700_000_000_000L, reopened.deletedTownsLoadedAt());
    }

    @Test
    void reportsAFallenTownOnceHoweverManySweepsSeeIt(@TempDir Path dir) {
        TownLogStore store = new TownLogStore(dir.resolve("town-log.json"));
        Territory held = territory("west", "191", "forest", "dead-town");
        Map<String, Territory> snapshot = Map.of(held.key(), held);
        TownLogEvent fell = TownLogEvent.of(TownLogEvent.Kind.TOWN_FELL, held, "dead-town", "Nebula", null);

        store.record(snapshot, List.of(fell), Map.of(), 0);
        store.record(snapshot, List.of(fell), Map.of(), 0);
        store.record(snapshot, List.of(fell), Map.of(), 0);

        assertEquals(1, store.events().size(),
                "a dangling claim is a standing fact, so it must not pile up one entry per sweep");
    }

    @Test
    void keepsRecordingHandoversEvenWhenTheyRepeat(@TempDir Path dir) {
        TownLogStore store = new TownLogStore(dir.resolve("town-log.json"));
        Territory held = territory("south", "16", "jungle", "town-b");
        TownLogEvent captured =
                TownLogEvent.of(TownLogEvent.Kind.CAPTURED, held, "town-a", "Costello", "New Dyshim");

        store.record(Map.of(held.key(), held), List.of(captured), Map.of(), 0);
        store.record(Map.of(held.key(), held), List.of(captured), Map.of(), 0);

        assertEquals(2, store.events().size(), "the same territory really can change hands twice");
    }

    @Test
    void countsAlliesByDaysTogetherRatherThanBySweeps(@TempDir Path dir) {
        TownLogStore store = new TownLogStore(dir.resolve("town-log.json"));
        LokaAlliance alliance = new LokaAlliance("a1", "The Covenant", 100, 13, "town-a",
                List.of("town-a", "town-b", "town-c"));
        Map<String, String> names = Map.of("town-a", "Hilo", "town-b", "Newgen", "town-c", "Grimwall");

        store.recordAlliances(List.of(alliance), names);
        store.recordAlliances(List.of(alliance), names);

        List<TownLogStore.TownPartner> partners = store.partnersOf("town-a");
        assertEquals(2, partners.size(), "both other members are partners, and the town is not its own");
        for (TownLogStore.TownPartner partner : partners) {
            assertEquals(1, partner.days(), "two sweeps on the same day are one day together");
            assertFalse(partner.townId().equals("town-a"));
        }
        assertEquals(java.util.Set.of("Newgen", "Grimwall"),
                partners.stream().map(TownLogStore.TownPartner::name).collect(java.util.stream.Collectors.toSet()),
                "partners are named, not left as ids");
    }

    private static Territory territory(String world, String num, String area, String townId) {
        return new Territory("id-" + num, world, num, area, townId, 2571, 114, 3196, 0);
    }
}
