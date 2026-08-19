package com.betterloka.api;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket that paces every call BetterLoka makes to the Loka API.
 *
 * <p>api.lokamc.com rate limits: it advertises the remaining budget in {@code x-rate-limit-remaining}
 * and answers 429 once that runs out. A full battle history sync is several hundred requests, and
 * this mod is meant to be installed by the whole server, so requests are paced rather than fired as
 * fast as the connection allows.
 *
 * <p>Permits may be reserved into the future, which turns concurrent callers into an orderly queue
 * instead of a thundering herd.
 */
public final class RateLimiter {
    private final double permitsPerSecond;
    private final double burst;

    private double tokens;
    private long lastRefillNanos;
    /** Set when the server pushes back, to hold every thread off at once. */
    private long resumeAtNanos;

    public RateLimiter(double permitsPerSecond, double burst) {
        this.permitsPerSecond = permitsPerSecond;
        this.burst = burst;
        this.tokens = burst;
        this.lastRefillNanos = System.nanoTime();
        this.resumeAtNanos = System.nanoTime();
    }

    /** Blocks until this caller is allowed to send one request. */
    public void acquire() throws InterruptedException {
        long sleepNanos;
        synchronized (this) {
            long now = System.nanoTime();
            double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(burst, tokens + elapsedSeconds * permitsPerSecond);

            long waitForTokenNanos = tokens >= 1.0
                    ? 0L
                    : (long) Math.ceil((1.0 - tokens) / permitsPerSecond * 1_000_000_000.0);
            tokens -= 1.0;

            long waitForPenaltyNanos = Math.max(0L, resumeAtNanos - now);
            sleepNanos = Math.max(waitForTokenNanos, waitForPenaltyNanos);
        }
        if (sleepNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(sleepNanos);
        }
    }

    /** Holds all callers off for at least {@code duration}, after the server answered 429. */
    public synchronized void backOff(Duration duration) {
        long until = System.nanoTime() + Math.max(0L, duration.toNanos());
        if (until > resumeAtNanos) {
            resumeAtNanos = until;
        }
        // Drain the bucket too, so the burst allowance does not immediately undo the penalty.
        tokens = Math.min(tokens, 0.0);
    }
}
