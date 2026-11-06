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

    /**
     * Background work's own, slower pace, on top of the shared one.
     *
     * <p>api.lokamc.com sits behind several nodes, each with a bucket of thirty that refills at
     * about one a second — measured: one node's {@code x-rate-limit-remaining} fell steadily from 29
     * to 2 under four requests a second while the others stayed full, and a 429 asks for up to 33
     * seconds. A sweep at the shared pace drained a node in seconds, and the wait it then asked for
     * landed on whatever a player clicked next. Paced below the refill, background work leaves the
     * buckets for the clicks.
     */
    private final double backgroundPermitsPerSecond;
    private double backgroundTokens;
    private long backgroundRefillNanos;
    /** Set when the server pushes back on background work: only background work waits it out. */
    private long backgroundResumeAtNanos;

    public RateLimiter(double permitsPerSecond, double burst) {
        this(permitsPerSecond, burst, permitsPerSecond);
    }

    public RateLimiter(double permitsPerSecond, double burst, double backgroundPermitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        this.burst = burst;
        this.tokens = burst;
        this.lastRefillNanos = System.nanoTime();
        this.resumeAtNanos = System.nanoTime();
        this.backgroundPermitsPerSecond = backgroundPermitsPerSecond;
        this.backgroundTokens = backgroundBurst();
        this.backgroundRefillNanos = System.nanoTime();
        this.backgroundResumeAtNanos = System.nanoTime();
    }

    private double backgroundBurst() {
        return Math.max(1.0, backgroundPermitsPerSecond);
    }

    /**
     * The longest a 429 holds back a request somebody is waiting on.
     *
     * <p>The server's own retry-after is for the node that said it, and the next request may well
     * land on another with room to spare; a player's click waits a moment and tries, rather than
     * sitting out half a minute that only one node asked for.
     */
    static final long INTERACTIVE_BACKOFF_CAP_MILLIS = 1500;

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

    /**
     * How much of the server's own budget background work leaves alone.
     *
     * <p>api.lokamc.com counts down {@code x-rate-limit-remaining} from about thirty, shared by
     * everything the client sends. Several sweeps at once could spend it to nought and draw a 429 —
     * and a 429 holds every request to the host off, the one a player is waiting on included.
     */
    private static final int SERVER_RESERVE = 10;

    /**
     * How long the lowest reading of the server's budget is kept.
     *
     * <p>The lowest, not the last: each node behind the host has its own bucket, and readings
     * alternate between a node that is nearly empty and one that is full. Trusting the last one let
     * a sweep see "28" half the time and run the empty node into a 429.
     */
    private static final long REMAINING_WINDOW_NANOS = 5_000_000_000L;

    private int lowestRemaining = Integer.MAX_VALUE;
    private long lowestRemainingAt;

    /** What the server last said was left of its budget. */
    public synchronized void observeRemaining(int remaining) {
        long now = System.nanoTime();
        if (remaining <= lowestRemaining || now - lowestRemainingAt > REMAINING_WINDOW_NANOS) {
            lowestRemaining = remaining;
            lowestRemainingAt = now;
        }
    }

    /** Whether the server's budget is low enough that background work should hold back. */
    private boolean serverBudgetLow(long now) {
        return lowestRemaining < SERVER_RESERVE && now - lowestRemainingAt < REMAINING_WINDOW_NANOS;
    }

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
                if (background && (tokens < INTERACTIVE_RESERVE || serverBudgetLow(now)
                        || now < backgroundResumeAtNanos || backgroundTokens < 1.0)) {
                    sleepNanos = -1;
                } else {
                    if (background) {
                        backgroundTokens -= 1.0;
                    }
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
        double backgroundElapsed = Math.max(0, now - backgroundRefillNanos) / 1_000_000_000.0;
        backgroundRefillNanos = now;
        backgroundTokens = Math.min(backgroundBurst(),
                backgroundTokens + backgroundElapsed * backgroundPermitsPerSecond);
    }

    /** Holds all callers off for at least {@code duration}, after the server answered 429. */
    public synchronized void backOff(Duration duration) {
        backOff(duration, false);
    }

    /**
     * After a 429. Background work sits out all of what the server asked for; requests somebody is
     * waiting on hold off for at most {@link #INTERACTIVE_BACKOFF_CAP_MILLIS}, and only when it was
     * one of theirs that was turned away — a sweep's 429 is the sweep's to wait out.
     */
    public synchronized void backOff(Duration duration, boolean background) {
        long now = System.nanoTime();
        long full = now + Math.max(0L, duration.toNanos());
        if (full > backgroundResumeAtNanos) {
            backgroundResumeAtNanos = full;
        }
        if (background) {
            return;
        }
        long capped = now + Math.min(Math.max(0L, duration.toNanos()), INTERACTIVE_BACKOFF_CAP_MILLIS * 1_000_000L);
        if (capped > resumeAtNanos) {
            resumeAtNanos = capped;
        }
        // Drain the bucket too, so the burst allowance does not immediately undo the penalty.
        tokens = Math.min(tokens, 0.0);
    }
}
