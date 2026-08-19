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
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

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

    /** One bucket per host: the services have independent budgets and must not throttle each other. */
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    private final double permitsPerSecond;

    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong throttleCount = new AtomicLong();

    public HttpTransport() {
        this(4, DEFAULT_PERMITS_PER_SECOND);
    }

    public HttpTransport(int bulkWorkers, double permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        this.executor = Executors.newFixedThreadPool(2, daemonFactory("BetterLoka-API-"));
        // Interactive lookups get their own pool. Sharing one with bulk work means a search queues
        // behind every background task and appears to hang.
        this.bulkExecutor = Executors.newFixedThreadPool(bulkWorkers, daemonFactory("BetterLoka-Bulk-"));
        this.httpExecutor = Executors.newCachedThreadPool(daemonFactory("BetterLoka-HTTP-"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
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

    public long requestCount() {
        return requestCount.get();
    }

    /** How many times a host answered 429, for diagnostics. */
    public long throttleCount() {
        return throttleCount.get();
    }

    /**
     * @return the response body.
     * @throws ApiException on 404 ({@link ApiException#notFound()}), on a redirect away from the
     *                      requested resource, or when the host stays unreachable.
     */
    public String get(String url) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json, text/html;q=0.9")
                .header("User-Agent", "BetterLoka/" + BetterLoka.VERSION + " (Minecraft mod)")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        RateLimiter limiter = limiterFor(URI.create(url).getHost());

        ApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long backoffMillis;
            try {
                limiter.acquire();
                requestCount.incrementAndGet();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
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
                throw new ApiException("Interrupted while fetching " + url, e);
            }

            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiException("Interrupted while fetching " + url, e);
                }
            }
        }
        throw last;
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
        httpExecutor.shutdownNow();
    }
}
