package com.betterloka.api;

import com.betterloka.BetterLoka;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared HTTP plumbing for every service BetterLoka talks to: connection pool, worker threads,
 * retries, and one rate limiter per host.
 *
 * <p>Every method here blocks and is expected to run on {@link #executor()} or
 * {@link #bulkExecutor()}, never on the render thread.
 */
public final class HttpTransport implements AutoCloseable {
    private static final int MAX_ATTEMPTS = 3;

    /** How long to wait for a connection before giving up on the host entirely. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** How long one attempt may take. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How long {@link #get} may spend in total, retries and backoff included.
     *
     * <p>Attempts alone are not a bound: five attempts at a thirty-second timeout with backoff
     * between them is over two and a half minutes, which is why a failing tab used to sit on
     * "loading" rather than ever saying so. A deadline turns that into a message a person can act on.
     */
    private static final Duration TOTAL_BUDGET = Duration.ofSeconds(20);

    /**
     * How many requests may be on the wire at once, across every screen.
     *
     * <p>The worker pools already bound this, but they are two, and the limit that matters is on the
     * host rather than on either pool.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 6;

    /** Threads for the HTTP client's own callbacks. Bounded: a cached pool has no ceiling. */
    private static final int HTTP_THREADS = 8;

    /**
     * The map's ground has its own lane, and the numbers here are the whole reason it draws quickly.
     *
     * <p>Terrain tiles are small static JPEGs on an asset CDN, and one screenful is a few hundred of
     * them. Sent down the shared lane they were paced at ten a second behind a six-wide budget every
     * other screen was also drawing on, so a view took the better part of half a minute to sharpen
     * and spent it showing the coarsest zoom — which is what "pixelated" actually was. A browser
     * opening the same map pulls these files as fast as the connection allows, so this lane does
     * too, and each tile is kept on disk afterwards and never asked for twice.
     */
    private static final int TILE_THREADS = 8;
    private static final int MAX_CONCURRENT_TILES = 8;
    private static final double TILE_PERMITS_PER_SECOND = 60.0;
    private static final double TILE_BURST = 30.0;

    /**
     * Default sustained rate per host. A profile lookup is about a dozen requests rather than the
     * several hundred an earlier design needed, so the pace is set for responsiveness; the retry
     * path absorbs the occasional 429 these hosts hand out regardless of pacing.
     */
    private static final double DEFAULT_PERMITS_PER_SECOND = 10.0;
    private static final double BURST = 10.0;

    /** Header Loka's gateway sets on a 429; often 0, so it is a floor rather than the whole wait. */
    private static final String RETRY_AFTER_HEADER = "x-rate-limit-retry-after-seconds";

    private final HttpClient http;
    private final ExecutorService executor;
    private final ExecutorService bulkExecutor;
    private final ExecutorService httpExecutor;
    private final ExecutorService tileExecutor;

    /** One bucket per host: the services have independent budgets and must not throttle each other. */
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final double permitsPerSecond;

    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong throttleCount = new AtomicLong();
    private final AtomicLong cancelledCount = new AtomicLong();

    /** The ceiling on requests in flight, so no burst of work can hold the host on its own. */
    private final java.util.concurrent.Semaphore inFlight =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_REQUESTS, true);

    /** The tiles' own ceiling and pace, kept apart from everything else. See {@link #TILE_THREADS}. */
    private final java.util.concurrent.Semaphore tilesInFlight =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_TILES, true);
    private final RateLimiter tileLimiter = new RateLimiter(TILE_PERMITS_PER_SECOND, TILE_BURST);

    public HttpTransport() {
        this(4, DEFAULT_PERMITS_PER_SECOND);
    }

    public HttpTransport(int bulkWorkers, double permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        // Two lanes rather than one shared pool, and that is the point rather than an oversight: a
        // single pool is what let one screen's sweep sit in front of another screen's click, which
        // is the fault this exists to prevent. Both are fixed size and made once.
        this.executor = Executors.newFixedThreadPool(4, daemonFactory("BetterLoka-API-"));
        this.bulkExecutor = Executors.newFixedThreadPool(bulkWorkers, daemonFactory("BetterLoka-Bulk-"));
        this.httpExecutor = Executors.newFixedThreadPool(HTTP_THREADS, daemonFactory("BetterLoka-HTTP-"));
        this.tileExecutor = Executors.newFixedThreadPool(TILE_THREADS, daemonFactory("BetterLoka-Tiles-"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                // HTTP/1.1 on purpose. Over HTTP/2 the client multiplexes every request onto a
                // single connection per host and parallel work serialises behind it — measured at
                // 8x slower with rate-limit backoff on top.
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

    /** Pool for requests a player is waiting on. */
    public ExecutorService executor() {
        return executor;
    }

    /** Pool for background fan-out, such as fetching several fight pages at once. */
    public ExecutorService bulkExecutor() {
        return bulkExecutor;
    }

    /**
     * Pool for map tiles alone.
     *
     * <p>Separate from {@link #executor()} on purpose: a screenful of ground is a few hundred fetches
     * and would otherwise sit in front of whatever the player actually clicked, which is the same
     * fault the two existing lanes exist to prevent.
     */
    public ExecutorService tileExecutor() {
        return tileExecutor;
    }

    public long requestCount() {
        return requestCount.get();
    }

    /** How many times a host answered 429, for diagnostics. */
    public long throttleCount() {
        return throttleCount.get();
    }

    /** How many requests were abandoned because their view moved on, for diagnostics. */
    public long cancelledCount() {
        return cancelledCount.get();
    }

    /**
     * @return the response body.
     * @throws ApiException on 404 ({@link ApiException#notFound()}), on a redirect away from the
     *                      requested resource, or when the host stays unreachable.
     */
    public String get(String url) throws ApiException {
        return get(url, false);
    }

    /**
     * One map tile, down the map's own lane.
     *
     * <p>Same request as {@link #getBytes}, but paced and counted separately — see
     * {@link #TILE_THREADS} for why the ground cannot share the pace the REST services are held to.
     */
    public byte[] getTile(String url) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "image/jpeg,image/png,image/*")
                .header("User-Agent", "BetterLoka/" + BetterLoka.VERSION + " (Minecraft mod)")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            tileLimiter.acquire(true);
            requestCount.incrementAndGet();
            HttpResponse<byte[]> response;
            tilesInFlight.acquire();
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } finally {
                tilesInFlight.release();
            }
            if (response.statusCode() != 200) {
                throw new ApiException("HTTP " + response.statusCode() + " from " + url,
                        response.statusCode() == 404);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted fetching " + url, e);
        } catch (java.io.IOException e) {
            throw new ApiException("Could not reach " + url, e);
        }
    }

    /**
     * The same fetch, for something that is not text.
     *
     * <p>Used for the map's marker icons. It goes through the same rate limiter as everything else,
     * so a screen asking for nine of them at once still queues politely.
     */
    public byte[] getBytes(String url, boolean background) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "image/png,image/*")
                .header("User-Agent", "BetterLoka/" + BetterLoka.VERSION + " (Minecraft mod)")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            limiterFor(URI.create(url).getHost()).acquire(background);
            requestCount.incrementAndGet();
            HttpResponse<byte[]> response;
            inFlight.acquire();
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } finally {
                inFlight.release();
            }
            if (response.statusCode() != 200) {
                throw new ApiException("HTTP " + response.statusCode() + " from " + url,
                        response.statusCode() == 404);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted fetching " + url, e);
        } catch (java.io.IOException e) {
            throw new ApiException("Could not reach " + url, e);
        }
    }

    /**
     * @param background true for a sweep or an index build — work nobody is watching, which then
     *                   yields its place in the queue to anything a player is waiting on
     */
    public String get(String url, boolean background) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json, text/html;q=0.9")
                .header("User-Agent", "BetterLoka/" + BetterLoka.VERSION + " (Minecraft mod)")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        RateLimiter limiter = limiterFor(URI.create(url).getHost());
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TOTAL_BUDGET.toNanos();

        ApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // Checked before every attempt: a request whose view has gone should stop here rather
            // than keep a worker and a permit for an answer nobody will read.
            abortIfCancelled(url);

            long backoffMillis;
            try {
                limiter.acquire(background);
                abortIfCancelled(url);
                requestCount.incrementAndGet();
                HttpResponse<String> response = send(request);
                int status = response.statusCode();
                if (status == 200) {
                    timing("request", url, startNanos);
                    return response.body();
                }
                if (status == 404) {
                    throw new ApiException("Not found: " + url, true);
                }
                // Redirects are how EldritchBot says "no such player", so they are a miss, not a hop.
                if (status >= 300 && status < 400) {
                    throw new ApiException("Redirected away from " + url, true);
                }
                if (status != 429 && status < 500) {
                    throw new ApiException("HTTP " + status + " from " + url, false);
                }
                last = new ApiException("HTTP " + status + " from " + url, false);
                backoffMillis = backoffFor(response, status, attempt);
                if (status == 429) {
                    throttleCount.incrementAndGet();
                    // Hold every other thread on this host off too — the budget is shared.
                    limiter.backOff(Duration.ofMillis(backoffMillis));
                }
            } catch (IOException e) {
                last = new ApiException("Could not reach " + url, e);
                backoffMillis = 400L * attempt;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelledCount.incrementAndGet();
                throw new ApiException("Cancelled while fetching " + url, e);
            }

            // Retrying past the budget only delays the message; the caller is told now instead.
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (attempt >= MAX_ATTEMPTS || remainingNanos <= 0
                    || remainingNanos < backoffMillis * 1_000_000L) {
                break;
            }
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelledCount.incrementAndGet();
                throw new ApiException("Cancelled while fetching " + url, e);
            }
        }
        timing("failed", url, startNanos);
        throw last;
    }

    /** One attempt, counted against the ceiling on requests in flight. */
    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        inFlight.acquire();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } finally {
            inFlight.release();
        }
    }

    /**
     * Stops a request whose caller has gone away.
     *
     * <p>A cancelled slot interrupts its worker; every blocking call below checks for it, but the
     * gaps between them would otherwise run a whole extra attempt for nothing.
     */
    private void abortIfCancelled(String url) throws ApiException {
        if (Thread.currentThread().isInterrupted()) {
            cancelledCount.incrementAndGet();
            throw new ApiException("Cancelled before fetching " + url, true);
        }
    }

    /** Where the time went, for tracing a slow screen. Debug: one line per request is a lot. */
    private static void timing(String phase, String url, long startNanos) {
        if (BetterLoka.LOGGER.isDebugEnabled()) {
            BetterLoka.LOGGER.debug("[betterloka] timing {} {} {} ms", phase, url,
                    (System.nanoTime() - startNanos) / 1_000_000);
        }
    }

    private RateLimiter limiterFor(String host) {
        return limiters.computeIfAbsent(host == null ? "" : host,
                key -> new RateLimiter(permitsPerSecond, Math.min(BURST, permitsPerSecond)));
    }

    /** Exponential backoff with jitter, floored by whatever the server asked for. */
    private static long backoffFor(HttpResponse<String> response, int status, int attempt) {
        long backoff = 300L * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(100L, 400L);
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

    @Override
    public void close() {
        executor.shutdownNow();
        bulkExecutor.shutdownNow();
        tileExecutor.shutdownNow();
        httpExecutor.shutdownNow();
    }
}
