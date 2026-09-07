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
 *
 * <p>Background work yields to what a player is waiting on. The Town Logger's sweep and the ranked
 * history are tens of requests and megabytes apiece; without this they sit in the same queue as a
 * search and the screen looks broken while a sweep nobody asked for finishes.
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

    /** How long a background caller waits at the back of the queue before taking its permit. */
    private static final long BACKGROUND_YIELD_MILLIS = 120;

    /**
     * How many permits background work must leave in the bucket.
     *
     * <p>Yielding for a moment was not enough. Permits may be reserved into the future, so a sweep
     * with a hundred pages to fetch could take the bucket a hundred permits into debt in a burst,
     * and an interactive request arriving a moment later inherited the whole of that debt as its own
     * wait — measured at nineteen seconds for the last of two hundred callers. Holding a floor for
     * work somebody is watching bounds that wait at roughly one request.
     */
    private static final double INTERACTIVE_RESERVE = 3.0;

    /** How long a background caller sleeps before looking at the bucket again. */
    private static final long BACKGROUND_RETRY_MILLIS = 60;

    /** Blocks until this caller is allowed to send one request. */
    public void acquire() throws InterruptedException {
        acquire(false);
    }

    /**
     * @param background true for work nobody is watching, which then hangs back so an interactive
     *                   request arriving at the same moment goes first
     */
    public void acquire(boolean background) throws InterruptedException {
        if (background) {
            // Deliberately outside the lock: the point is to leave the bucket alone for a moment,
            // not to hold every other caller off while waiting.
            TimeUnit.MILLISECONDS.sleep(BACKGROUND_YIELD_MILLIS);
        }
        while (true) {
            long sleepNanos;
            synchronized (this) {
                refill();
                long now = System.nanoTime();
                // Background work waits for a real permit rather than reserving one it has not got,
                // so it can never put an interactive caller into its debt.
                if (background && tokens < INTERACTIVE_RESERVE) {
                    sleepNanos = -1;
                } else {
                    long waitForTokenNanos = tokens >= 1.0
                            ? 0L
                            : (long) Math.ceil((1.0 - tokens) / permitsPerSecond * 1_000_000_000.0);
                    tokens -= 1.0;

                    long waitForPenaltyNanos = Math.max(0L, resumeAtNanos - now);
                    sleepNanos = Math.max(waitForTokenNanos, waitForPenaltyNanos);
                }
            }
            if (sleepNanos < 0) {
                TimeUnit.MILLISECONDS.sleep(BACKGROUND_RETRY_MILLIS);
                continue;
            }
            if (sleepNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            }
            return;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        tokens = Math.min(burst, tokens + elapsedSeconds * permitsPerSecond);
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
