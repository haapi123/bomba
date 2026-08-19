package com.betterloka.api;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.BattleZone;
import com.betterloka.api.model.Json;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin client over the public Loka REST API at {@code https://api.lokamc.com}.
 *
 * <p>Every method here is blocking and is expected to be called from {@link #executor()}, never
 * from the render thread.
 */
public final class LokaApi implements AutoCloseable {
    public static final String BASE_URL = "https://api.lokamc.com";

    /**
     * The server clamps {@code size} to 20 no matter what is requested, so paging maths has to
     * assume 20 rather than whatever we ask for.
     */
    public static final int PAGE_SIZE = 20;

    private static final String MOJANG_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Sustained request rate. Measured against the live API: a full history sync at 8/s still drew
     * the occasional 429, so the pace is set below that. A first sync is around 410 requests, so
     * this puts it at roughly a minute and a half — paid once, then never again.
     */
    private static final double PERMITS_PER_SECOND = 5.0;
    private static final double BURST = 8.0;

    /** Header the API sets on a 429; it is often 0, so it is treated as a floor, not the whole wait. */
    private static final String RETRY_AFTER_HEADER = "x-rate-limit-retry-after-seconds";

    private final HttpClient http;
    private final ExecutorService executor;
    private final ExecutorService bulkExecutor;
    private final ExecutorService httpExecutor;
    private final RateLimiter rateLimiter;
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong throttleCount = new AtomicLong();

    public LokaApi() {
        this(4, PERMITS_PER_SECOND);
    }

    /**
     * @param workers      how many bulk requests may be in flight at once. Kept deliberately small:
     *                     a full battle history sync is several hundred requests and this mod ships
     *                     to every player on the server.
     * @param permitsPerSecond sustained request rate across both pools.
     */
    public LokaApi(int workers, double permitsPerSecond) {
        this.rateLimiter = new RateLimiter(permitsPerSecond, Math.min(BURST, permitsPerSecond));
        this.executor = Executors.newFixedThreadPool(2, daemonFactory("BetterLoka-API-"));
        this.bulkExecutor = Executors.newFixedThreadPool(workers, daemonFactory("BetterLoka-Sync-API-"));
        // Deliberately not the same pool as the task executor: the task threads block inside
        // HttpClient.send(), and HTTP/2 does some of its work on the client's executor, so sharing
        // one bounded pool between the two can deadlock.
        this.httpExecutor = Executors.newCachedThreadPool(daemonFactory("BetterLoka-HTTP-"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // HTTP/1.1 on purpose. Over HTTP/2 the client multiplexes every request onto a
                // single connection to api.lokamc.com and the whole sweep serialises behind it;
                // a 1.1 connection pool lets the four workers actually run in parallel.
                .version(HttpClient.Version.HTTP_1_1)
                .executor(this.httpExecutor)
                .build();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Pool for requests a player is waiting on. Deliberately separate from {@link #bulkExecutor()}:
     * a history sync queues hundreds of tasks, and a lookup sharing that queue would sit behind all
     * of them. Both pools draw from the same rate limiter, so splitting them costs the API nothing.
     */
    public ExecutorService executor() {
        return executor;
    }

    /** Pool for background bulk work — currently the battle history sync. */
    public ExecutorService bulkExecutor() {
        return bulkExecutor;
    }

    /** Total requests sent, for diagnostics. */
    public long requestCount() {
        return requestCount.get();
    }

    /** How many times the server answered 429, for diagnostics. */
    public long throttleCount() {
        return throttleCount.get();
    }

    /**
     * Looks a player up by name. The Loka endpoint is case sensitive and 404s on a casing mismatch,
     * so a miss falls back to resolving the canonical name through Mojang and retrying by UUID.
     */
    public LokaPlayer findPlayerByName(String name) throws LokaApiException {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new LokaApiException("Empty player name", true);
        }
        try {
            return LokaPlayer.fromJson(getObject(BASE_URL + "/players/search/findByName?name=" + encode(trimmed)));
        } catch (LokaApiException e) {
            if (!e.notFound()) {
                throw e;
            }
        }

        UUID uuid = resolveMojangUuid(trimmed);
        if (uuid == null) {
            throw new LokaApiException("No player named " + trimmed, true);
        }
        return findPlayerByUuid(uuid);
    }

    public LokaPlayer findPlayerByUuid(UUID uuid) throws LokaApiException {
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
    public LokaTown findTownByMember(String identityId) throws LokaApiException {
        if (identityId == null || identityId.isEmpty()) {
            return null;
        }
        try {
            return LokaTown.fromJson(getObject(BASE_URL + "/towns/search/findByMember?id=" + encode(identityId)));
        } catch (LokaApiException e) {
            if (e.notFound()) {
                return null;
            }
            throw e;
        }
    }

    public LokaTown findTownById(String townId) throws LokaApiException {
        if (townId == null || townId.isEmpty()) {
            return null;
        }
        try {
            return LokaTown.fromJson(getObject(BASE_URL + "/towns/search/findById?id=" + encode(townId)));
        } catch (LokaApiException e) {
            if (e.notFound()) {
                return null;
            }
            throw e;
        }
    }

    /** One page of towns. */
    public record TownPage(List<LokaTown> towns, int totalPages) {
    }

    public TownPage fetchTownPage(int page) throws LokaApiException {
        JsonObject json = getObject(BASE_URL + "/towns?size=" + PAGE_SIZE + "&page=" + page);
        JsonObject embedded = Json.object(json, "_embedded");
        JsonElement array = embedded == null ? null : embedded.get("towns");
        List<LokaTown> towns = new ArrayList<>();
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    towns.add(LokaTown.fromJson(element.getAsJsonObject()));
                }
            }
        }
        return new TownPage(towns, Json.integer(Json.object(json, "page"), "totalPages", 0));
    }

    /** One page of battles, newest first. */
    public record BattlePage(List<BattleZone> battles, int totalPages, int totalElements) {
    }

    /**
     * @param page zero based page index over all battles, sorted by end time descending so that
     *             page 0 always holds the most recent battles.
     */
    public BattlePage fetchBattlePage(int page) throws LokaApiException {
        JsonObject json = getObject(BASE_URL + "/battlezones?size=" + PAGE_SIZE + "&page=" + page + "&sort=timeEnded,desc");
        JsonObject pageInfo = Json.object(json, "page");
        return new BattlePage(
                readBattles(json),
                Json.integer(pageInfo, "totalPages", 0),
                Json.integer(pageInfo, "totalElements", 0));
    }

    /**
     * Battles happening right now. These have no end time yet, so they sort to the back of the
     * paged listing and have to be fetched separately to be counted.
     */
    public List<BattleZone> fetchActiveBattles() throws LokaApiException {
        return readBattles(getObject(BASE_URL + "/battlezones/search/findBattles"));
    }

    private static List<BattleZone> readBattles(JsonObject json) {
        JsonObject embedded = Json.object(json, "_embedded");
        JsonElement array = embedded == null ? null : embedded.get("battlezones");
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<BattleZone> battles = new ArrayList<>();
        for (JsonElement element : array.getAsJsonArray()) {
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

    /** @return the canonical UUID for a name of any casing, or {@code null} if no such account. */
    private UUID resolveMojangUuid(String name) {
        try {
            JsonObject json = getObject(MOJANG_PROFILE_URL + encode(name));
            String undashed = Json.string(json, "id");
            if (undashed == null || undashed.length() != 32) {
                return null;
            }
            return UUID.fromString(undashed.substring(0, 8) + "-" + undashed.substring(8, 12) + "-"
                    + undashed.substring(12, 16) + "-" + undashed.substring(16, 20) + "-" + undashed.substring(20));
        } catch (LokaApiException | IllegalArgumentException e) {
            return null;
        }
    }

    private JsonObject getObject(String url) throws LokaApiException {
        String body = get(url);
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new LokaApiException("Unexpected response shape from " + url, false);
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new LokaApiException("Malformed response from " + url, e);
        }
    }

    private String get(String url) throws LokaApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "BetterLoka/" + BetterLoka.VERSION + " (Minecraft mod)")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        LokaApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long backoffMillis;
            try {
                rateLimiter.acquire();
                requestCount.incrementAndGet();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    return response.body();
                }
                if (status == 404) {
                    throw new LokaApiException("Not found: " + url, true);
                }
                // 429 and 5xx are worth another go; anything else is our fault, not the server's.
                if (status != 429 && status < 500) {
                    throw new LokaApiException("HTTP " + status + " from " + url, false);
                }
                last = new LokaApiException("HTTP " + status + " from " + url, false);
                backoffMillis = backoffFor(response, status, attempt);
                if (status == 429) {
                    throttleCount.incrementAndGet();
                    // Hold every other thread off too — the budget is shared, so one thread
                    // sleeping while the rest keep hammering would not help.
                    rateLimiter.backOff(Duration.ofMillis(backoffMillis));
                }
            } catch (IOException e) {
                last = new LokaApiException("Could not reach " + url, e);
                backoffMillis = 400L * attempt;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LokaApiException("Interrupted while fetching " + url, e);
            }

            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LokaApiException("Interrupted while fetching " + url, e);
                }
            }
        }
        throw last;
    }

    /** Exponential backoff with jitter, floored by whatever the server asked for. */
    private static long backoffFor(HttpResponse<String> response, int status, int attempt) {
        long base = 300L * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(100L, 400L);
        long backoff = base + jitter;
        if (status == 429) {
            long requested = response.headers().firstValue(RETRY_AFTER_HEADER)
                    .map(value -> {
                        try {
                            return Long.parseLong(value.trim()) * 1000L;
                        } catch (NumberFormatException e) {
                            return 0L;
                        }
                    })
                    .orElse(0L);
            backoff = Math.max(backoff, requested);
        }
        return backoff;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        bulkExecutor.shutdownNow();
        httpExecutor.shutdownNow();
    }
}
