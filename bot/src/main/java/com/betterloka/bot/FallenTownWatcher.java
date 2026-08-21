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
 */
public final class FallenTownWatcher {
    /** How long the deleted-town listing is good for. It only grows when a town dies. */
    private static final long DELETED_TTL_MILLIS = 30 * 60 * 1000L;

    private final LokaApi api;
    private final BotState state;

    private Map<String, LokaTown> deletedTowns = Map.of();
    private long deletedLoadedAt;

    /** The last count seen, so a change can be spotted without downloading anything else. */
    private int lastDeletedCount = -1;
    private long lastFullSweepAt;

    public FallenTownWatcher(LokaApi api, BotState state) {
        this.api = api;
        this.state = state;
    }

    /** What a cheap check decided to do, so the caller can log it honestly. */
    public record Result(List<FallenTowns.Fallen> announce, boolean sweptFully, int deletedCount) {
    }

    /**
     * One check.
     *
     * @param announceEverything     true to report the standing backlog rather than just record it —
     *                               only ever true on a deliberately configured first run
     * @param fullSweepIntervalMillis how long may pass without a full sweep regardless of the count,
     *                                as a backstop against a deletion the count happens to hide
     */
    public Result check(boolean announceEverything, long fullSweepIntervalMillis) throws ApiException {
        int count = api.countDeletedTowns();
        boolean countMoved = count >= 0 && count != lastDeletedCount;
        boolean overdue = System.currentTimeMillis() - lastFullSweepAt >= fullSweepIntervalMillis;

        if (!countMoved && !overdue && lastDeletedCount >= 0) {
            // Nothing has been deleted since the last look, and the backstop is not due: the
            // kilobyte just spent is the whole cost of this check.
            return new Result(List.of(), false, count);
        }

        lastDeletedCount = count;
        lastFullSweepAt = System.currentTimeMillis();
        return new Result(sweep(announceEverything), true, count);
    }

    private List<FallenTowns.Fallen> sweep(boolean announceEverything) throws ApiException {
        List<Territory> territories = fetchTerritories();
        refreshDeletedTowns();
        List<FallenTowns.Fallen> fallen = FallenTowns.detect(territories, deletedTowns);

        List<FallenTowns.Fallen> fresh = new ArrayList<>();
        boolean firstRun = state.isFirstRun();
        for (FallenTowns.Fallen town : fallen) {
            // Keyed by town and territory: a town losing a second territory later is news again,
            // and the same territory reported twice is not.
            List<String> keys = new ArrayList<>();
            for (Territory territory : town.territories()) {
                keys.add(town.town().id() + "|" + territory.key());
            }
            boolean anyNew = state.markSeen(keys);
            if (firstRun ? announceEverything : anyNew) {
                fresh.add(town);
            }
        }
        state.clearFirstRun();
        state.save();
        return fresh;
    }

    /** Everything currently standing as fallen, announced or not — for the {@code --list} check. */
    public List<FallenTowns.Fallen> current() throws ApiException {
        List<Territory> territories = fetchTerritories();
        refreshDeletedTowns();
        return FallenTowns.detect(territories, deletedTowns);
    }

    private List<Territory> fetchTerritories() throws ApiException {
        List<Territory> territories = new ArrayList<>();
        for (String world : LokaApi.CONQUEST_WORLDS) {
            territories.addAll(api.fetchTerritories(world));
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
     * a deleted town 404s on a lookup by id.
     */
    private void refreshDeletedTowns() throws ApiException {
        boolean stale = System.currentTimeMillis() - deletedLoadedAt >= DELETED_TTL_MILLIS;
        boolean incomplete = deletedTowns.size() != lastDeletedCount;
        if (!deletedTowns.isEmpty() && !stale && !incomplete) {
            return;
        }
        Map<String, LokaTown> deleted = new HashMap<>();
        LokaApi.TownPage first = api.fetchDeletedTownPage(0);
        collect(first, deleted);
        for (int page = 1; page < first.totalPages(); page++) {
            collect(api.fetchDeletedTownPage(page), deleted);
        }
        if (!deleted.isEmpty()) {
            deletedTowns = Map.copyOf(deleted);
            deletedLoadedAt = System.currentTimeMillis();
        }
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
