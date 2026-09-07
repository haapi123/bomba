package com.betterloka.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic harness for the reported stall: measurements, not assertions.
 *
 * <p>Runs the real {@link HttpTransport} and {@link RateLimiter} against a local stand-in for
 * api.lokamc.com so the numbers come from the shipped code rather than from a model of it. Nothing
 * here leaves the machine.
 *
 * <p>Minutes rather than seconds — the point is to sit through the queueing — so it is opt in:
 * {@code ./gradlew test -Pbetterloka.diagnose=true}.
 */
@EnabledIfSystemProperty(named = "betterloka.diagnose", matches = "true")
class ContentionDiagnosisTest {
    private HttpServer server;
    private String base;
    private final AtomicInteger served = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        // Plenty of server-side capacity: anything we measure is the client's own queueing.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(32));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        served.incrementAndGet();
        try {
            // A realistic server round trip, so client-side queueing stands out against it.
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static int modThreads() {
        Set<Thread> all = Thread.getAllStackTraces().keySet();
        int count = 0;
        for (Thread thread : all) {
            if (thread.getName().startsWith("BetterLoka-")) {
                count++;
            }
        }
        return count;
    }

    private static String threadBreakdown() {
        java.util.Map<String, Integer> byPrefix = new java.util.TreeMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (!name.startsWith("BetterLoka-")) {
                continue;
            }
            String prefix = name.replaceAll("\\d+$", "");
            byPrefix.merge(prefix, 1, Integer::sum);
        }
        return byPrefix.toString();
    }

    /**
     * The Market's seller fan-out against an interactive lookup from another screen.
     *
     * <p>Every screen submits to {@code transport.executor()} and every Loka host shares one
     * {@link RateLimiter}, so this measures what a Fight Manager refresh costs once the Market has
     * queued a snapshot's worth of seller lookups.
     */
    @Test
    void sellerFanOutDelaysAnInteractiveLookup() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            System.out.println("=== B) threads at rest: " + modThreads() + " " + threadBreakdown());

            // Warm one connection so the measurement is not the first TCP handshake.
            transport.get(base + "/warm");

            long controlStart = System.nanoTime();
            transport.get(base + "/battlezones");
            long controlMillis = (System.nanoTime() - controlStart) / 1_000_000;
            System.out.println("=== control: a lone interactive request took " + controlMillis + " ms");

            for (int sellers : new int[]{50, 150, 300}) {
                drainLimiter(transport);

                List<CompletableFuture<?>> fanOut = new ArrayList<>();
                for (int i = 0; i < sellers; i++) {
                    int id = i;
                    fanOut.add(CompletableFuture.runAsync(() -> {
                        try {
                            transport.get(base + "/players/search/findByIdentityId?identityId=" + id);
                        } catch (ApiException e) {
                            throw new RuntimeException(e);
                        }
                    }, transport.executor()));
                }

                // The other screen's refresh, submitted straight after the fan-out, as a tab switch
                // does. Timed from submission, which is what the player experiences.
                long start = System.nanoTime();
                CompletableFuture<Long> interactive = CompletableFuture.supplyAsync(() -> {
                    long began = System.nanoTime();
                    try {
                        transport.get(base + "/battlezones");
                    } catch (ApiException e) {
                        throw new RuntimeException(e);
                    }
                    return began;
                }, transport.executor());

                long beganNanos = interactive.get(5, TimeUnit.MINUTES);
                long waitedMillis = (beganNanos - start) / 1_000_000;
                long totalMillis = (System.nanoTime() - start) / 1_000_000;
                System.out.println("=== A/B) after " + sellers + " seller lookups queued: refresh sat "
                        + waitedMillis + " ms in the pool queue, " + totalMillis + " ms in total"
                        + "  (threads: " + modThreads() + ")");

                CompletableFuture.allOf(fanOut.toArray(new CompletableFuture[0])).join();
                System.out.println("        fan-out drained; threads now " + modThreads()
                        + " " + threadBreakdown());
            }
        }
    }

    /** Lets the token bucket refill so each round starts from the same place. */
    private static void drainLimiter(HttpTransport transport) throws InterruptedException {
        Thread.sleep(1200);
    }

    /** Does repeating the scenario leave threads behind? */
    @Test
    void threadCountAcrossRepeatedScenarios() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            System.out.println("=== B) threads after start: " + modThreads() + " " + threadBreakdown());
            for (int round = 1; round <= 20; round++) {
                CountDownLatch done = new CountDownLatch(12);
                for (int i = 0; i < 12; i++) {
                    int id = i;
                    CompletableFuture.runAsync(() -> {
                        try {
                            transport.get(base + "/market_sales?page=" + id);
                        } catch (ApiException e) {
                            // Counted below regardless.
                        } finally {
                            done.countDown();
                        }
                    }, transport.executor());
                }
                done.await(2, TimeUnit.MINUTES);
                if (round == 5 || round == 20) {
                    System.out.println("=== B) threads after " + round + " rounds: " + modThreads()
                            + " " + threadBreakdown());
                }
            }
            System.gc();
            Thread.sleep(500);
            System.out.println("=== B) threads at end: " + modThreads() + " " + threadBreakdown());
            System.out.println("=== C) requests served by the stand-in: " + served.get());
        }
    }

    /**
     * How deep the token bucket lets callers reserve into the future.
     *
     * <p>{@link RateLimiter#acquire} hands out permits that are already spent, so the wait a caller
     * sleeps off is set by how many reserved before it — including work nobody is waiting on.
     */
    @Test
    void reservationDepthGrowsWithQueuedWork() throws Exception {
        int callers = 200;
        RateLimiter limiter = new RateLimiter(10.0, 10.0);
        long[] waitedMillis = new long[callers];
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);

        for (int i = 0; i < callers; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                    long start = System.nanoTime();
                    limiter.acquire(false);
                    waitedMillis[index] = (System.nanoTime() - start) / 1_000_000;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        done.await(2, TimeUnit.MINUTES);
        long totalMillis = (System.nanoTime() - start) / 1_000_000;

        long[] sorted = waitedMillis.clone();
        java.util.Arrays.sort(sorted);
        System.out.println("=== C) " + callers + " callers reserving at once on one host bucket:");
        System.out.println("        soonest " + sorted[0] + " ms, median " + sorted[callers / 2]
                + " ms, latest " + sorted[callers - 1] + " ms, all done after " + totalMillis + " ms");
        System.out.println("        a request arriving now would queue behind all of them.");

        long lateStart = System.nanoTime();
        limiter.acquire(false);
        System.out.println("=== C) a fresh interactive acquire straight after: "
                + (System.nanoTime() - lateStart) / 1_000_000 + " ms");
    }
}
