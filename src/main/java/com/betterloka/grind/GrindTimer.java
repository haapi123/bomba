package com.betterloka.grind;

import java.util.Locale;

/**
 * A countdown the player starts and reads off the screen.
 *
 * <p>Kept as an end time rather than a ticking counter, so it stays right whether or not anybody is
 * looking at it — a timer that only advances while its screen is open would be worse than none.
 */
public final class GrindTimer {
    /**
     * Where a timer is in its cycle.
     *
     * <p>A countdown that has run out is {@link #IDLE} again, not finished-and-stuck: the next
     * thing that would start it should start it. That distinction is the whole point of naming the
     * state — the shulker timer used to restart on every kill, so a good run of shulkers meant the
     * countdown never actually counted down.
     */
    public enum Cycle {
        /** Not counting: never started, stopped, or run out. Ready to begin a cycle. */
        IDLE,
        /** Counting down. Anything that would start it again is ignored. */
        RUNNING
    }

    private volatile long durationMillis;
    private volatile long endsAt;

    public GrindTimer(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    /** Changes how long the countdown runs for. A running timer is left alone until restarted. */
    public void setDurationMillis(long durationMillis) {
        this.durationMillis = Math.max(1000L, durationMillis);
    }

    /** Starts, or restarts, the countdown from now. What a keybind press does. */
    public void start() {
        endsAt = System.currentTimeMillis() + durationMillis;
    }

    public Cycle cycle() {
        return running() ? Cycle.RUNNING : Cycle.IDLE;
    }

    /**
     * Begins a cycle only if one is not already under way.
     *
     * <p>What an automatic trigger calls. A shulker killed while the countdown is running is not a
     * reason to start it over — the twenty minutes are counting down to when the next one spawns,
     * and killing another shulker in the meantime does not move that moment.
     *
     * @return true if this call began a new cycle
     */
    public boolean startIfIdle() {
        if (cycle() == Cycle.RUNNING) {
            return false;
        }
        start();
        return true;
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
        return format(remainingMillis());
    }

    /** The full length, for showing what a fresh start would count down from. */
    public String durationText() {
        return format(durationMillis);
    }

    /** {@code 2:59:41} past an hour, {@code 16:43} below it — three hours as 179:41 reads as noise. */
    private static String format(long millis) {
        long seconds = (millis + 999) / 1000;
        long hours = seconds / 3600;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    public long durationMillis() {
        return durationMillis;
    }
}
