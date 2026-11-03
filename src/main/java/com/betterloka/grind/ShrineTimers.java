package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * When each shrine comes back, and the names the server has been seen to use for them.
 *
 * <p>Kept on disk as wall-clock times, so a timer started before logging off is still right after
 * logging back in: a shrine purged at 20:00 is back at 20:15 whether or not the game was running.
 */
public final class ShrineTimers {
    public enum State {
        /** Never seen purged, so nothing is known about it. */
        UNKNOWN,
        /** Purged and counting down. */
        COOLDOWN,
        /** Its cooldown has run out. */
        READY
    }

    private record Saved(Map<String, Long> endsAt, Map<String, String> learned) {
    }

    private static final Type SAVED = new TypeToken<Saved>() { }.getType();

    private final JsonStore<Saved> store;
    private final Map<String, Long> endsAt = new HashMap<>();
    private final Map<String, String> learned = new HashMap<>();

    public ShrineTimers(Path file) {
        this.store = new JsonStore<>(file, JsonStore.envelopeOf(SAVED), Long.MAX_VALUE);
        Saved saved = store.read();
        if (saved != null) {
            if (saved.endsAt() != null) {
                endsAt.putAll(saved.endsAt());
            }
            if (saved.learned() != null) {
                learned.putAll(saved.learned());
            }
        }
    }

    /** Starts the shrine's cooldown from now: it was just purged. */
    public synchronized void purged(Shrine shrine, long now) {
        endsAt.put(shrine.id(), now + Shrines.COOLDOWN_MILLIS);
        save();
    }

    /** Forgets the shrine's timer, for one started by mistake. */
    public synchronized void clear(Shrine shrine) {
        if (endsAt.remove(shrine.id()) != null) {
            save();
        }
    }

    public synchronized State state(Shrine shrine, long now) {
        Long end = endsAt.get(shrine.id());
        if (end == null) {
            return State.UNKNOWN;
        }
        return now < end ? State.COOLDOWN : State.READY;
    }

    /** Milliseconds left on the cooldown; zero when it is not counting. */
    public synchronized long remaining(Shrine shrine, long now) {
        Long end = endsAt.get(shrine.id());
        return end == null ? 0 : Math.max(0, end - now);
    }

    /** When the cooldown ends, as a wall-clock time, or zero when there is none. */
    public synchronized long endsAt(Shrine shrine) {
        return endsAt.getOrDefault(shrine.id(), 0L);
    }

    /**
     * Remembers which shrine a name the server used belongs to. A name already tied to a different
     * shrine is one the server uses for several, and is marked as such rather than moved.
     */
    public synchronized void learn(String name, Shrine shrine) {
        String key = Shrines.key(name);
        String before = learned.get(key);
        String after = before == null || before.equals(shrine.id()) ? shrine.id() : Shrines.SEVERAL;
        if (!after.equals(before)) {
            learned.put(key, after);
            save();
        }
    }

    public synchronized Map<String, String> learned() {
        return Map.copyOf(learned);
    }

    /** {@code 12:31}, or {@code 0:05}: minutes and seconds, which is all a fifteen minute wait needs. */
    public static String clock(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void save() {
        try {
            store.write(new Saved(Map.copyOf(endsAt), Map.copyOf(learned)));
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not save the shrine timers", e);
        }
    }
}
