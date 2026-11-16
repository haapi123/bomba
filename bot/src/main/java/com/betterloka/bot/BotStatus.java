package com.betterloka.bot;

/**
 * How the fallen-town watch is doing, for {@code /test} to report: when it last looked at Loka and
 * whether that worked, when it last swept everything, and when it last told anybody anything.
 *
 * <p>Written by the watch thread and read by whoever runs the command, so every field is volatile and
 * each is meaningful on its own.
 */
public final class BotStatus {
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean watching;
    private volatile long lastCheckAt;
    private volatile boolean lastCheckOk;
    private volatile String lastCheckError;
    private volatile long lastSweepAt;
    private volatile int deletedCount = -1;
    private volatile long lastAnnouncedAt;
    private volatile String lastAnnounced;
    private volatile long lastPostErrorAt;
    private volatile String lastPostError;

    public void watchStarted() {
        watching = true;
    }

    public void checked(boolean sweptFully, int deleted) {
        long now = System.currentTimeMillis();
        lastCheckAt = now;
        lastCheckOk = true;
        lastCheckError = null;
        deletedCount = deleted;
        if (sweptFully) {
            lastSweepAt = now;
        }
    }

    public void checkFailed(String error) {
        lastCheckAt = System.currentTimeMillis();
        lastCheckOk = false;
        lastCheckError = error;
    }

    public void announced(String what) {
        lastAnnouncedAt = System.currentTimeMillis();
        lastAnnounced = what;
    }

    public void postFailed(String error) {
        lastPostErrorAt = System.currentTimeMillis();
        lastPostError = error;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean watching() {
        return watching;
    }

    public long lastCheckAt() {
        return lastCheckAt;
    }

    public boolean lastCheckOk() {
        return lastCheckOk;
    }

    public String lastCheckError() {
        return lastCheckError;
    }

    public long lastSweepAt() {
        return lastSweepAt;
    }

    public int deletedCount() {
        return deletedCount;
    }

    public long lastAnnouncedAt() {
        return lastAnnouncedAt;
    }

    public String lastAnnounced() {
        return lastAnnounced;
    }

    public long lastPostErrorAt() {
        return lastPostErrorAt;
    }

    public String lastPostError() {
        return lastPostError;
    }
}
