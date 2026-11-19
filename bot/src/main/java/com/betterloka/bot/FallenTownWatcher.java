package com.betterloka.bot;

import com.betterloka.api.ApiException;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import com.betterloka.towns.FallenTowns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweeps Loka for towns that have fallen, and reports the ones nobody has been told about yet.
 *
 * <p>The detection itself is {@link FallenTowns}, the same code the in-game Town Logger runs. What
 * this adds is two things: the memory of what has already been announced, so a restart does not ping
 * the role with a dozen towns that died last month, and a cheap way to ask "has anything happened"
 * so the watch can run every half minute without downloading a megabyte each time.
 *
 * <p>The cheap check works because <em>a town falling changes nothing in the territory list</em> —
 * its claims keep pointing at the same id. What changes is that the town joins Loka's deleted list,
 * so the size of that list is the signal. Reading it is one request of about a kilobyte; the full
 * sweep behind it is closer to a megabyte and only runs when the count has actually moved.
 *
 * <p>A count is only taken as seen once the sweep it set off has finished. A sweep that fails
 * part-way — Loka answering 429 on page thirty of its deleted list, say — leaves the count as it
 * was, so the next check sweeps again rather than deciding nothing has happened.
 */
public final class FallenTownWatcher {
    /**
     * How long the deleted-town listing is good for. It only changes when a town dies, which the
     * count already says, so this is only a backstop: forty-odd requests are not worth more often.
     */
    private static final long DELETED_TTL_MILLIS = 12 * 60 * 60 * 1000L;
    /** How often one request of a sweep is tried, and how long to wait between tries, growing. */
    static final int ATTEMPTS = 4;
    static final long RETRY_WAIT_MILLIS = 15_000;

    /** Waits between tries; the tests give one that does not. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private interface Call<T> {
        T run() throws ApiException;
    }

    private final LokaApi api;
    private final BotState state;
    private final Sleeper sleeper;

    private Map<String, LokaTown> deletedTowns = Map.of();
    private long deletedLoadedAt;

    /** The last count a finished sweep saw, so a change can be spotted without downloading anything else. */
    private int lastDeletedCount = -1;
    private long lastFullSweepAt;

    public FallenTownWatcher(LokaApi api, BotState state) {
        this(api, state, Thread::sleep);
    }

    FallenTownWatcher(LokaApi api, BotState state, Sleeper sleeper) {
        this.api = api;
        this.state = state;
        this.sleeper = sleeper;
        // Where the last run left off: the deleted-town listing and its count, so a restart does not
        // read the forty-odd pages again. The last sweep's time is not taken up on purpose: every
        // start sweeps the territories once (five requests, the listing from the state), so whatever
        // fell while the bot was off, or while a sweep kept failing, is found at once.
        this.lastDeletedCount = state.deletedCount();
        this.deletedTowns = state.deletedTowns();
        this.deletedLoadedAt = state.deletedLoadedAt();
    }

    /** What a cheap check decided to do, so the caller can log it honestly. */
    public record Result(List<FallenTowns.Fallen> announce, boolean sweptFully, int deletedCount) {
    }

    /**
     * One check. The state is changed in memory — what was seen, the count, the listing — and left
     * for the caller to save together with the alerts it queues, so the two cannot come apart.
     *
     * @param announceEverything     true to report the standing backlog rather than just record it —
     *                               only ever true on a deliberately configured first run
     * @param fullSweepIntervalMillis how long may pass without a full sweep regardless of the count,
     *                                as a backstop against a deletion the count happens to hide
     */
    public synchronized Result check(boolean announceEverything, long fullSweepIntervalMillis)
            throws ApiException, InterruptedException {
        int count = patiently(api::countDeletedTowns);
        boolean countMoved = count >= 0 && count != lastDeletedCount;
        boolean overdue = System.currentTimeMillis() - lastFullSweepAt >= fullSweepIntervalMillis;

        if (!countMoved && !overdue && lastDeletedCount >= 0) {
            // Nothing has been deleted since the last look, and the backstop is not due: the
            // kilobyte just spent is the whole cost of this check.
            return new Result(List.of(), false, count);
        }

        long sweptAt = System.currentTimeMillis();
        // If this throws, nothing below has happened: the count stays unseen and the next check
        // sweeps again.
        List<FallenTowns.Fallen> fresh = sweep(announceEverything, count);
        lastDeletedCount = count;
        lastFullSweepAt = sweptAt;
        state.rememberSweep(lastDeletedCount, lastFullSweepAt, deletedTowns, deletedLoadedAt);
        return new Result(fresh, true, count);
    }

    private List<FallenTowns.Fallen> sweep(boolean announceEverything, int count)
            throws ApiException, InterruptedException {
        List<Territory> territories = fetchTerritories();
        refreshDeletedTowns(count);
        List<FallenTowns.Fallen> fallen = FallenTowns.detect(territories, deletedTowns);

        List<FallenTowns.Fallen> fresh = new ArrayList<>();
        boolean firstRun = state.isFirstRun();
        for (FallenTowns.Fallen town : fallen) {
            // Keyed by town and territory: a town losing a second territory later is news again,
            // and the same territory reported twice is not.
            boolean anyNew = state.markSeen(keys(town));
            if (firstRun ? announceEverything : anyNew) {
                fresh.add(town);
            }
        }
        state.clearFirstRun();
        return fresh;
    }

    static List<String> keys(FallenTowns.Fallen town) {
        List<String> keys = new ArrayList<>();
        for (Territory territory : town.territories()) {
            keys.add(town.town().id() + "|" + territory.key());
        }
        return keys;
    }

    /** Everything currently standing as fallen, announced or not — for the {@code --list} check. */
    public synchronized List<FallenTowns.Fallen> current() throws ApiException, InterruptedException {
        List<Territory> territories = fetchTerritories();
        int count = patiently(api::countDeletedTowns);
        refreshDeletedTowns(count);
        return FallenTowns.detect(territories, deletedTowns);
    }

    /** Whether every territory of {@code town} has been announced (or recorded on the first run). */
    public boolean announced(FallenTowns.Fallen town) {
        return state.allSeen(keys(town));
    }

    private List<Territory> fetchTerritories() throws ApiException, InterruptedException {
        List<Territory> territories = new ArrayList<>();
        for (String world : LokaApi.CONQUEST_WORLDS) {
            territories.addAll(patiently(() -> api.fetchTerritories(world)));
        }
        if (territories.isEmpty()) {
            throw new ApiException("The territory sweep came back empty", false);
        }
        return territories;
    }

    /**
     * Reloads the deleted-town listing when it is stale or when the count says it has grown.
     *
     * <p>Forty small requests, and the only way to put a name to the id a fallen town leaves behind:
     * a deleted town 404s on a lookup by id. Each page is tried a few times, waiting longer each
     * time, so one 429 on page thirty does not throw the other twenty-nine away.
     */
    private void refreshDeletedTowns(int count) throws ApiException, InterruptedException {
        boolean stale = System.currentTimeMillis() - deletedLoadedAt >= DELETED_TTL_MILLIS;
        boolean incomplete = deletedTowns.size() != count;
        if (!deletedTowns.isEmpty() && !stale && !incomplete) {
            return;
        }
        Map<String, LokaTown> deleted = new HashMap<>();
        LokaApi.TownPage first = patiently(() -> api.fetchDeletedTownPage(0));
        collect(first, deleted);
        for (int page = 1; page < first.totalPages(); page++) {
            int number = page;
            collect(patiently(() -> api.fetchDeletedTownPage(number)), deleted);
        }
        if (!deleted.isEmpty()) {
            deletedTowns = Map.copyOf(deleted);
            deletedLoadedAt = System.currentTimeMillis();
        }
    }

    /** One request, tried again after a wait when Loka says slow down or does not answer. */
    private <T> T patiently(Call<T> call) throws ApiException, InterruptedException {
        ApiException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (ApiException e) {
                if (e.notFound()) {
                    throw e;
                }
                last = e;
                if (attempt < ATTEMPTS) {
                    sleeper.sleep(RETRY_WAIT_MILLIS * attempt);
                }
            }
        }
        throw last;
    }

    private static void collect(LokaApi.TownPage page, Map<String, LokaTown> into) {
        for (LokaTown town : page.towns()) {
            if (town.id() != null) {
                into.put(town.id(), town);
            }
        }
    }

    public int knownDeletedTowns() {
        return deletedTowns.size();
    }
}
