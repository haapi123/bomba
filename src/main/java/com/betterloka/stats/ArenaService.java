package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.ArenaApi;
import com.betterloka.api.ArenaApi.Ladder;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.ArenaRank;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One player's ranked 1v1 record, current and historical.
 *
 * <p>Loka publishes ladders, not players: every lookup would otherwise be the whole standings again,
 * so a ladder is fetched once and indexed for everyone. The current season is two requests and lands
 * in about a second; the "best rank ever" index is a season's final standings for every season on
 * both ladders — around forty requests — so it is built once in the background and the card fills in
 * when it arrives.
 *
 * <p>Players are followed by UUID because names change between seasons, with a name index kept
 * alongside for the case where Loka's own record of the account has gone and no UUID is known.
 */
public final class ArenaService {
    private static final long CURRENT_TTL_MILLIS = 5 * 60 * 1000L;

    /**
     * How long the best-rank index stays good on disk.
     *
     * <p>It is every past season's final table on both ladders — forty requests and about two
     * megabytes — and a finished season's result never changes again. Rebuilding it once a session
     * was most of what a first search cost; a day is well inside how often a season ends.
     */
    private static final long HISTORY_TTL_MILLIS = 24 * 60 * 60 * 1000L;

    /** The index in the shape it is saved as: ladder name to key to the best entry. */
    private record SavedBest(int season, String name, String uuid, int wins, int losses,
                             String rank, int position) {
    }

    private static final Type SAVED_TYPE = JsonStore.envelopeOf(
            new TypeToken<Map<String, Map<String, SavedBest>>>() {
            }.getType());

    /** The best rank a player reached, and the season they reached it in. */
    public record Best(int season, ArenaEntry entry) {
    }

    /** A player's standing on one ladder: where they are now, and their best season. */
    public record Standing(Ladder ladder, ArenaEntry current, Best best) {
        public boolean isEmpty() {
            return current == null && best == null;
        }
    }

    private final ArenaApi api;
    private final JsonStore<Map<String, Map<String, SavedBest>>> disk;

    private final Map<Ladder, List<ArenaEntry>> current = new EnumMap<>(Ladder.class);
    private volatile long currentLoadedAt;
    private volatile CompletableFuture<Void> currentLoad;

    private volatile CompletableFuture<Map<Ladder, Map<String, Best>>> history;

    public ArenaService(ArenaApi api) {
        this(api, null);
    }

    /** @param cacheFile where to keep the best-rank index between sessions, or {@code null} not to. */
    public ArenaService(ArenaApi api, Path cacheFile) {
        this.api = api;
        this.disk = cacheFile == null ? null : new JsonStore<>(cacheFile, SAVED_TYPE, HISTORY_TTL_MILLIS);
    }

    /**
     * Where the player stands right now, per ladder. Resolves once the two current ladders are in;
     * the {@code best} of each standing is filled only if the history index already happens to be
     * built, so this never waits on it.
     */
    public CompletableFuture<List<Standing>> current(String name, UUID uuid) {
        return loadCurrent().thenApply(ignored -> {
            Map<Ladder, Map<String, Best>> index = historyIfReady();
            List<Standing> standings = new ArrayList<>();
            for (Ladder ladder : Ladder.values()) {
                standings.add(new Standing(ladder, findCurrent(ladder, name, uuid),
                        index == null ? null : lookup(index.get(ladder), name, uuid)));
            }
            return standings;
        });
    }

    /** The best rank the player ever reached on each ladder, and when. Builds the index if needed. */
    public CompletableFuture<List<Standing>> best(String name, UUID uuid) {
        return loadHistory().thenApply(index -> {
            List<Standing> standings = new ArrayList<>();
            for (Ladder ladder : Ladder.values()) {
                standings.add(new Standing(ladder, findCurrent(ladder, name, uuid),
                        lookup(index.get(ladder), name, uuid)));
            }
            return standings;
        });
    }

    private ArenaEntry findCurrent(Ladder ladder, String name, UUID uuid) {
        List<ArenaEntry> rows;
        synchronized (current) {
            rows = current.get(ladder);
        }
        if (rows == null) {
            return null;
        }
        for (ArenaEntry entry : rows) {
            if (matches(entry, name, uuid)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean matches(ArenaEntry entry, String name, UUID uuid) {
        if (uuid != null && entry.uuid() != null) {
            return uuid.equals(entry.uuid());
        }
        return name != null && name.equalsIgnoreCase(entry.name());
    }

    private static Best lookup(Map<String, Best> index, String name, UUID uuid) {
        if (index == null) {
            return null;
        }
        Best byUuid = uuid == null ? null : index.get("u:" + uuid);
        if (byUuid != null) {
            return byUuid;
        }
        return name == null ? null : index.get("n:" + name.toLowerCase(Locale.ROOT));
    }

    private CompletableFuture<Void> loadCurrent() {
        CompletableFuture<Void> running = currentLoad;
        if (running != null && !running.isDone()) {
            return running;
        }
        if (System.currentTimeMillis() - currentLoadedAt < CURRENT_TTL_MILLIS && !current.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> load = CompletableFuture.runAsync(() -> {
            for (Ladder ladder : Ladder.values()) {
                try {
                    List<ArenaEntry> rows = api.fetchCurrent(ladder);
                    synchronized (current) {
                        current.put(ladder, rows);
                    }
                } catch (ApiException e) {
                    BetterLoka.LOGGER.debug("Could not load the {} ladder", ladder, e);
                }
            }
            currentLoadedAt = System.currentTimeMillis();
        }, api.executor());
        currentLoad = load;
        return load;
    }

    private Map<Ladder, Map<String, Best>> historyIfReady() {
        CompletableFuture<Map<Ladder, Map<String, Best>>> built = history;
        return built != null && built.isDone() && !built.isCompletedExceptionally() ? built.join() : null;
    }

    private CompletableFuture<Map<Ladder, Map<String, Best>>> loadHistory() {
        CompletableFuture<Map<Ladder, Map<String, Best>>> existing = history;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (history == null) {
                history = CompletableFuture.supplyAsync(this::buildHistory, api.bulkExecutor());
            }
            return history;
        }
    }

    /**
     * Indexes every season's final standings.
     *
     * <p>A season's weekly snapshots are cumulative, so its last published week is how it finished —
     * one leaderboard per season per ladder rather than one per week, which is the difference between
     * forty requests and several hundred.
     */
    private Map<Ladder, Map<String, Best>> buildHistory() {
        Map<Ladder, Map<String, Best>> fromDisk = readIndex();
        if (fromDisk != null) {
            return fromDisk;
        }

        Map<Ladder, Map<String, Best>> index = new EnumMap<>(Ladder.class);
        for (Ladder ladder : Ladder.values()) {
            index.put(ladder, new HashMap<>());
        }

        List<Integer> seasons;
        try {
            seasons = api.fetchSeasons();
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not list arena seasons", e);
            return index;
        }

        for (int season : seasons) {
            int week;
            try {
                List<Integer> weeks = api.fetchWeeks(season);
                if (weeks.isEmpty()) {
                    continue;
                }
                week = weeks.get(weeks.size() - 1);
            } catch (ApiException e) {
                BetterLoka.LOGGER.debug("Could not list weeks of arena season {}", season, e);
                continue;
            }
            for (Ladder ladder : Ladder.values()) {
                try {
                    record(index.get(ladder), season, api.fetchHistory(ladder, season, week));
                } catch (ApiException e) {
                    BetterLoka.LOGGER.debug("Could not load {} season {} week {}", ladder, season, week, e);
                }
            }
        }
        writeIndex(index);
        return index;
    }

    /** @return the saved index if one is still fresh, otherwise {@code null}. */
    private Map<Ladder, Map<String, Best>> readIndex() {
        if (disk == null) {
            return null;
        }
        Map<String, Map<String, SavedBest>> saved = disk.read();
        if (saved == null || saved.isEmpty()) {
            return null;
        }
        Map<Ladder, Map<String, Best>> index = new EnumMap<>(Ladder.class);
        for (Ladder ladder : Ladder.values()) {
            Map<String, Best> restored = new HashMap<>();
            Map<String, SavedBest> rows = saved.get(ladder.name());
            if (rows != null) {
                for (Map.Entry<String, SavedBest> row : rows.entrySet()) {
                    SavedBest value = row.getValue();
                    restored.put(row.getKey(), new Best(value.season(), new ArenaEntry(value.name(),
                            value.uuid() == null ? null : UUID.fromString(value.uuid()),
                            value.wins(), value.losses(), ArenaRank.parse(value.rank()), 0, 0,
                            value.position())));
                }
            }
            index.put(ladder, restored);
        }
        BetterLoka.LOGGER.debug("Loaded the arena history index from disk");
        return index;
    }

    private void writeIndex(Map<Ladder, Map<String, Best>> index) {
        if (disk == null) {
            return;
        }
        Map<String, Map<String, SavedBest>> saved = new HashMap<>();
        for (Map.Entry<Ladder, Map<String, Best>> ladder : index.entrySet()) {
            Map<String, SavedBest> rows = new HashMap<>();
            for (Map.Entry<String, Best> entry : ladder.getValue().entrySet()) {
                ArenaEntry best = entry.getValue().entry();
                rows.put(entry.getKey(), new SavedBest(entry.getValue().season(), best.name(),
                        best.uuid() == null ? null : best.uuid().toString(), best.wins(), best.losses(),
                        best.rank() == null ? null : best.rank().label(), best.position()));
            }
            saved.put(ladder.getKey().name(), rows);
        }
        disk.write(saved);
    }

    private static void record(Map<String, Best> index, int season, List<ArenaEntry> rows) {
        for (ArenaEntry entry : rows) {
            if (entry.rank() == null) {
                continue;
            }
            Best candidate = new Best(season, entry);
            if (entry.uuid() != null) {
                keepBetter(index, "u:" + entry.uuid(), candidate);
            }
            if (entry.name() != null) {
                keepBetter(index, "n:" + entry.name().toLowerCase(Locale.ROOT), candidate);
            }
        }
    }

    /** Ties go to the earlier season: the first time they reached that rank is the one worth naming. */
    private static void keepBetter(Map<String, Best> index, String key, Best candidate) {
        Best held = index.get(key);
        if (held == null) {
            index.put(key, candidate);
            return;
        }
        ArenaRank a = candidate.entry().rank();
        ArenaRank b = held.entry().rank();
        int order = a.compareTo(b);
        if (order > 0 || (order == 0 && candidate.season() < held.season())) {
            index.put(key, candidate);
        }
    }
}
