package com.betterloka.api;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.Json;
import com.betterloka.api.model.MarketListing;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

/** Client for Loka's market endpoints: what is on sale, from whom, and for how much. */
public final class MarketApi {
    private static final String BASE_URL = LokaApi.BASE_URL;

    /** How long a full sweep of the market stays fresh before it is worth pulling again. */
    private static final long SNAPSHOT_TTL_MILLIS = 5 * 60 * 1000L;

    /** Everything on sale right now, with what it is collectively worth. */
    public record Snapshot(List<MarketListing> listings, long fetchedAt) {
        public double totalValue() {
            return listings.stream().mapToDouble(MarketListing::price).sum();
        }

        public int totalItems() {
            return listings.stream().mapToInt(MarketListing::quantity).sum();
        }

        public long distinctSellers() {
            return listings.stream().map(MarketListing::ownerId).filter(java.util.Objects::nonNull).distinct().count();
        }

        boolean stale() {
            return System.currentTimeMillis() - fetchedAt > SNAPSHOT_TTL_MILLIS;
        }
    }

    private final HttpTransport transport;

    /** Seller names by identity ID. Listings only carry the ID, and many share a seller. */
    private final Map<String, String> sellerNames = new ConcurrentHashMap<>();
    private volatile List<String> types;
    private volatile Snapshot snapshot;
    private CompletableFuture<Snapshot> inFlightSnapshot;

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
     * The whole market. About fifty requests, so it is cached for a few minutes and shared between
     * callers rather than re-fetched per tab.
     *
     * @param onProgress called with the number of pages fetched so far.
     */
    public synchronized CompletableFuture<Snapshot> snapshot(IntConsumer onProgress) {
        Snapshot cached = snapshot;
        if (cached != null && !cached.stale()) {
            return CompletableFuture.completedFuture(cached);
        }
        if (inFlightSnapshot != null && !inFlightSnapshot.isDone()) {
            return inFlightSnapshot;
        }
        inFlightSnapshot = CompletableFuture.supplyAsync(() -> {
            try {
                return loadSnapshot(onProgress);
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, transport.executor());
        return inFlightSnapshot;
    }

    private Snapshot loadSnapshot(IntConsumer onProgress) throws ApiException {
        JsonObject first = getObject(BASE_URL + "/market_sales?size=" + LokaApi.PAGE_SIZE + "&page=0");
        int totalPages = Json.integer(Json.object(first, "page"), "totalPages", 1);

        List<MarketListing> all = new ArrayList<>(readListings(first));
        onProgress.accept(1);

        List<CompletableFuture<List<MarketListing>>> tasks = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(1);
        for (int page = 1; page < totalPages; page++) {
            int target = page;
            tasks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return readListings(getObject(
                            BASE_URL + "/market_sales?size=" + LokaApi.PAGE_SIZE + "&page=" + target));
                } catch (ApiException e) {
                    BetterLoka.LOGGER.debug("Market page {} failed", target, e);
                    return List.<MarketListing>of();
                } finally {
                    onProgress.accept(done.incrementAndGet());
                }
            }, transport.bulkExecutor()));
        }
        for (CompletableFuture<List<MarketListing>> task : tasks) {
            all.addAll(task.join());
        }

        Snapshot fresh = new Snapshot(List.copyOf(all), System.currentTimeMillis());
        snapshot = fresh;
        return fresh;
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
            JsonObject json = getObject(BASE_URL + "/players/search/findByIdentityId?identityId=" + encode(ownerId));
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
