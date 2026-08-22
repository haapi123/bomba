package com.betterloka.grind;

import java.util.Locale;

/**
 * A countdown the player starts and reads off the screen.
 *
 * <p>Kept as an end time rather than a ticking counter, so it stays right whether or not anybody is
 * looking at it — a timer that only advances while its screen is open would be worse than none.
 */
public final class GrindTimer {
    private final long durationMillis;
    private volatile long endsAt;

    public GrindTimer(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /** Starts, or restarts, the countdown from now. */
    public void start() {
        endsAt = System.currentTimeMillis() + durationMillis;
    }

    public void stop() {
        endsAt = 0;
    }

    public boolean running() {
        return endsAt > 0 && remainingMillis() > 0;
    }

    /** True once a started timer has run out, until it is stopped or restarted. */
    public boolean finished() {
        return endsAt > 0 && remainingMillis() <= 0;
    }

    public long remainingMillis() {
        return Math.max(0, endsAt - System.currentTimeMillis());
    }

    /** How far through, 0 to 1, for a progress bar. */
    public float progress() {
        if (endsAt == 0 || durationMillis <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, 1f - (float) remainingMillis() / durationMillis));
    }

    /** {@code 16:43}, or {@code 0:00} once it has run out. */
    public String remainingText() {
        long seconds = (remainingMillis() + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    public long durationMillis() {
        return durationMillis;
    }
}
