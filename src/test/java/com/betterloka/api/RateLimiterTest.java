package com.betterloka.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget every screen shares, and the rule that keeps a sweep from spending it all.
 *
 * <p>Permits may be reserved into the future, which is what turns concurrent callers into a queue
 * rather than a stampede. The fault it caused was that background work could reserve the bucket a
 * hundred permits into debt in a burst, and the next interactive request inherited all of it —
 * measured at nineteen seconds for the last of two hundred callers.
 */
class RateLimiterTest {
    @Test
    void backgroundWorkNoLongerPutsAClickIntoItsDebt() throws Exception {
        RateLimiter limiter = new RateLimiter(10.0, 10.0);

        int backgroundCallers = 100;
        CountDownLatch started = new CountDownLatch(backgroundCallers);
        Thread[] threads = new Thread[backgroundCallers];
        for (int i = 0; i < backgroundCallers; i++) {
            threads[i] = new Thread(() -> {
                started.countDown();
                try {
                    limiter.acquire(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].setDaemon(true);
            threads[i].start();
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        // Long enough for every one of them to have reached the bucket and taken what it can.
        Thread.sleep(500);

        long start = System.nanoTime();
        limiter.acquire(false);
        long waitedMillis = (System.nanoTime() - start) / 1_000_000;

        for (Thread thread : threads) {
            thread.interrupt();
        }
        // One request's worth of pacing, with room for a slow machine — not the whole backlog.
        assertTrue(waitedMillis < 1500,
                "an interactive request behind " + backgroundCallers
                        + " background ones waited " + waitedMillis + " ms");
    }

    /** Interactive callers still queue among themselves, or the pacing would mean nothing. */
    @Test
    void interactiveCallersStillPaceThemselves() throws Exception {
        RateLimiter limiter = new RateLimiter(10.0, 2.0);
        long start = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            limiter.acquire(false);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        // Two free from the burst, four paced at ten a second.
        assertTrue(elapsedMillis >= 300,
                "six requests at ten a second should take at least 300 ms, took " + elapsedMillis);
    }

    /** A 429 has to hold everyone off, background and interactive alike. */
    @Test
    void serverPushbackHoldsEveryCallerOff() throws Exception {
        RateLimiter limiter = new RateLimiter(100.0, 100.0);
        limiter.backOff(java.time.Duration.ofMillis(300));

        long start = System.nanoTime();
        limiter.acquire(false);
        long waitedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(waitedMillis >= 250, "a 429 must hold an interactive caller too, waited "
                + waitedMillis + " ms");
    }
}
