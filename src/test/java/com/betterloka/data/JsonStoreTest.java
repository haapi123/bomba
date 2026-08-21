package com.betterloka.data;

import com.betterloka.api.model.LokaTown;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The disk caches are what keep the mod off a player's connection: the town roster is a megabyte and
 * a fight page never changes once the fight ends. A cache that silently fails to read back would look
 * exactly like the mod being slow again, so the round trip and the expiry are both pinned down.
 */
class JsonStoreTest {
    private static final Type TYPE =
            JsonStore.envelopeOf(new TypeToken<List<LokaTown.Saved>>() {
            }.getType());

    @Test
    void readsBackWhatItWrote(@TempDir Path dir) {
        JsonStore<List<LokaTown.Saved>> store =
                new JsonStore<>(dir.resolve("towns.json"), TYPE, 60_000);
        store.write(List.of(new LokaTown.Saved("id", "Hilo", "north", "setup an inhibitor", 25, 100,
                true, 23, 10, "1", "owner", List.of("a", "b"))));

        List<LokaTown.Saved> read = new JsonStore<List<LokaTown.Saved>>(
                dir.resolve("towns.json"), TYPE, 60_000).read();
        assertEquals(1, read.size());
        LokaTown.Saved town = read.get(0);
        assertEquals("Hilo", town.name());
        assertEquals(10, town.vulnerabilityWindow());
        assertEquals(List.of("a", "b"), town.subOwnerIds());
        assertEquals(23, town.memberCount());
    }

    @Test
    void refusesAnExpiredCopy(@TempDir Path dir) {
        Path file = dir.resolve("towns.json");
        new JsonStore<List<LokaTown.Saved>>(file, TYPE, 60_000)
                .write(List.of(new LokaTown.Saved("id", "Hilo", "north", null, 25, 100, true, 1, 9,
                        "1", null, List.of())));

        // Same file, zero tolerance for age: what was just written is already too old.
        assertNull(new JsonStore<List<LokaTown.Saved>>(file, TYPE, -1).read(),
                "a stale cache must be refetched, not served");
    }

    @Test
    void treatsAMissingOrBrokenFileAsNoCache(@TempDir Path dir) throws Exception {
        assertNull(new JsonStore<List<LokaTown.Saved>>(dir.resolve("absent.json"), TYPE, 60_000).read());

        Path broken = dir.resolve("broken.json");
        java.nio.file.Files.writeString(broken, "{ this is not json");
        assertNull(new JsonStore<List<LokaTown.Saved>>(broken, TYPE, 60_000).read(),
                "a corrupt cache is a cache miss, not a crash");
    }

    @Test
    void survivesARoundTripThroughTheTownModel(@TempDir Path dir) {
        LokaTown.Saved saved = new LokaTown.Saved("id", "Newgen", "west", "slogan", 24, 175,
                false, 40, 19, "77", "owner-id", List.of("sub"));
        LokaTown restored = LokaTown.fromSaved(saved);

        assertEquals("Newgen", restored.name());
        assertEquals("Ascalon", restored.continentName());
        assertEquals(19, restored.vulnerabilityWindow());
        assertEquals(List.of("sub"), restored.subOwnerIds());
        assertEquals(saved, restored.toSaved(), "the slim form has to survive both directions");
    }
}
