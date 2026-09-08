package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every battle a player has fought, from Loka's own archive.
 *
 * <p>EldritchBot publishes a career total and the last ten fights and nothing between, so a split of
 * a career by format could only ever be taken over those ten. Loka's {@code /battlezones} carries
 * all 8,445 battles ever fought, each with a line per player — kills, deaths, whether they actually
 * turned up — which is the only place the full answer exists.
 *
 * <p>It is not cheap to read: the server caps a page at twenty however large a size is asked for, so
 * the whole archive is 423 requests and about 77 MB. Three things make that acceptable. It is read
 * newest-first, so a refresh stops at the first battle already held. It is folded into per-player
 * monthly totals as it goes and only those are kept, so the archive itself is never stored. And it
 * is shared: the sweep that answers one player answers everybody.
 *
 * <p>What it cannot give is wins. Loka's battle records carry no winner — no field, and the strength
 * and conquest-point columns are zero on finished battles — so win rates stay with EldritchBot's
 * career figure and the totals here are fights, kills and deaths.
 */
public final class BattleIndex {
    /** Rivina's battles are named {@code lilboi-9}; the prefix is the world, and worlds do not move. */
    private static final String RIVI_WORLD = "lilboi";

    /** How many pages one sweep pass fetches before yielding, so a session is never held hostage. */
    private static final int PAGES_PER_PASS = 40;

    /**
     * How far the forward pass looks for battles fought since the last sweep.
     *
     * <p>Two pages is forty battles, comfortably more than a day's worth, and it stops at the first
     * one already held — so keeping an index current normally costs a single request.
     */
    private static final int FORWARD_PAGES = 2;

    /** The archive is append-only; a rebuild from scratch is only needed if the format changes. */
    private static final int FORMAT = 1;

    /** One player's fights in one month, on one side of the Rivi/Conquest divide. */
    public record Bucket(int fights, int kills, int deaths) {
        static final Bucket EMPTY = new Bucket(0, 0, 0);

        Bucket plus(int killsToAdd, int deathsToAdd) {
            return new Bucket(fights + 1, kills + killsToAdd, deaths + deathsToAdd);
        }

        public Bucket plus(Bucket other) {
            return new Bucket(fights + other.fights, kills + other.kills, deaths + other.deaths);
        }

        public boolean isEmpty() {
            return fights == 0;
        }

        public double killDeathRatio() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }

        public String killDeathText() {
            return String.format(Locale.ROOT, "%.2f", killDeathRatio());
        }
    }

    /** What one player did, split by format and broken down by month. */
    public record PlayerRecord(Bucket riviTotal, Bucket conquestTotal,
                               Map<String, Bucket> riviByMonth, Map<String, Bucket> conquestByMonth) {
        public static final PlayerRecord EMPTY =
                new PlayerRecord(Bucket.EMPTY, Bucket.EMPTY, Map.of(), Map.of());

        public Bucket rivi(String month) {
            return riviByMonth.getOrDefault(month, Bucket.EMPTY);
        }

        public Bucket conquest(String month) {
            return conquestByMonth.getOrDefault(month, Bucket.EMPTY);
        }

        public boolean isEmpty() {
            return riviTotal.isEmpty() && conquestTotal.isEmpty();
        }
    }

    /** How far the sweep has got, for a screen that wants to say so. */
    public record Progress(boolean sweeping, int battlesIndexed, int battlesTotal,
                           int pagesRead, int pagesTotal) {
        /**
         * Whether the whole archive has been read.
         *
         * <p>Measured in pages, not battles: a battle nobody turned up to contributes nothing to
         * anybody's totals, so counting folded battles would leave a finished build looking unfinished
         * for ever.
         */
        public boolean complete() {
            return pagesTotal > 0 && pagesRead >= pagesTotal;
        }

        public int percent() {
            return pagesTotal <= 0 ? 0
                    : (int) Math.min(100, Math.round(pagesRead * 100.0 / pagesTotal));
        }
    }

    /** The disk form: flat rows, because a nested map of maps is a great deal of punctuation. */
    private record StoredRow(String uuid, String month, boolean rivi,
                             int fights, int kills, int deaths) {
    }

    private record StoredIndex(int format, long newestEnded, long oldestEnded, int battlesIndexed,
                               int battlesTotal, int pagesRead, int pagesTotal,
                               List<StoredRow> rows) {
    }

    private static final Type STORE_TYPE = JsonStore.envelopeOf(StoredIndex.class);

    private final LokaApi loka;
    private final JsonStore<StoredIndex> disk;

    /** uuid -> month -> bucket, one map per format. Replaced wholesale, never mutated in place. */
    private volatile Map<UUID, Map<String, Bucket>> rivi = Map.of();
    private volatile Map<UUID, Map<String, Bucket>> conquest = Map.of();

    /** The window of the archive already folded in, by battle end time. */
    private volatile long newestEnded;
    private volatile long oldestEnded = Long.MAX_VALUE;
    private volatile int battlesIndexed;
    private volatile int battlesTotal;
    /** How many pages the backward pass has consumed. The frontier, and what resumes a build. */
    private volatile int pagesRead;
    private volatile int pagesTotal;

    private final AtomicBoolean sweeping = new AtomicBoolean();
    /** Whether a build loop is running, as against one pass of it. */
    private final AtomicBoolean indexing = new AtomicBoolean();

    /**
     * The index never goes stale: the archive is append-only, so a battle read a month ago is still
     * the same battle, and a build spread over several sessions has to survive between them.
     *
     * <p>{@link JsonStore} reads a zero here as "expired a millisecond after it was written", which
     * silently threw the whole index away on every restart and started the 77 MB build from nothing.
     */
    private static final long FOREVER = Long.MAX_VALUE;

    public BattleIndex(LokaApi loka, Path file) {
        this.loka = loka;
        this.disk = new JsonStore<>(file, STORE_TYPE, FOREVER);
        restore();
    }

    /** What the index knows about one player. Never blocks. */
    public PlayerRecord of(UUID uuid) {
        if (uuid == null) {
            return PlayerRecord.EMPTY;
        }
        Map<String, Bucket> riviMonths = rivi.getOrDefault(uuid, Map.of());
        Map<String, Bucket> conquestMonths = conquest.getOrDefault(uuid, Map.of());
        if (riviMonths.isEmpty() && conquestMonths.isEmpty()) {
            return PlayerRecord.EMPTY;
        }
        return new PlayerRecord(total(riviMonths), total(conquestMonths),
                Map.copyOf(riviMonths), Map.copyOf(conquestMonths));
    }

    public Progress progress() {
        return new Progress(indexing.get() || sweeping.get(), battlesIndexed, battlesTotal,
                pagesRead, pagesTotal);
    }

    /**
     * Starts filling the index in, and keeps going until it is complete.
     *
     * <p>Called when somebody looks a player up rather than at startup: the archive is 77 MB, and a
     * mod has no business downloading that on the chance it might be wanted. Once built it stays
     * built — the file survives restarts and a later run only reads what is new.
     *
     * <p>Holds one background worker for the length of the build. That is deliberate: background
     * requests wait for a real permit rather than reserving one, so this yields to every screen the
     * player is actually waiting on.
     */
    public void startIndexing(java.util.concurrent.ExecutorService executor) {
        if (progress().complete() || !indexing.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    boolean progressed = sweep();
                    if (!progressed || progress().complete()) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.debug("Battle index build stopped", e);
            } finally {
                indexing.set(false);
            }
        });
    }

    /** The key a month's bucket is filed under, e.g. {@code 2026-09}. */
    public static String monthKey(LocalDate date) {
        return String.format(Locale.ROOT, "%04d-%02d", date.getYear(), date.getMonthValue());
    }

    private static Bucket total(Map<String, Bucket> months) {
        Bucket sum = Bucket.EMPTY;
        for (Bucket bucket : months.values()) {
            sum = sum.plus(bucket);
        }
        return sum;
    }

    /**
     * Reads more of the archive, newest first.
     *
     * <p>Blocking, and meant for a background worker. Two passes are possible on each call: forward,
     * picking up battles fought since the last one, and backward, extending how far back the index
     * reaches. Both stop at {@link #PAGES_PER_PASS} so a full first build spreads over several calls
     * rather than holding a worker for the length of the archive.
     *
     * @return true if anything new was folded in
     */
    public boolean sweep() {
        if (!sweeping.compareAndSet(false, true)) {
            return false;
        }
        try {
            Pass pass = new Pass(copy(rivi), copy(conquest), newestEnded, oldestEnded);

            // Forward: battles fought since the last sweep sit at the front of the archive, and
            // stopping at the first one already held means an up-to-date index costs one page.
            read(pass, 0, FORWARD_PAGES, true);

            // Backward: extend how far back the index reaches, resuming from the page after the
            // last one read. Counted in pages rather than in battles: a battle nobody turned up to
            // folds nothing, so counting what was folded made the frontier lag behind where the
            // reading had actually got to, and the build stalled at a quarter of the archive while
            // re-reading pages it already had. Every battle a forward pass adds pushes the older
            // ones back a place, so the frontier shifts with them, and it resumes a page early
            // because an overlap costs one request where a gap loses battles for good.
            int shift = pass.added / LokaApi.PAGE_SIZE;
            int resumeFrom = Math.max(0, pagesRead + shift - 1);
            read(pass, resumeFrom, PAGES_PER_PASS, false);

            if (pass.added == 0 && pass.pagesRead == 0) {
                return false;
            }
            rivi = pass.rivi;
            conquest = pass.conquest;
            newestEnded = pass.newest;
            oldestEnded = pass.oldest;
            battlesIndexed += pass.added;
            if (pass.furthestPage >= 0) {
                pagesRead = Math.max(pagesRead, pass.furthestPage + 1);
            }
            save();
            return true;
        } finally {
            sweeping.set(false);
        }
    }

    /** One sweep's working state, so the two passes share a window and a set of totals. */
    private static final class Pass {
        private final Map<UUID, Map<String, Bucket>> rivi;
        private final Map<UUID, Map<String, Bucket>> conquest;
        private long newest;
        private long oldest;
        private int added;
        /** Pages the backward pass got through, which is what the next sweep resumes from. */
        private int pagesRead;
        private int furthestPage = -1;

        Pass(Map<UUID, Map<String, Bucket>> rivi, Map<UUID, Map<String, Bucket>> conquest,
             long newest, long oldest) {
            this.rivi = rivi;
            this.conquest = conquest;
            this.newest = newest;
            this.oldest = oldest;
        }
    }

    /**
     * Reads pages into the running totals.
     *
     * @param stopAtKnown true for the forward pass, which is done the moment it meets a battle the
     *                    index already holds; the backward pass reads past those, since an overlap
     *                    is how it finds where its own frontier is
     */
    private void read(Pass pass, int firstPage, int pages, boolean stopAtKnown) {
        for (int page = firstPage; page < firstPage + pages; page++) {
            LokaApi.BattlePage fetched;
            try {
                fetched = loka.fetchBattlePage(page);
            } catch (ApiException e) {
                BetterLoka.LOGGER.debug("Battle archive page {} failed", page, e);
                return;
            }
            if (fetched.totalElements() > 0) {
                battlesTotal = fetched.totalElements();
            }
            if (fetched.totalPages() > 0) {
                pagesTotal = fetched.totalPages();
            }
            if (!stopAtKnown) {
                pass.pagesRead++;
                pass.furthestPage = Math.max(pass.furthestPage, page);
            }
            if (fetched.battles().isEmpty()) {
                return;
            }

            for (BattleZone battle : fetched.battles()) {
                long ended = battle.timeEnded();
                // Newest-first and folded contiguously, so anything inside the window is held.
                if (ended > 0 && ended <= pass.newest && ended >= pass.oldest) {
                    if (stopAtKnown) {
                        return;
                    }
                    continue;
                }
                if (fold(battle, pass.rivi, pass.conquest)) {
                    pass.added++;
                    pass.newest = Math.max(pass.newest, ended);
                    pass.oldest = Math.min(pass.oldest, ended);
                }
            }
            if (fetched.totalPages() > 0 && page >= fetched.totalPages() - 1) {
                return;
            }
        }
    }

    /**
     * Adds one battle's lines to the totals.
     *
     * <p>Only players who actually turned up are counted: Loka records everybody who signed up, and
     * a fight somebody registered for and skipped is not a fight they were in.
     *
     * @return true if the battle contributed anything
     */
    private boolean fold(BattleZone battle, Map<UUID, Map<String, Bucket>> riviOut,
                         Map<UUID, Map<String, Bucket>> conquestOut) {
        long ended = battle.timeEnded();
        if (ended <= 0) {
            return false;
        }
        String month = monthKey(LocalDate.ofInstant(Instant.ofEpochMilli(ended), ZoneId.systemDefault()));
        Map<UUID, Map<String, Bucket>> target = isRivi(battle.territory()) ? riviOut : conquestOut;

        boolean any = false;
        for (BattleParticipant participant : battle.participants()) {
            if (!participant.participated() || participant.uuid() == null) {
                continue;
            }
            Map<String, Bucket> months = target.computeIfAbsent(participant.uuid(), key -> new HashMap<>());
            months.merge(month, Bucket.EMPTY.plus(participant.kills(), participant.deaths()),
                    (existing, added) -> new Bucket(existing.fights() + added.fights(),
                            existing.kills() + added.kills(), existing.deaths() + added.deaths()));
            any = true;
        }
        return any;
    }

    /**
     * Whether a battle was fought on Rivina.
     *
     * <p>Loka names a battle for the hex it was fought over — {@code lilboi-9}, {@code west-125} —
     * and the prefix is the world. The {@code world} field would be the obvious source and is null
     * on older records; the name is filled in on every one of them.
     */
    static boolean isRivi(String battleName) {
        return battleName != null && battleName.startsWith(RIVI_WORLD);
    }

    private static Map<UUID, Map<String, Bucket>> copy(Map<UUID, Map<String, Bucket>> source) {
        Map<UUID, Map<String, Bucket>> copy = new HashMap<>(source.size() * 2);
        source.forEach((uuid, months) -> copy.put(uuid, new HashMap<>(months)));
        return copy;
    }

    private void restore() {
        StoredIndex stored;
        try {
            stored = disk.read();
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the battle index", e);
            return;
        }
        if (stored == null || stored.format() != FORMAT || stored.rows() == null) {
            return;
        }
        Map<UUID, Map<String, Bucket>> riviIn = new HashMap<>();
        Map<UUID, Map<String, Bucket>> conquestIn = new HashMap<>();
        for (StoredRow row : stored.rows()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(row.uuid());
            } catch (IllegalArgumentException e) {
                continue;
            }
            (row.rivi() ? riviIn : conquestIn)
                    .computeIfAbsent(uuid, key -> new HashMap<>())
                    .put(row.month(), new Bucket(row.fights(), row.kills(), row.deaths()));
        }
        rivi = riviIn;
        conquest = conquestIn;
        newestEnded = stored.newestEnded();
        oldestEnded = stored.oldestEnded() == 0 ? Long.MAX_VALUE : stored.oldestEnded();
        battlesIndexed = stored.battlesIndexed();
        battlesTotal = stored.battlesTotal();
        pagesRead = stored.pagesRead();
        pagesTotal = stored.pagesTotal();
    }

    private void save() {
        List<StoredRow> rows = new ArrayList<>();
        collect(rivi, true, rows);
        collect(conquest, false, rows);
        try {
            disk.write(new StoredIndex(FORMAT, newestEnded,
                    oldestEnded == Long.MAX_VALUE ? 0 : oldestEnded,
                    battlesIndexed, battlesTotal, pagesRead, pagesTotal, rows));
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not write the battle index", e);
        }
    }

    private static void collect(Map<UUID, Map<String, Bucket>> source, boolean isRivi,
                                List<StoredRow> out) {
        source.forEach((uuid, months) -> months.forEach((month, bucket) ->
                out.add(new StoredRow(uuid.toString(), month, isRivi,
                        bucket.fights(), bucket.kills(), bucket.deaths()))));
    }
}
