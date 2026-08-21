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
 * this adds is the memory of what has already been announced, so a restart does not ping the role
 * with a dozen towns that died last month.
 */
public final class FallenTownWatcher {
    /** How long the deleted-town listing is good for. It only grows when a town dies. */
    private static final long DELETED_TTL_MILLIS = 30 * 60 * 1000L;

    private final LokaApi api;
    private final BotState state;

    private Map<String, LokaTown> deletedTowns = Map.of();
    private long deletedLoadedAt;

    public FallenTownWatcher(LokaApi api, BotState state) {
        this.api = api;
        this.state = state;
    }

    /**
     * One sweep.
     *
     * @param announceEverything true to report the standing backlog rather than just record it —
     *                           only ever true on a deliberately configured first run
     * @return the fallen towns worth announcing, newest knowledge first
     */
    public List<FallenTowns.Fallen> check(boolean announceEverything) throws ApiException {
        List<Territory> territories = new ArrayList<>();
        for (String world : LokaApi.CONQUEST_WORLDS) {
            territories.addAll(api.fetchTerritories(world));
        }
        if (territories.isEmpty()) {
            throw new ApiException("The territory sweep came back empty", false);
        }

        refreshDeletedTowns();
        List<FallenTowns.Fallen> fallen = FallenTowns.detect(territories, deletedTowns);

        List<FallenTowns.Fallen> fresh = new ArrayList<>();
        for (FallenTowns.Fallen town : fallen) {
            // Keyed by town and territory: a town losing a second territory later is news again,
            // and the same territory reported twice is not.
            List<String> keys = new ArrayList<>();
            for (Territory territory : town.territories()) {
                keys.add(town.town().id() + "|" + territory.key());
            }
            if (state.markSeen(keys) && !state.isFirstRun()) {
                fresh.add(town);
            } else if (state.isFirstRun() && announceEverything) {
                fresh.add(town);
            }
        }
        state.clearFirstRun();
        state.save();
        return fresh;
    }

    /** Everything currently standing as fallen, announced or not — for the {@code --list} check. */
    public List<FallenTowns.Fallen> current() throws ApiException {
        List<Territory> territories = new ArrayList<>();
        for (String world : LokaApi.CONQUEST_WORLDS) {
            territories.addAll(api.fetchTerritories(world));
        }
        refreshDeletedTowns();
        return FallenTowns.detect(territories, deletedTowns);
    }

    /**
     * Reloads the deleted-town listing when it is stale.
     *
     * <p>Forty small requests, and the only way to put a name to the id a fallen town leaves behind:
     * a deleted town 404s on a lookup by id.
     */
    private void refreshDeletedTowns() throws ApiException {
        if (!deletedTowns.isEmpty() && System.currentTimeMillis() - deletedLoadedAt < DELETED_TTL_MILLIS) {
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
