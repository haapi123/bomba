package com.betterloka.towns;

import com.betterloka.BetterLoka;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The history of every {@code /town info} panel this client has seen.
 *
 * <p>Loka deletes a town once it has had no active members for a month, so the deletion date is
 * knowable — but only by somebody who watched the count reach zero. Nothing published says when that
 * happened, so this remembers each reading and the run of zeroes is measured from the first one it
 * saw. That makes every estimate a floor: a town could have been at zero for weeks before anybody
 * opened its panel, and the countdown here starts the day the mod first looked.
 */
public final class TownActivityStore {
    /** Loka removes a town after this long with no active members. */
    public static final int DAYS_AT_ZERO_BEFORE_DELETION = 30;

    /** Plenty for watching a shortlist of towns for months, without the file growing unbounded. */
    private static final int MAX_READINGS_PER_TOWN = 400;

    private static final long DAY_MILLIS = 24 * 60 * 60 * 1000L;

    private static final Type PAYLOAD =
            new TypeToken<Map<String, List<TownInfoReading>>>() { }.getType();

    /**
     * Never expires.
     *
     * <p>Every other store here is a cache of something downloadable. This is the opposite: a
     * reading nobody took again cannot be re-fetched from anywhere, and a run of zeroes is only
     * worth anything if the old end of it survives.
     */
    private static final long FOREVER = Long.MAX_VALUE;

    private final JsonStore<Map<String, List<TownInfoReading>>> store;

    /** Keyed by lower-case town name: Loka's panel and its API do not always agree on case. */
    private final Map<String, List<TownInfoReading>> readings = new LinkedHashMap<>();

    public TownActivityStore(Path file) {
        this.store = new JsonStore<>(file, JsonStore.envelopeOf(PAYLOAD), FOREVER);
        load();
    }

    private void load() {
        Map<String, List<TownInfoReading>> saved = store.read();
        if (saved != null) {
            saved.forEach((town, list) -> {
                if (list != null) {
                    readings.put(town, new ArrayList<>(list));
                }
            });
        }
    }

    /**
     * Records a reading, unless it says the same thing as the last one from today.
     *
     * <p>Opening the same panel five times in a minute is one fact, not five, and the file is meant
     * to hold months of a town's history rather than an afternoon of clicking.
     *
     * @return true if this reading was new information
     */
    public synchronized boolean record(TownInfoReading reading) {
        if (reading == null || reading.townName() == null || reading.townName().isBlank()) {
            return false;
        }
        List<TownInfoReading> history =
                readings.computeIfAbsent(key(reading.townName()), ignored -> new ArrayList<>());

        if (!history.isEmpty()) {
            TownInfoReading last = history.get(history.size() - 1);
            boolean sameDay = reading.readAt() - last.readAt() < DAY_MILLIS;
            if (sameDay && last.active() == reading.active() && last.members() == reading.members()) {
                return false;
            }
        }

        history.add(reading);
        while (history.size() > MAX_READINGS_PER_TOWN) {
            history.remove(0);
        }
        save();
        return true;
    }

    private void save() {
        try {
            store.write(readings);
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not save the town activity log", e);
        }
    }

    /** Every reading of one town, oldest first. */
    public synchronized List<TownInfoReading> history(String townName) {
        List<TownInfoReading> history = readings.get(key(townName));
        return history == null ? List.of() : List.copyOf(history);
    }

    public synchronized TownInfoReading latest(String townName) {
        List<TownInfoReading> history = readings.get(key(townName));
        return history == null || history.isEmpty() ? null : history.get(history.size() - 1);
    }

    /** Towns with any reading, most recently read first. */
    public synchronized List<String> watched() {
        List<String> names = new ArrayList<>();
        readings.forEach((key, history) -> {
            if (!history.isEmpty()) {
                names.add(history.get(history.size() - 1).townName());
            }
        });
        names.sort(Collections.reverseOrder(java.util.Comparator.comparingLong(
                name -> latest(name) == null ? 0L : latest(name).readAt())));
        return names;
    }

    /**
     * When the town was first seen with no active members, in the current run of zeroes.
     *
     * <p>Any reading above zero resets it: the town was alive that day, so whatever came before does
     * not count towards Loka's timer.
     *
     * @return epoch millis, or {@code 0} if the newest reading is not zero
     */
    public synchronized long zeroSince(String townName) {
        List<TownInfoReading> history = readings.get(key(townName));
        if (history == null || history.isEmpty()) {
            return 0L;
        }
        if (!history.get(history.size() - 1).atZero()) {
            return 0L;
        }
        long since = 0L;
        for (int i = history.size() - 1; i >= 0; i--) {
            TownInfoReading reading = history.get(i);
            if (!reading.atZero()) {
                break;
            }
            since = reading.readAt();
        }
        return since;
    }

    /**
     * The earliest day Loka could delete this town, going by the readings held.
     *
     * <p>An upper bound on the date, and so a late estimate: the count may have hit zero long before
     * the first reading, which would bring the real date forward.
     *
     * @return epoch millis, or {@code 0} if the town is not currently at zero
     */
    public synchronized long deletionNoEarlierThan(String townName) {
        long since = zeroSince(townName);
        return since == 0L ? 0L : since + DAYS_AT_ZERO_BEFORE_DELETION * DAY_MILLIS;
    }

    /** How many whole days the town has been seen at zero. */
    public synchronized long daysAtZero(String townName) {
        long since = zeroSince(townName);
        return since == 0L ? 0L : (System.currentTimeMillis() - since) / DAY_MILLIS;
    }

    private static String key(String townName) {
        return townName.toLowerCase(Locale.ROOT);
    }
}
