package com.betterloka.api;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.Json;
import com.betterloka.api.model.MarketListing;
import com.betterloka.api.model.MarketSort;
import com.betterloka.data.LruCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    /**
     * The market's own threads, for the pages of a sweep.
     *
     * <p>A sweep used to run in the shared background pool, so a Refresh — forty-five pages a player
     * is sitting watching — queued behind the battle archive and the ranked history, four threads
     * between all of them, and paced as work nobody was waiting on. It took minutes. The pages now
     * have threads of their own, and a sweep somebody asked for is fetched as a request somebody is
     * waiting on: measured, Loka hands out all forty-five in about a second.
     */
    private static final int PAGE_THREADS = 6;
    private final java.util.concurrent.ExecutorService pageExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(PAGE_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "BetterLoka-Market-" + System.identityHashCode(runnable));
                thread.setDaemon(true);
                return thread;
            });

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
        return matchTypes(fetchTypes(), query, limit);
    }

    /**
     * Ranks {@code types} against what was typed.
     *
     * <p>Both sides are read the way a player would: colour codes off, underscores as spaces, case
     * ignored — so {@code xp bottle} finds {@code §3Xp Bottles}, and {@code diamond sword} finds
     * {@code DIAMOND_SWORD}.
     */
    public static List<String> matchTypes(List<String> types, String query, int limit) {
        String needle = searchKey(query);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<String> ranked = new ArrayList<>();
        for (String type : types) {
            if (searchKey(type).contains(needle)) {
                ranked.add(type);
            }
        }
        ranked.sort(Comparator
                .comparingInt((String type) -> searchKey(type).equals(needle) ? 0
                        : searchKey(type).startsWith(needle) ? 1 : 2)
                .thenComparingInt(type -> searchKey(type).length())
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return ranked.size() > limit ? List.copyOf(ranked.subList(0, limit)) : List.copyOf(ranked);
    }

    /** A type or a query as it is compared: {@code §3Xp_Bottles} to {@code xp bottles}. */
    static String searchKey(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("§.", "").replace('_', ' ').toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    /** Everything currently on sale of one item type, cheapest per item first. */
    public List<MarketListing> fetchListings(String type) throws ApiException {
        List<MarketListing> listings = readListings(
                getObject(BASE_URL + "/market_sales/search/findByType?type=" + encode(type)));
        return MarketSort.CHEAPEST.sort(listings);
    }

    // --- the whole market ---

    /** The API hands the market out twenty offers a page, whatever size is asked for. */
    private static final int PAGE_SIZE = 20;

    /**
     * How long a sweep of the whole market is good for before opening the square fetches it again.
     * The square opens on what it has either way, and Refresh fetches it now whatever its age.
     */
    private static final long SWEEP_TTL_MILLIS = 10 * 60 * 1000L;

    /** The oldest saved market still worth opening on while a fresh one is fetched. */
    private static final long CACHE_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L;

    /** The newest sweep, running or done. */
    private volatile MarketSweep sweep;

    /** The newest sweep that finished with offers in it — what the square shows while another runs. */
    private volatile MarketSweep lastFinished;

    private volatile Path cacheFile;

    /**
     * The whole market, fetching it again if the last one is older than a few minutes.
     *
     * <p>Loka's API has no categories and no way to ask for more than twenty offers at once, so the
     * square is built from every page of {@code /market_sales} — about forty requests. They go out
     * in the background lane, behind anything a player is waiting on.
     *
     * <p>Nobody waits for them, though: the market is fetched ahead of time while the player is
     * online, and the last one is kept on disk, so the square opens full and a refresh replaces it
     * when it lands — see {@link #shown()}.
     *
     * @param refresh true to fetch it again even if the last sweep is still fresh
     */
    public synchronized MarketSweep sweep(boolean refresh) {
        return sweep(refresh ? 0 : SWEEP_TTL_MILLIS, refresh);
    }

    /** Fetches the market again, in the background, if what is held is older than {@code maxAgeMillis}. */
    public synchronized MarketSweep sweep(long maxAgeMillis) {
        return sweep(maxAgeMillis, false);
    }

    /**
     * @param urgent true when somebody pressed Refresh and is watching: the pages are fetched as
     *               requests somebody is waiting on, and a background sweep already running is
     *               hurried along rather than waited out
     */
    private synchronized MarketSweep sweep(long maxAgeMillis, boolean urgent) {
        MarketSweep current = sweep;
        if (current != null && current.running()) {
            if (urgent) {
                current.makeUrgent();
            }
            return current;
        }
        MarketSweep basis = current != null && !current.failed() ? current : lastFinished;
        if (basis != null && System.currentTimeMillis() - basis.finishedAt() < maxAgeMillis) {
            return basis;
        }
        MarketSweep fresh = new MarketSweep(this::finished);
        if (urgent) {
            fresh.makeUrgent();
        }
        sweep = fresh;
        pageExecutor.execute(() -> runSweep(fresh));
        return fresh;
    }

    /**
     * What the square should show: the newest market that finished, or — the very first time, with
     * nothing saved — the one coming in, page by page.
     */
    public MarketSweep shown() {
        MarketSweep current = sweep;
        if (current != null && !current.running() && !current.failed() && !current.listings().isEmpty()) {
            return current;
        }
        MarketSweep finished = lastFinished;
        return finished != null ? finished : current;
    }

    /** Whether a fresh market is being fetched behind what is shown. */
    public boolean refreshing() {
        MarketSweep current = sweep;
        return current != null && current.running();
    }

    /** The newest sweep, finished or not, without starting one. */
    public MarketSweep currentSweep() {
        return sweep;
    }

    private void finished(MarketSweep done) {
        if (done.failed() || done.listings().isEmpty()) {
            return;
        }
        lastFinished = done;
        if (done.whole()) {
            save(done);
        }
    }

    // --- the market on disk ---

    /** Where the last whole market is kept between sessions; read back at once, in the background. */
    public void useCache(Path file) {
        this.cacheFile = file;
        transport.backgroundExecutor().execute(this::restore);
    }

    private void restore() {
        Path file = cacheFile;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            long savedAt = Json.longValue(saved, "savedAt", 0);
            if (System.currentTimeMillis() - savedAt > CACHE_MAX_AGE_MILLIS) {
                return;
            }
            JsonElement array = saved.get("listings");
            if (array == null || !array.isJsonArray()) {
                return;
            }
            List<JsonObject> raw = new ArrayList<>();
            for (JsonElement element : array.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    raw.add(element.getAsJsonObject());
                }
            }
            MarketSweep restored = MarketSweep.restored(raw, savedAt);
            synchronized (this) {
                if (lastFinished == null) {
                    lastFinished = restored;
                }
            }
            BetterLoka.LOGGER.info("[betterloka] market restored from disk: {} offers", raw.size());
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the saved market", e);
        }
    }

    private void save(MarketSweep done) {
        Path file = cacheFile;
        if (file == null) {
            return;
        }
        try {
            JsonObject saved = new JsonObject();
            saved.addProperty("savedAt", done.finishedAt());
            JsonArray array = new JsonArray();
            for (JsonObject raw : done.raw()) {
                array.add(raw);
            }
            saved.add("listings", array);
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, saved.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not save the market", e);
        }
    }

    private void runSweep(MarketSweep target) {
        JsonObject first;
        try {
            first = getObject(pageUrl(0), !target.urgent());
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not fetch the first market page", e);
            target.fail();
            return;
        }
        int pages = Math.max(1, Json.integer(Json.object(first, "page"), "totalPages", 1));
        target.start(pages);
        List<JsonObject> firstRaw = rawListings(first);
        target.add(parse(firstRaw), firstRaw);
        for (int page = 1; page < pages; page++) {
            int number = page;
            pageExecutor.execute(() -> {
                try {
                    // Read when the page's turn comes, so a Refresh pressed half way through
                    // hurries the rest.
                    List<JsonObject> raw = rawListings(getObject(pageUrl(number), !target.urgent()));
                    target.add(parse(raw), raw);
                } catch (ApiException | RuntimeException e) {
                    BetterLoka.LOGGER.debug("Could not fetch market page {}", number, e);
                    target.pageFailed();
                }
            });
        }
    }

    /** One page's offers as the API sent them, without the links that would only bloat the save. */
    private static List<JsonObject> rawListings(JsonObject json) {
        JsonObject embedded = Json.object(json, "_embedded");
        JsonElement array = embedded == null ? null : embedded.get("market_sales");
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement element : array.getAsJsonArray()) {
            if (element.isJsonObject()) {
                JsonObject copy = element.getAsJsonObject().deepCopy();
                copy.remove("_links");
                out.add(copy);
            }
        }
        return out;
    }

    private static List<MarketListing> parse(List<JsonObject> raw) {
        List<MarketListing> out = new ArrayList<>(raw.size());
        for (JsonObject object : raw) {
            out.add(MarketListing.fromJson(object));
        }
        return out;
    }

    private static String pageUrl(int page) {
        return BASE_URL + "/market_sales?size=" + PAGE_SIZE + "&page=" + page;
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
