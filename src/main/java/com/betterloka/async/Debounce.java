package com.betterloka.async;

import java.util.Objects;

/**
 * Holds back a value until typing stops, and refuses to hand out the same one twice in a row.
 *
 * <p>Without this the Market fired a request per keystroke: "Diamond Sword" is thirteen of them,
 * twelve of which nobody ever sees the answer to, each holding a worker thread and a permit from a
 * budget every other screen shares.
 *
 * <p>Driven from {@code render} rather than a timer, so it needs no thread of its own and cannot
 * deliver anything while the view is not being drawn.
 */
public final class Debounce {
    private final long delayMillis;

    private String pending;
    private long dueAtMillis;
    private String lastTaken;
    private long lastTakenAtMillis;

    /** How long the same query is considered already answered, so a retype does not refetch. */
    private final long dedupeMillis;

    public Debounce(long delayMillis, long dedupeMillis) {
        this.delayMillis = delayMillis;
        this.dedupeMillis = dedupeMillis;
    }

    /** Notes what the field says now. Resets the clock if it changed. */
    public void offer(String value, long nowMillis) {
        if (Objects.equals(value, pending)) {
            return;
        }
        pending = value;
        dueAtMillis = nowMillis + delayMillis;
    }

    /**
     * @return the value once it has stood still for the delay and is not the one just taken,
     *         otherwise {@code null}
     */
    public String take(long nowMillis) {
        if (pending == null || nowMillis < dueAtMillis) {
            return null;
        }
        String value = pending;
        pending = null;
        if (Objects.equals(value, lastTaken) && nowMillis - lastTakenAtMillis < dedupeMillis) {
            return null;
        }
        lastTaken = value;
        lastTakenAtMillis = nowMillis;
        return value;
    }

    /** Drops anything waiting, for a view that is going away or has been answered another way. */
    public void clear() {
        pending = null;
    }

    /** Marks {@code value} as already handled, so an identical one is not fetched again. */
    public void accept(String value, long nowMillis) {
        lastTaken = value;
        lastTakenAtMillis = nowMillis;
        pending = null;
    }
}
