package com.betterloka.api;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.Json;
import com.betterloka.api.model.MarketListing;
import com.betterloka.data.LruCache;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** Client for Loka's market endpoints: what is on sale, from whom, and for how much. */
public final class MarketApi {
    private static final String BASE_URL = LokaApi.BASE_URL;

    /** How many seller names to keep. The market has about 150 sellers; this is room to spare. */
    private static final int SELLER_CACHE_SIZE = 600;

    /** A player can be renamed, so a name is not kept for the whole session. */
    private static final long SELLER_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * How many seller names may be looked up at once.
     *
     * <p>There is no bulk endpoint — {@code /players/search} offers findByUuid, findByName and
     * findByIdentityId, all single — so this is the only lever. Resolving every seller on the market
     * was 147 requests behind one click; a screen shows about fifteen rows, and a cap keeps a fast
     * scroll from queueing the whole market again.
     */
    private static final int MAX_SELLER_LOOKUPS_IN_FLIGHT = 8;

    private final HttpTransport transport;

    /** Seller names by identity ID. Listings only carry the ID, and many share a seller. */
    private final LruCache<String, String> sellerNames =
            new LruCache<>(SELLER_CACHE_SIZE, SELLER_TTL_MILLIS);

    /** Identity IDs being looked up right now, so the same one is never fetched twice at once. */
    private final java.util.Set<String> sellersInFlight = ConcurrentHashMap.newKeySet();

    private volatile List<String> types;

    public MarketApi(HttpTransport transport) {
        this.transport = transport;
    }

    public ExecutorService executor() {
        return transport.executor();
    }

    /** Every item type that has ever been listed, for matching what the player typed. */
    public List<String> fetchTypes() throws ApiException {
        List<String> cached = types;
        if (cached != null) {
            return cached;
        }
        JsonObject json = getObject(BASE_URL + "/market/search/findTypes");
        JsonElement array = json.get("types");
        List<String> found = new ArrayList<>();
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                found.add(element.getAsString());
            }
        }
        found.sort(String.CASE_INSENSITIVE_ORDER);
        types = List.copyOf(found);
        return types;
    }

    /**
     * Item types whose name contains {@code query}, best matches first: exact, then prefix, then
     * anywhere.
     */
    public List<String> matchTypes(String query, int limit) throws ApiException {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (needle.isEmpty()) {
            return List.of();
        }
        List<String> ranked = new ArrayList<>();
        for (String type : fetchTypes()) {
            if (type.toLowerCase(Locale.ROOT).contains(needle)) {
                ranked.add(type);
            }
        }
        ranked.sort(Comparator
                .comparingInt((String type) -> type.toLowerCase(Locale.ROOT).equals(needle) ? 0
                        : type.toLowerCase(Locale.ROOT).startsWith(needle) ? 1 : 2)
                .thenComparingInt(String::length)
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : List.copyOf(ranked);
    }

    /** Everything currently on sale of one item type, cheapest per unit first. */
    public List<MarketListing> fetchListings(String type) throws ApiException {
        List<MarketListing> listings = readListings(
                getObject(BASE_URL + "/market_sales/search/findByType?type=" + encode(type)));
        List<MarketListing> sorted = new ArrayList<>(listings);
        sorted.sort(Comparator.comparingDouble(MarketListing::pricePerUnit));
        return List.copyOf(sorted);
    }

    /**
     * The seller's name if it is already known, without going anywhere for it.
     *
     * <p>Safe to call while drawing: it never blocks and never starts a request.
     *
     * @return the name, {@code ""} if the lookup came back with nobody, or {@code null} if it has
     *         not been looked up
     */
    public String sellerNameIfKnown(String ownerId) {
        return ownerId == null || ownerId.isEmpty() ? null : sellerNames.get(ownerId);
    }

    /**
     * Starts looking up the names among {@code ownerIds} that are not known yet.
     *
     * <p>Called with the rows actually on screen. Everything on the market used to be resolved the
     * moment a sweep finished — 147 requests admitted by one click, into the same four-thread pool
     * and 10-per-second budget every other screen shares, which is what made a Fight Manager refresh
     * take half a minute. Names are wanted for what is being looked at, and that is what is fetched.
     *
     * <p>Runs as background work: a name that fills in a moment later is worth less than the request
     * somebody is actually waiting on.
     */
    public void requestSellerNames(Iterable<String> ownerIds) {
        for (String ownerId : ownerIds) {
            if (ownerId == null || ownerId.isEmpty() || sellerNames.has(ownerId)) {
                continue;
            }
            if (sellersInFlight.size() >= MAX_SELLER_LOOKUPS_IN_FLIGHT) {
                return;
            }
            if (!sellersInFlight.add(ownerId)) {
                continue;
            }
            transport.bulkExecutor().execute(() -> {
                try {
                    sellerName(ownerId);
                } finally {
                    sellersInFlight.remove(ownerId);
                }
            });
        }
    }

    /** @return the seller's name, or {@code null} if it cannot be resolved. Cached per identity. */
    public String sellerName(String ownerId) {
        if (ownerId == null || ownerId.isEmpty()) {
            return null;
        }
        String cached = sellerNames.get(ownerId);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String resolved = "";
        try {
            JsonObject json = getObject(
                    BASE_URL + "/players/search/findByIdentityId?identityId=" + encode(ownerId), true);
            JsonObject embedded = Json.object(json, "_embedded");
            JsonElement players = embedded == null ? null : embedded.get("players");
            if (players != null && players.isJsonArray() && !players.getAsJsonArray().isEmpty()) {
                String name = Json.string(players.getAsJsonArray().get(0).getAsJsonObject(), "name");
                if (name != null) {
                    resolved = name;
                }
            }
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not resolve market seller {}", ownerId, e);
        }
        sellerNames.put(ownerId, resolved);
        return resolved.isEmpty() ? null : resolved;
    }

    private static List<MarketListing> readListings(JsonObject json) {
        JsonObject embedded = Json.object(json, "_embedded");
        JsonElement array = embedded == null ? null : embedded.get("market_sales");
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<MarketListing> listings = new ArrayList<>();
        for (JsonElement element : array.getAsJsonArray()) {
            if (element.isJsonObject()) {
                listings.add(MarketListing.fromJson(element.getAsJsonObject()));
            }
        }
        return listings;
    }

    private JsonObject getObject(String url) throws ApiException {
        return getObject(url, false);
    }

    /**
     * @param background true for a sweep or a name filling in behind the rendering, which then
     *                   yields its place in the queue to whatever a player is waiting on
     */
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
