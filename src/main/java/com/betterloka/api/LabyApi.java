package com.betterloka.api;

import com.betterloka.api.model.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Every name an account has gone by, from Laby.net.
 *
 * <p>Mojang withdrew name history in 2022 — {@code api.mojang.com/user/profiles/{uuid}/names} is a
 * 404 now — and the services that proxy Mojang went with it: Ashcon returns only the current name,
 * PlayerDB an empty list. Laby.net kept crawling and has its own record, which turns out to be a
 * better one than the game's: Loka's {@code /find} lists three names for a player this has twelve
 * for, with dates.
 *
 * <p>Their versioned API sits behind a bot challenge and answers 428; this older path does not, so
 * it is the one used. Nothing here needs a key or an account, and a UUID nobody knows comes back as
 * an empty list rather than an error.
 */
public final class LabyApi {
    private static final String BASE_URL = "https://laby.net/api";

    /**
     * One name, and when it was taken.
     *
     * @param changedAt when the account took this name; absent for the first name it ever had
     * @param accurate  whether the date is Mojang's own or Laby's estimate. Mojang's record stops in
     *                  2022, so everything since is dated by when Laby noticed rather than when it
     *                  happened, and saying so is the difference between a date and a guess.
     */
    public record NameEntry(String name, Instant changedAt, boolean accurate) {
    }

    private final HttpTransport transport;

    public LabyApi(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * The account's names, oldest first.
     *
     * @return an empty list when the account is unknown or the service is unreachable — a name
     *         history nobody can produce is not an error worth failing a profile over
     */
    public List<NameEntry> nameHistory(UUID uuid) throws ApiException {
        if (uuid == null) {
            return List.of();
        }
        String body = transport.get(BASE_URL + "/user/" + undashed(uuid) + "/get-names", true);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new ApiException("Malformed name history for " + uuid, e);
        }
        if (!parsed.isJsonArray()) {
            return List.of();
        }

        List<NameEntry> names = new ArrayList<>();
        for (JsonElement element : parsed.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String name = Json.string(entry, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            names.add(new NameEntry(name, instant(Json.string(entry, "changed_at")),
                    Json.bool(entry, "accurate", false)));
        }
        return List.copyOf(names);
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }
}
