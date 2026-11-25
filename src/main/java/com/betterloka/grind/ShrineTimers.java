package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * When the player started each shrine's timer, and what each shrine has given them.
 *
 * <p>A timer only ever starts from the player's own click on the shrine's Start button: nothing the
 * server says starts one.
 *
 * <p>Kept on disk as wall-clock times, so a timer started before logging off is still right after
 * logging back in: one started at 20:00 is done at 20:20 whether or not the game was running.
 *
 * <p>What is kept is when a timer was started, not when it ends: the cooldown is the player's
 * setting, and changing it — to the right number, once they know it — moves the timers already
 * running along with it.
 */
public final class ShrineTimers {
    public enum State {
        /** No timer started, so nothing is known about it. */
        UNKNOWN,
        /** Started and counting down. */
        COOLDOWN,
        /** Its cooldown has run out. */
        READY
    }

    /**
     * What goes in the file. {@code endsAt} is what an earlier version kept, read once and turned
     * into a start time; {@code purgedAt} keeps its old name so files from before 0.65 still load.
     */
    private record Saved(Map<String, Long> endsAt, Map<String, Long> purgedAt, Map<String, ShrineLoot.Stats> stats) {
    }

    private static final Type SAVED = new TypeToken<Saved>() { }.getType();

    private final JsonStore<Saved> store;
    private final LongSupplier cooldown;
    private final Map<String, Long> purgedAt = new HashMap<>();
    private final Map<String, ShrineLoot.Stats> stats = new HashMap<>();

    /** With the default twenty minute cooldown. */
    public ShrineTimers(Path file) {
        this(file, () -> Shrines.COOLDOWN_MILLIS);
    }

    /** @param cooldown how long a shrine takes to come back, asked each time so a change applies at once */
    public ShrineTimers(Path file, LongSupplier cooldown) {
        this.store = new JsonStore<>(file, JsonStore.envelopeOf(SAVED), Long.MAX_VALUE);
        this.cooldown = cooldown;
        Saved saved = store.read();
        if (saved != null) {
            if (saved.endsAt() != null) {
                saved.endsAt().forEach((id, end) -> purgedAt.put(id, end - Shrines.LEGACY_COOLDOWN_MILLIS));
            }
            if (saved.purgedAt() != null) {
                purgedAt.putAll(saved.purgedAt());
            }
            if (saved.stats() != null) {
                stats.putAll(saved.stats());
            }
        }
    }

    /** How long a shrine takes to come back, as set now. */
    public long cooldownMillis() {
        return Math.max(1000, cooldown.getAsLong());
    }

    /** Starts the shrine's timer from now, as the player asked. */
    public synchronized void start(Shrine shrine, long now) {
        purgedAt.put(shrine.id(), now);
        save();
    }

    /** Forgets the shrine's timer, for one started by mistake. */
    public synchronized void clear(Shrine shrine) {
        if (purgedAt.remove(shrine.id()) != null) {
            save();
        }
    }

    public synchronized State state(Shrine shrine, long now) {
        long end = endsAt(shrine);
        if (end == 0) {
            return State.UNKNOWN;
        }
        return now < end ? State.COOLDOWN : State.READY;
    }

    /** Milliseconds left on the cooldown; zero when it is not counting. */
    public synchronized long remaining(Shrine shrine, long now) {
        long end = endsAt(shrine);
        return end == 0 ? 0 : Math.max(0, end - now);
    }

    /** When the cooldown ends, as a wall-clock time, or zero when there is none. */
    public synchronized long endsAt(Shrine shrine) {
        Long at = purgedAt.get(shrine.id());
        return at == null ? 0 : at + cooldownMillis();
    }

    /** Adds one finished run's haul to the shrine's statistics. */
    public synchronized void recordRun(Shrine shrine, Map<String, Integer> gained, long at) {
        stats.put(shrine.id(), stats(shrine).plus(gained, at));
        save();
    }

    public synchronized ShrineLoot.Stats stats(Shrine shrine) {
        return stats.getOrDefault(shrine.id(), ShrineLoot.Stats.EMPTY);
    }

    public synchronized void clearStats(Shrine shrine) {
        if (stats.remove(shrine.id()) != null) {
            save();
        }
    }

    /** {@code 12:31}, or {@code 0:05}: minutes and seconds, which is all a twenty minute wait needs. */
    public static String clock(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void save() {
        try {
            store.write(new Saved(null, Map.copyOf(purgedAt), Map.copyOf(stats)));
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not save the shrine timers", e);
        }
    }
}
