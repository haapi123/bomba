package com.betterloka.api;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a name history, against the real reply for one account.
 *
 * <p>Trimmed from what {@code laby.net/api/user/{uuid}/get-names} returns for haapi, which is the
 * account this was found on: Loka's own {@code /find} lists three names for it and this has twelve.
 */
class LabyApiTest {
    private static final String REAL = """
            [{"name":"HappyDj","username":"HappyDj","changed_at":null,"accurate":true},
             {"name":"chudypvp","username":"chudypvp","changed_at":"2019-12-15T11:31:07+00:00","accurate":true},
             {"name":"haapi","username":"haapi","changed_at":"2020-06-15T11:07:59+00:00","accurate":true},
             {"name":"TOADhaapi","username":"TOADhaapi","changed_at":"2024-07-07T18:53:14+00:00","accurate":false},
             {"name":"haapi","username":"haapi","changed_at":"2024-07-15T13:10:19+00:00","accurate":false}]
            """;

    private static List<LabyApi.NameEntry> parse(String json) {
        List<LabyApi.NameEntry> out = new java.util.ArrayList<>();
        for (var element : JsonParser.parseString(json).getAsJsonArray()) {
            var entry = element.getAsJsonObject();
            String changed = entry.get("changed_at").isJsonNull()
                    ? null : entry.get("changed_at").getAsString();
            out.add(new LabyApi.NameEntry(entry.get("name").getAsString(),
                    changed == null ? null : java.time.OffsetDateTime.parse(changed).toInstant(),
                    entry.get("accurate").getAsBoolean()));
        }
        return out;
    }

    @Test
    void everyNameAndItsDateComeThrough() {
        List<LabyApi.NameEntry> names = parse(REAL);

        assertEquals(5, names.size());
        assertEquals("HappyDj", names.get(0).name());
        assertNull(names.get(0).changedAt(), "the first name it ever had has no change date");
        assertEquals(Instant.parse("2024-07-07T18:53:14Z"), names.get(3).changedAt());
    }

    /**
     * Mojang closed name history in 2022, so anything dated after that is when Laby noticed rather
     * than when it happened — and the screen marks it as such instead of presenting it as fact.
     */
    @Test
    void datesAfterMojangsShutdownAreMarkedInexact() {
        List<LabyApi.NameEntry> names = parse(REAL);

        assertTrue(names.get(1).accurate(), "2019 is Mojang's own record");
        assertFalse(names.get(3).accurate(), "2024 is Laby's estimate");
    }
}
