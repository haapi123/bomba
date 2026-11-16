package com.betterloka.duel;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's ping over a duel, as the server publishes it in the player list.
 *
 * <p>That is the only ping a client can see of somebody else, and it is coarse. The server averages
 * it itself — vanilla measures every fifteen seconds and smooths ({@code (old × 3 + new) / 4}), Paper
 * keeps a few seconds' average — and sends everybody's to everybody only every thirty seconds; a
 * player who has just joined a server shows 0 until they have been measured. So this keeps every value
 * the server sent, and when it arrived — a 0 is "not measured yet" and is skipped — and works out from
 * them what can honestly be said: the average over
 * the duel (each value counting for as long as it stood), the highest, and each <b>spike</b>, a change
 * of {@value #SPIKE_MS} ms or more from the value before, up or down: how many, which way, the
 * biggest, when, and how far apart. How many readings all that rests on is part of the answer.
 */
public final class PingTrace {
    /** A change this big from one reading to the next is a spike. */
    public static final int SPIKE_MS = 20;

    private record Reading(long at, int ms) {
    }

    /** One spike: {@code change} ms (up if positive), {@code afterMillis} into the duel. */
    public record Spike(long afterMillis, int change) {
    }

    /**
     * What the duel's readings come to.
     *
     * @param everySeconds the average time between spikes, or -1 with fewer than two
     * @param biggest      the largest change, signed, or 0 with no spikes
     */
    public record Summary(int readings, int average, int max, List<Spike> spikes, int up, int down, int biggest,
                          double everySeconds) {
        public int spikeCount() {
            return spikes.size();
        }
    }

    private final List<Reading> readings = new ArrayList<>();

    /** One look at the player list; only a measured value the server has changed is kept. */
    public void sample(long now, int ms) {
        if (ms <= 0) {
            return;
        }
        if (readings.isEmpty() || readings.get(readings.size() - 1).ms() != ms) {
            readings.add(new Reading(now, ms));
        }
    }

    public boolean isEmpty() {
        return readings.isEmpty();
    }

    /**
     * The ping between {@code from} and {@code to}: the value standing at the start, and every one the
     * server sent until the end. When the first value came only after the start, it stands in for the
     * time before it — the best there is.
     *
     * @return {@code null} when nothing was ever read
     */
    public Summary summary(long from, long to) {
        if (readings.isEmpty()) {
            return null;
        }
        if (to <= from) {
            to = from + 1;
        }
        // The value in force when the duel began: the last one before it, or failing that the first.
        int start = 0;
        for (int i = 0; i < readings.size(); i++) {
            if (readings.get(i).at() <= from) {
                start = i;
            }
        }
        List<Reading> window = new ArrayList<>();
        window.add(new Reading(from, readings.get(start).ms()));
        for (int i = start + 1; i < readings.size(); i++) {
            Reading reading = readings.get(i);
            if (reading.at() > from && reading.at() <= to) {
                window.add(reading);
            }
        }

        double weighted = 0;
        int max = 0;
        List<Spike> spikes = new ArrayList<>();
        int up = 0;
        int down = 0;
        int biggest = 0;
        for (int i = 0; i < window.size(); i++) {
            Reading reading = window.get(i);
            long until = i + 1 < window.size() ? window.get(i + 1).at() : to;
            weighted += (double) reading.ms() * (until - reading.at());
            max = Math.max(max, reading.ms());
            if (i == 0) {
                continue;
            }
            int change = reading.ms() - window.get(i - 1).ms();
            if (Math.abs(change) < SPIKE_MS) {
                continue;
            }
            spikes.add(new Spike(reading.at() - from, change));
            if (change > 0) {
                up++;
            } else {
                down++;
            }
            if (Math.abs(change) > Math.abs(biggest)) {
                biggest = change;
            }
        }
        int average = (int) Math.round(weighted / (to - from));
        double every = spikes.size() >= 2
                ? (spikes.get(spikes.size() - 1).afterMillis() - spikes.get(0).afterMillis()) / 1000.0 / (spikes.size() - 1)
                : -1;
        return new Summary(window.size(), average, max, List.copyOf(spikes), up, down, biggest, every);
    }
}
