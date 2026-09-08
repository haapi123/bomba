package com.betterloka.api;

import com.betterloka.api.model.BattleZone;
import com.betterloka.api.model.Json;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ObjectIds;
import com.betterloka.api.model.ScheduledFight;
import com.betterloka.api.model.Territory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URLEncoder;
import java.time.Instant;
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

    /** Every alliance. There are about ten, so this is one request. */
    public List<LokaAlliance> fetchAlliances() throws ApiException {
        List<LokaAlliance> alliances = new ArrayList<>();
        JsonObject json = getObject(BASE_URL + "/alliances?size=" + PAGE_SIZE + "&page=0");
        int pages = Json.integer(Json.object(json, "page"), "totalPages", 1);
        collectAlliances(json, alliances);
        for (int page = 1; page < pages; page++) {
            collectAlliances(getObject(BASE_URL + "/alliances?size=" + PAGE_SIZE + "&page=" + page), alliances);
        }
        return alliances;
    }

    private void collectAlliances(JsonObject json, List<LokaAlliance> into) {
        for (JsonElement element : embeddedArray(json, "alliances")) {
            if (element.isJsonObject()) {
                into.add(LokaAlliance.fromJson(element.getAsJsonObject()));
            }
        }
    }

    /**
     * Every account belonging to one identity.
     *
     * <p>Loka groups a person's accounts under a shared identity, which is what its own
     * {@code /find} reports as somebody's alts. Reading it here means the mod never has to send a
     * command as the player.
     */
    public List<LokaPlayer> findAccountsByIdentity(String identityId) throws ApiException {
        List<LokaPlayer> accounts = new ArrayList<>();
        if (identityId == null || identityId.isEmpty()) {
            return accounts;
        }
        JsonObject json = getObject(BASE_URL + "/players/search/findByIdentityId?identityId=" + encode(identityId));
        for (JsonElement element : embeddedArray(json, "players")) {
            if (element.isJsonObject()) {
                accounts.add(LokaPlayer.fromJson(element.getAsJsonObject()));
            }
        }
        return accounts;
    }

    /**
     * The player behind an identity ID.
     *
     * <p>An identity can own several accounts; the first is taken, which for a main account with
     * alts is the main. Town rosters are keyed by identity, so this is how a member becomes a name.
     *
     * @return the player, or {@code null} if the identity is unknown
     */
    public LokaPlayer findPlayerByIdentity(String identityId) throws ApiException {
        if (identityId == null || identityId.isEmpty()) {
            return null;
        }
        for (LokaPlayer player : findAccountsByIdentity(identityId)) {
            if (player.name() != null && !player.name().isBlank()) {
                return player;
            }
        }
        return null;
    }

    /**
     * When this player last put something on the market, from their live listings.
     *
     * <p>Loka publishes no last-login anywhere, so this is the closest thing to one that its API
     * will answer: a listing exists because somebody stood at a market stall and made it, and its
     * ObjectID says when. It is a lower bound — a player with nothing for sale gets {@code null},
     * which is not the same as "has not played".
     *
     * <p>Only current listings are read. The completed-order history would be a better signal, but
     * its search endpoint ignores {@code size} and {@code sort} and returns a player's whole career
     * — a third of a megabyte for one person — which is not something to do per town member.
     *
     * @param identityId the player's identity ID
     * @return the newest listing's creation time, or {@code null} if they have none
     */
    public Instant lastMarketListing(String identityId) throws ApiException {
        if (identityId == null || identityId.isEmpty()) {
            return null;
        }
        JsonObject json =
                getObject(BASE_URL + "/market_sales/search/findByOwnerId?id=" + encode(identityId));
        Instant newest = null;
        for (JsonElement element : embeddedArray(json, "market_sales")) {
            if (!element.isJsonObject()) {
                continue;
            }
            Instant listed = ObjectIds.timestamp(Json.string(element.getAsJsonObject(), "id"));
            if (listed != null && (newest == null || listed.isAfter(newest))) {
                newest = listed;
            }
        }
        return newest;
    }

    /**
     * The account name behind an identity ID.
     *
     * <p>Town owners and sub-owners are recorded by identity, and one identity can own several
     * accounts, so the first is taken as the person's name.
     *
     * @return the name, or {@code null} if the identity is unknown
     */
    public String findNameByIdentity(String identityId) throws ApiException {
        if (identityId == null || identityId.isEmpty()) {
            return null;
        }
        JsonObject json = getObject(BASE_URL + "/players/search/findByIdentityId?identityId=" + encode(identityId));
        for (JsonElement element : embeddedArray(json, "players")) {
            if (element.isJsonObject()) {
                String name = Json.string(element.getAsJsonObject(), "name");
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * The worlds Conquest is played on.
     *
     * <p>Five, not three. {@code lilboi} and {@code bigboi} were left out here on the reading that
     * they were event maps — they are Loka's own names for Rivina and Balak, the two continents its
     * public map serves alongside the other three, and between them they hold 31 claimed
     * territories. Leaving them out meant a town could fall holding ground on either and nothing
     * here would notice.
     *
     * <p>{@code ctw} really is a minigame: one territory, never claimed. It stays out.
     */
    public static final List<String> CONQUEST_WORLDS =
            List.of("north", "west", "south", "lilboi", "bigboi");

    /** Every territory on one continent, in a single request. */
    public List<Territory> fetchTerritories(String world) throws ApiException {
        List<Territory> territories = new ArrayList<>();
        JsonObject json = getObject(BASE_URL + "/territories/search/findByWorld?world=" + encode(world), true);
        for (JsonElement element : embeddedArray(json, "territories")) {
            if (element.isJsonObject()) {
                territories.add(Territory.fromJson(element.getAsJsonObject()));
            }
        }
        return territories;
    }

    /**
     * How many towns Loka has deleted, in one small request.
     *
     * <p>A town falling changes nothing in the territory list — its claims keep the same id — so this
     * count going up is the actual signal that one has. Reading it costs a kilobyte, against the
     * eight hundred a territory sweep costs, which is what makes watching every half minute sensible.
     */
    public int countDeletedTowns() throws ApiException {
        JsonObject json = getObject(BASE_URL + "/towns/search/findDeleted?size=1&page=0", true);
        return Json.integer(Json.object(json, "page"), "totalElements", -1);
    }

    /**
     * One page of towns that have been deleted.
     *
     * <p>A deleted town 404s on {@code findById}, so this listing is the only way to put a name to
     * the id a fallen town leaves behind on its territories.
     */
    public TownPage fetchDeletedTownPage(int page) throws ApiException {
        JsonObject json = getObject(BASE_URL + "/towns/search/findDeleted?size=" + PAGE_SIZE + "&page=" + page, true);
        List<LokaTown> towns = new ArrayList<>();
        for (JsonElement element : embeddedArray(json, "towns")) {
            if (element.isJsonObject()) {
                towns.add(LokaTown.fromJson(element.getAsJsonObject()));
            }
        }
        return new TownPage(towns, Json.integer(Json.object(json, "page"), "totalPages", 0));
    }

    /**
     * Every battle Loka currently has on its books — declared and waiting, or under way.
     *
     * <p>The same endpoint the in-fight check uses, read for its schedule rather than its rosters.
     */
    /**
     * Battles Loka has on record, most recent first, finished ones included.
     *
     * <p>Development only, for photographing the Fight Manager when nothing is declared: the rows
     * are real battles with real names and real turnouts, rather than numbers made up to fill a
     * screenshot.
     */
    public List<ScheduledFight> fetchRecentBattles() throws ApiException {
        List<ScheduledFight> fights = new ArrayList<>();
        for (JsonElement element : embeddedArray(getObject(BASE_URL + "/battlezones"),
                "battlezones")) {
            if (element.isJsonObject()) {
                fights.add(ScheduledFight.fromJson(element.getAsJsonObject()));
            }
        }
        return fights;
    }

    public List<ScheduledFight> fetchScheduledFights() throws ApiException {
        List<ScheduledFight> fights = new ArrayList<>();
        for (JsonElement element : embeddedArray(getObject(BASE_URL + "/battlezones/search/findBattles"),
                "battlezones")) {
            if (element.isJsonObject()) {
                fights.add(ScheduledFight.fromJson(element.getAsJsonObject()));
            }
        }
        return fights;
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

    /** One page of the battle archive, newest finished battle first. */
    public record BattlePage(List<BattleZone> battles, int totalPages, int totalElements) {
    }

    /**
     * A page of every battle Loka has on record.
     *
     * <p>Newest first, which is what makes the archive worth indexing: a refresh reads until it
     * meets a battle it already has rather than starting over. The page size is fixed by the server
     * at twenty however large a size is asked for — 8,445 battles is 423 pages — so this is
     * deliberately background work.
     */
    public BattlePage fetchBattlePage(int page) throws ApiException {
        JsonObject json = getObject(BASE_URL + "/battlezones?size=" + PAGE_SIZE
                + "&page=" + page + "&sort=timeEnded,desc", true);
        List<BattleZone> battles = new ArrayList<>();
        for (JsonElement element : embeddedArray(json, "battlezones")) {
            if (!element.isJsonObject()) {
                continue;
            }
            BattleZone battle = BattleZone.fromJson(element.getAsJsonObject());
            if (battle != null) {
                battles.add(battle);
            }
        }
        JsonObject pageInfo = Json.object(json, "page");
        return new BattlePage(List.copyOf(battles),
                Json.integer(pageInfo, "totalPages", 0),
                Json.integer(pageInfo, "totalElements", 0));
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
        return getObject(url, false);
    }

    private JsonObject getObject(String url, boolean background) throws ApiException {
        String body = transport.get(url, background);
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
