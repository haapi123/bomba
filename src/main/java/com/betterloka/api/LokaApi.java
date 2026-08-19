package com.betterloka.api;

import com.betterloka.api.model.BattleZone;
import com.betterloka.api.model.Json;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Client for Loka's own API at {@code https://api.lokamc.com}.
 *
 * <p>Career combat statistics come from {@link EldritchApi} instead — Loka exposes kills and deaths
 * only inside individual battle records with no way to query by player, so reaching career totals
 * here meant downloading the entire battle history. What this API remains the authority on is
 * identity (rank, account age), town rosters, and battles happening right now.
 */
public final class LokaApi {
    public static final String BASE_URL = "https://api.lokamc.com";

    /** The server clamps {@code size} to 20 however much is asked for. */
    public static final int PAGE_SIZE = 20;

    private static final String MOJANG_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";

    private final HttpTransport transport;

    public LokaApi(HttpTransport transport) {
        this.transport = transport;
    }

    public ExecutorService executor() {
        return transport.executor();
    }

    /**
     * Looks a player up by name. The Loka endpoint is case sensitive and 404s on a casing mismatch,
     * so a miss falls back to resolving the canonical name through Mojang and retrying by UUID.
     */
    public LokaPlayer findPlayerByName(String name) throws ApiException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ApiException("Empty player name", true);
        }
        try {
            return LokaPlayer.fromJson(getObject(BASE_URL + "/players/search/findByName?name=" + encode(trimmed)));
        } catch (ApiException e) {
            if (!e.notFound()) {
                throw e;
            }
        }

        UUID uuid = resolveMojangUuid(trimmed);
        if (uuid == null) {
            throw new ApiException("No player named " + trimmed, true);
        }
        return findPlayerByUuid(uuid);
    }

    public LokaPlayer findPlayerByUuid(UUID uuid) throws ApiException {
        return LokaPlayer.fromJson(getObject(BASE_URL + "/players/search/findByUuid?uuid=" + uuid));
    }

    /**
     * The town a player belongs to. The {@code town} field inlined on the player document is
     * unreliable (null even for members), so membership is resolved from the town side instead.
     *
     * @param identityId the player's identity ID — town membership is keyed by identity, not by the
     *                   per-account player ID.
     * @return the town, or {@code null} if the player is townless.
     */
    public LokaTown findTownByMember(String identityId) throws ApiException {
        if (identityId == null || identityId.isEmpty()) {
            return null;
        }
        return findTownOrNull(BASE_URL + "/towns/search/findByMember?id=" + encode(identityId));
    }

    public LokaTown findTownById(String townId) throws ApiException {
        if (townId == null || townId.isEmpty()) {
            return null;
        }
        return findTownOrNull(BASE_URL + "/towns/search/findById?id=" + encode(townId));
    }

    public LokaTown findTownByName(String townName) throws ApiException {
        if (townName == null || townName.isEmpty()) {
            return null;
        }
        return findTownOrNull(BASE_URL + "/towns/search/findByName?name=" + encode(townName));
    }

    private LokaTown findTownOrNull(String url) throws ApiException {
        try {
            JsonObject json = getObject(url);
            return json.has("name") ? LokaTown.fromJson(json) : null;
        } catch (ApiException e) {
            if (e.notFound()) {
                return null;
            }
            throw e;
        }
    }

    /** One page of towns. */
    public record TownPage(List<LokaTown> towns, int totalPages) {
    }

    public TownPage fetchTownPage(int page) throws ApiException {
        JsonObject json = getObject(BASE_URL + "/towns?size=" + PAGE_SIZE + "&page=" + page);
        List<LokaTown> towns = new ArrayList<>();
        for (JsonElement element : embeddedArray(json, "towns")) {
            if (element.isJsonObject()) {
                towns.add(LokaTown.fromJson(element.getAsJsonObject()));
            }
        }
        return new TownPage(towns, Json.integer(Json.object(json, "page"), "totalPages", 0));
    }

    /**
     * Battles happening right now. EldritchBot only publishes finished fights, so this is the only
     * way to tell that someone is in a fight at this moment.
     */
    public List<BattleZone> fetchActiveBattles() throws ApiException {
        List<BattleZone> battles = new ArrayList<>();
        for (JsonElement element : embeddedArray(getObject(BASE_URL + "/battlezones/search/findBattles"), "battlezones")) {
            if (!element.isJsonObject()) {
                continue;
            }
            BattleZone battle = BattleZone.fromJson(element.getAsJsonObject());
            if (battle != null) {
                battles.add(battle);
            }
        }
        return battles;
    }

    /** Spring Data REST wraps collections in {@code _embedded.<name>}. */
    private static Iterable<JsonElement> embeddedArray(JsonObject json, String name) {
        JsonObject embedded = Json.object(json, "_embedded");
        JsonElement array = embedded == null ? null : embedded.get(name);
        return array != null && array.isJsonArray() ? array.getAsJsonArray() : List.of();
    }

    /** @return the canonical UUID for a name of any casing, or {@code null} if no such account. */
    private UUID resolveMojangUuid(String name) {
        try {
            return toUuid(Json.string(getObject(MOJANG_PROFILE_URL + encode(name)), "id"));
        } catch (ApiException e) {
            return null;
        }
    }

    /** @return a UUID from Mojang's or EldritchBot's undashed form, or {@code null} if malformed. */
    public static UUID toUuid(String undashed) {
        if (undashed == null || undashed.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(undashed.substring(0, 8) + "-" + undashed.substring(8, 12) + "-"
                    + undashed.substring(12, 16) + "-" + undashed.substring(16, 20) + "-" + undashed.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private JsonObject getObject(String url) throws ApiException {
        String body = transport.get(url);
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new ApiException("Unexpected response shape from " + url, false);
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new ApiException("Malformed response from " + url, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
