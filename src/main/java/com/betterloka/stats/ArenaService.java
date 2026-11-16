package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.ArenaApi;
import com.betterloka.api.ArenaApi.Ladder;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.ArenaRank;
import com.betterloka.api.model.LokaPlayer;
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

    /** The {@link Best#season()} of a rank held in the season being played now. */
    public static final int THIS_SEASON = 0;

    /** Wins and losses added up over seasons, and how many seasons that is. */
    public record Totals(int wins, int losses, int seasons) {
        public static final Totals NONE = new Totals(0, 0, 0);

        public int games() {
            return wins + losses;
        }

        Totals plus(int moreWins, int moreLosses) {
            return moreWins + moreLosses == 0 ? this : new Totals(wins + moreWins, losses + moreLosses, seasons + 1);
        }
    }

    /**
     * A player's ranked record on one ladder: where they are now, the highest rank they ever held —
     * this season's counts too, with {@link #THIS_SEASON} — how the last <em>finished</em> season
     * ended for them, and their wins and losses over every season Loka has a table of plus this one.
     *
     * @param season     the number of the season being played now
     * @param lastNumber the number of the last finished season
     */
    public record Career(Ladder ladder, ArenaEntry current, Best highest, Best lastSeason, Totals career,
                         int season, int lastNumber) {
        public boolean isEmpty() {
            return current == null && highest == null && lastSeason == null && career.games() == 0;
        }
    }

    /**
     * Which seasons the history index's latest one is. Loka publishes a season's table week by week
     * while it is played, so the latest season in the history is usually the one going on now — its
     * last week a snapshot, not how it ended — and the last finished one is the season before.
     */
    record Seasons(int latest, int previous, boolean latestOngoing) {
        int thisSeason() {
            return latestOngoing ? latest : latest + 1;
        }

        int lastFinished() {
            return latestOngoing ? previous : latest;
        }
    }

    private final ArenaApi api;
    private final JsonStore<Map<String, Map<String, SavedBest>>> disk;

    private final Map<Ladder, List<ArenaEntry>> current = new EnumMap<>(Ladder.class);
    private volatile long currentLoadedAt;
    private volatile CompletableFuture<Void> currentLoad;

    private volatile CompletableFuture<Map<Ladder, Map<String, Best>>> history;

    /**
     * Whether the index being served came back whole. One that did not — a season that failed to
     * load — is used for now but neither saved nor kept: the next search builds it again, rather
     * than a player's best season going missing for the rest of the session and, once written to
     * disk, the day after.
     */
    private volatile boolean historyComplete;

    /** Every name each UUID has appeared under across the snapshots, in the order they were read. */
    private final Map<UUID, List<String>> seenNames = new HashMap<>();

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

    /**
     * Names this UUID has gone by before, oldest first.
     *
     * <p>Read out of the past seasons' ladder tables, which record whatever the player was called at
     * the time — Mojang stopped publishing name history in 2022 and Loka's own record keeps only the
     * current name, so this is the only source left. It therefore only knows the names of players who
     * have been on a ranked ladder.
     */
    public CompletableFuture<List<String>> previousNames(String currentName, UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return loadHistory().thenApply(ignored -> {
            List<String> names = new ArrayList<>();
            synchronized (seenNames) {
                for (String name : seenNames.getOrDefault(uuid, List.of())) {
                    if (!name.equalsIgnoreCase(currentName) && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
            return List.copyOf(names);
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

    /**
     * The duel summary's look at an opponent: on each ladder, now, at best, and last season. Waits
     * for both the current ladders and the history index, which is usually built by then.
     */
    public CompletableFuture<List<Career>> careers(String name, UUID uuid) {
        return careers(name, uuid, Map.of());
    }

    /**
     * The same, with this season's record from the player's own profile where there is one: the
     * ladder tables list only the top five hundred, and only after ten games, while a player's own
     * record has every game of theirs.
     */
    public CompletableFuture<List<Career>> careers(String name, UUID uuid, Map<Ladder, LokaPlayer.Ranked> own) {
        return loadCurrent().thenCombine(loadHistory(), (ignored, index) -> {
            Seasons seasons = seasons(index);
            List<Career> careers = new ArrayList<>();
            for (Ladder ladder : Ladder.values()) {
                ArenaEntry row = findCurrent(ladder, name, uuid);
                LokaPlayer.Ranked mine = own == null ? null : own.get(ladder);
                if (mine != null && (mine.games() > 0 || mine.rank() != null)) {
                    row = new ArenaEntry(name, uuid, mine.wins(), mine.losses(), mine.rank(), mine.streak(), 0,
                            row == null ? 0 : row.position());
                }
                careers.add(career(ladder, row, index.get(ladder), name, uuid, seasons));
            }
            return careers;
        });
    }

    /** One ladder's {@link Career}, out of this season's row and the history index. */
    static Career career(Ladder ladder, ArenaEntry current, Map<String, Best> index, String name, UUID uuid,
                         Seasons seasons) {
        Best best = lookup(index, "", name, uuid);
        Best latest = lookup(index, "l", name, uuid);
        if (seasons.latestOngoing() && best != null && best.season() == seasons.latest()) {
            // Its best was this season's table so far: this season.
            best = new Best(THIS_SEASON, best.entry());
        }
        Best last = seasons.latestOngoing() ? lookup(index, "p", name, uuid) : latest;
        Best finished = lookup(index, "c", name, uuid);
        Totals career = finished == null ? Totals.NONE
                : new Totals(finished.entry().wins(), finished.entry().losses(), finished.season());
        if (!seasons.latestOngoing() && latest != null) {
            career = career.plus(latest.entry().wins(), latest.entry().losses());
        }
        if (current != null) {
            career = career.plus(current.wins(), current.losses());
        }
        return new Career(ladder, current, highest(current, best), last, career, seasons.thisSeason(),
                seasons.lastFinished());
    }

    private volatile Seasons seasonsCache;
    private volatile Object seasonsCacheKey;

    /** Works out once per index and current-ladder load which season is going on. */
    private Seasons seasons(Map<Ladder, Map<String, Best>> index) {
        Object key = List.of(System.identityHashCode(index), currentLoadedAt);
        Seasons held = seasonsCache;
        if (held != null && key.equals(seasonsCacheKey)) {
            return held;
        }
        int latest = -1;
        int previous = -1;
        for (Map<String, Best> rows : index.values()) {
            for (Map.Entry<String, Best> entry : rows.entrySet()) {
                if (latest < 0 && entry.getKey().startsWith("lu:")) {
                    latest = entry.getValue().season();
                } else if (previous < 0 && entry.getKey().startsWith("pu:")) {
                    previous = entry.getValue().season();
                }
                if (latest >= 0 && previous >= 0) {
                    break;
                }
            }
        }
        Map<Ladder, List<ArenaEntry>> now = new EnumMap<>(Ladder.class);
        synchronized (current) {
            now.putAll(current);
        }
        Seasons worked = new Seasons(latest, previous, latestOngoing(index, now));
        seasonsCache = worked;
        seasonsCacheKey = key;
        return worked;
    }

    /**
     * Whether the latest season in the history is the one being played: then everybody's record now
     * is their record in its last weekly table plus whatever they have played since — never less. A
     * new season starts everybody again from nothing, so most would have fewer games now than then.
     * Without enough players on both to tell, it is taken to be going on, which is what it nearly
     * always is.
     */
    static boolean latestOngoing(Map<Ladder, Map<String, Best>> index, Map<Ladder, List<ArenaEntry>> now) {
        int common = 0;
        int kept = 0;
        for (Map.Entry<Ladder, List<ArenaEntry>> ladder : now.entrySet()) {
            Map<String, Best> rows = index.get(ladder.getKey());
            if (rows == null) {
                continue;
            }
            for (ArenaEntry entry : ladder.getValue()) {
                Best then = entry.uuid() == null ? null : rows.get("lu:" + entry.uuid());
                if (then == null) {
                    continue;
                }
                common++;
                if (entry.wins() >= then.entry().wins() && entry.losses() >= then.entry().losses()) {
                    kept++;
                }
            }
        }
        return common < 20 || kept >= common * 0.9;
    }

    /** The higher of this season's rank and the best of the past ones; a tie goes to the past. */
    static Best highest(ArenaEntry current, Best past) {
        if (current == null || current.rank() == null) {
            return past;
        }
        if (past == null || current.rank().compareTo(past.entry().rank()) > 0) {
            return new Best(THIS_SEASON, current);
        }
        return past;
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
        return lookup(index, "", name, uuid);
    }

    /**
     * @param kind {@code ""} for the best season, {@code "l"} for the last one: the index keeps both,
     *             under {@code u:}/{@code n:} and {@code lu:}/{@code ln:}
     */
    private static Best lookup(Map<String, Best> index, String kind, String name, UUID uuid) {
        if (index == null) {
            return null;
        }
        Best byUuid = uuid == null ? null : index.get(kind + "u:" + uuid);
        if (byUuid != null) {
            return byUuid;
        }
        return name == null ? null : index.get(kind + "n:" + name.toLowerCase(Locale.ROOT));
    }

    /** Fetches both ladders at once — they are independent, and one after the other doubled the wait. */
    private synchronized CompletableFuture<Void> loadCurrent() {
        CompletableFuture<Void> running = currentLoad;
        if (running != null && !running.isDone()) {
            return running;
        }
        if (System.currentTimeMillis() - currentLoadedAt < CURRENT_TTL_MILLIS && !current.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<Boolean>> ladders = new ArrayList<>();
        for (Ladder ladder : Ladder.values()) {
            ladders.add(CompletableFuture.supplyAsync(() -> {
                try {
                    List<ArenaEntry> rows = api.fetchCurrent(ladder);
                    rememberNames(rows);
                    synchronized (current) {
                        current.put(ladder, rows);
                    }
                    return true;
                } catch (ApiException e) {
                    BetterLoka.LOGGER.debug("Could not load the {} ladder", ladder, e);
                    return false;
                }
            }, api.executor()));
        }
        CompletableFuture<Void> load = CompletableFuture.allOf(ladders.toArray(CompletableFuture[]::new))
                .thenRun(() -> {
                    // Only a full answer is kept for the five minutes; a ladder that failed is asked
                    // for again by the next search.
                    if (ladders.stream().allMatch(CompletableFuture::join)) {
                        currentLoadedAt = System.currentTimeMillis();
                    }
                });
        currentLoad = load;
        return load;
    }

    /**
     * Gets both ladders and the best-rank index ready ahead of the first search.
     *
     * <p>The index is read from disk when it is fresh, which is instant; otherwise it is built in
     * the background, so the Player Finder's Best rank is there when somebody asks rather than
     * forty requests after.
     */
    public void prewarm() {
        loadCurrent();
        loadHistory();
    }

    private Map<Ladder, Map<String, Best>> historyIfReady() {
        CompletableFuture<Map<Ladder, Map<String, Best>>> built = history;
        return built != null && built.isDone() && !built.isCompletedExceptionally() ? built.join() : null;
    }

    private CompletableFuture<Map<Ladder, Map<String, Best>>> loadHistory() {
        CompletableFuture<Map<Ladder, Map<String, Best>>> existing = history;
        if (existing != null && !(existing.isDone() && (existing.isCompletedExceptionally() || !historyComplete))) {
            return existing;
        }
        synchronized (this) {
            CompletableFuture<Map<Ladder, Map<String, Best>>> held = history;
            if (held == null || held.isDone() && (held.isCompletedExceptionally() || !historyComplete)) {
                CompletableFuture<Map<Ladder, Map<String, Best>>> build =
                        CompletableFuture.supplyAsync(this::buildHistory, api.backgroundExecutor());
                // What an incomplete build found is served to whoever was waiting on it, and the
                // next caller starts another.
                history = build;
                held = build;
            }
            return held;
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
            // The names come back with it: rebuilding the index just to recover them would undo the
            // whole point of caching it.
            historyComplete = true;
            return fromDisk;
        }
        historyComplete = false;

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

        // Every season at once, a short task each in the bulk lane: one after the other was forty
        // round trips end to end, the better part of ten seconds before Best rank could say anything.
        int latest = seasons.stream().mapToInt(Integer::intValue).max().orElse(-1);
        int previous = seasons.stream().mapToInt(Integer::intValue).filter(season -> season < latest).max().orElse(-1);
        List<CompletableFuture<Boolean>> tasks = new ArrayList<>();
        for (int season : seasons) {
            Kind kind = season == latest ? Kind.LATEST : season == previous ? Kind.PREVIOUS : Kind.OLDER;
            tasks.add(CompletableFuture.supplyAsync(() -> recordSeason(index, season, kind), api.bulkExecutor()));
        }
        boolean complete = true;
        for (CompletableFuture<Boolean> task : tasks) {
            try {
                complete &= task.join();
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.debug("An arena season failed to index", e);
                complete = false;
            }
        }
        if (complete) {
            writeIndex(index);
        } else {
            BetterLoka.LOGGER.info("[betterloka] the ranked history came back incomplete; it will be read again");
        }
        historyComplete = complete;
        return index;
    }

    /** Where a season stands among those published: the last two are also kept on their own. */
    enum Kind {
        LATEST, PREVIOUS, OLDER
    }

    /**
     * One season's final standings on both ladders.
     *
     * @return whether all of it was read
     */
    private boolean recordSeason(Map<Ladder, Map<String, Best>> index, int season, Kind kind) {
        int week;
        try {
            List<Integer> weeks = api.fetchWeeks(season);
            if (weeks.isEmpty()) {
                return true;
            }
            week = weeks.get(weeks.size() - 1);
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not list weeks of arena season {}", season, e);
            return false;
        }
        boolean complete = true;
        for (Ladder ladder : Ladder.values()) {
            try {
                List<ArenaEntry> rows = api.fetchHistory(ladder, season, week);
                rememberNames(rows);
                synchronized (index) {
                    record(index.get(ladder), season, rows, kind);
                }
            } catch (ApiException e) {
                BetterLoka.LOGGER.debug("Could not load {} season {} week {}", ladder, season, week, e);
                complete = false;
            }
        }
        return complete;
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
            for (Best best : restored.values()) {
                rememberNames(List.of(best.entry()));
            }
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

    /**
     * Indexes one season's table: each player's best season so far ({@code u:}/{@code n:}); the
     * latest and the one before kept as they are ({@code lu:}/{@code ln:}, {@code pu:}/{@code pn:});
     * and every season but the latest added up ({@code cu:}/{@code cn:} — a {@link Best} whose season
     * is how many seasons, its entry the summed wins and losses with no rank). The latest is left out
     * of the sum because it is usually still being played.
     */
    static void record(Map<String, Best> index, int season, List<ArenaEntry> rows, Kind kind) {
        for (ArenaEntry entry : rows) {
            if (entry.rank() == null) {
                continue;
            }
            Best candidate = new Best(season, entry);
            String prefix = switch (kind) {
                case LATEST -> "l";
                case PREVIOUS -> "p";
                case OLDER -> null;
            };
            if (entry.uuid() != null) {
                keepBetter(index, "u:" + entry.uuid(), candidate);
                if (prefix != null) {
                    index.putIfAbsent(prefix + "u:" + entry.uuid(), candidate);
                }
                if (kind != Kind.LATEST) {
                    addUp(index, "cu:" + entry.uuid(), entry);
                }
            }
            if (entry.name() != null) {
                String name = entry.name().toLowerCase(Locale.ROOT);
                keepBetter(index, "n:" + name, candidate);
                if (prefix != null) {
                    index.putIfAbsent(prefix + "n:" + name, candidate);
                }
                if (kind != Kind.LATEST) {
                    addUp(index, "cn:" + name, entry);
                }
            }
        }
    }

    private static void addUp(Map<String, Best> index, String key, ArenaEntry entry) {
        index.merge(key, new Best(1, new ArenaEntry(entry.name(), entry.uuid(), entry.wins(), entry.losses(),
                        null, 0, 0, 0)),
                (held, more) -> new Best(held.season() + 1, new ArenaEntry(held.entry().name(), held.entry().uuid(),
                        held.entry().wins() + more.entry().wins(), held.entry().losses() + more.entry().losses(),
                        null, 0, 0, 0)));
    }

    /** Keeps whatever a UUID was called in this snapshot, which is where name history comes from. */
    private void rememberNames(List<ArenaEntry> rows) {
        synchronized (seenNames) {
            for (ArenaEntry entry : rows) {
                if (entry.uuid() == null || entry.name() == null) {
                    continue;
                }
                List<String> names = seenNames.computeIfAbsent(entry.uuid(), key -> new ArrayList<>());
                if (!names.contains(entry.name())) {
                    names.add(entry.name());
                }
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
