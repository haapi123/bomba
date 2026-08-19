package com.betterloka;

import com.betterloka.api.LokaApi;
import com.betterloka.api.LokaApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures how fast the Loka API will actually let us page through the battle history, so the
 * request rate baked into {@link LokaApi} is a measurement rather than a guess.
 *
 * <p>Run with {@code ./gradlew test -Pbetterloka.diag=true -Pbetterloka.rate=5}.
 */
@EnabledIfSystemProperty(named = "betterloka.diag", matches = "true")
class PageFetchDiagnosticTest {
    private static final int PAGES = 80;

    @Test
    void sweepPages() throws Exception {
        double rate = Double.parseDouble(System.getProperty("betterloka.rate", "8"));
        try (LokaApi api = new LokaApi(4, rate)) {
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            long start = System.currentTimeMillis();
            CompletableFuture<?>[] tasks = new CompletableFuture[PAGES];
            for (int page = 0; page < PAGES; page++) {
                int target = page;
                tasks[page] = CompletableFuture.runAsync(() -> {
                    try {
                        api.fetchBattlePage(target);
                        ok.incrementAndGet();
                    } catch (LokaApiException e) {
                        failed.incrementAndGet();
                        System.out.println("  failed: " + e.getMessage());
                    }
                }, api.executor());
            }
            CompletableFuture.allOf(tasks).join();
            long elapsed = System.currentTimeMillis() - start;

            System.out.printf("rate=%.1f/s  ok=%d failed=%d  requests=%d  429s=%d  elapsed=%.1fs  effective=%.2f pages/s%n",
                    rate, ok.get(), failed.get(), api.requestCount(), api.throttleCount(),
                    elapsed / 1000.0, PAGES / (elapsed / 1000.0));
        }
    }
}
