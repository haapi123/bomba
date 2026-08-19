package com.betterloka.data;

import com.betterloka.BetterLoka;
import com.betterloka.api.LokaApi;
import com.betterloka.api.LokaApiException;
import com.betterloka.api.model.LokaTown;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves town IDs to towns. Battle records reference towns by ID only, and newer records leave
 * the attacker/defender name fields null, so a name is only obtainable through this lookup.
 *
 * <p>Loka has fewer than a hundred living towns, so the whole set is pulled in one go and kept for
 * a few minutes; disbanded towns referenced by old battles are fetched individually as they come up.
 */
public final class TownCache {
    private static final long BULK_TTL_MILLIS = 10 * 60 * 1000L;

    private final LokaApi api;
    private final Map<String, LokaTown> byId = new ConcurrentHashMap<>();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();
    private volatile long lastBulkLoad;

    public TownCache(LokaApi api) {
        this.api = api;
    }

    /** Pulls every living town. Cheap enough to call before each search; it self-throttles. */
    public void ensureLoaded() {
        if (System.currentTimeMillis() - lastBulkLoad < BULK_TTL_MILLIS && !byId.isEmpty()) {
            return;
        }
        try {
            LokaApi.TownPage first = api.fetchTownPage(0);
            store(first);
            for (int page = 1; page < first.totalPages(); page++) {
                store(api.fetchTownPage(page));
            }
            lastBulkLoad = System.currentTimeMillis();
            unknown.clear();
        } catch (LokaApiException e) {
            BetterLoka.LOGGER.debug("Could not refresh the town list", e);
        }
    }

    private void store(LokaApi.TownPage page) {
        for (LokaTown town : page.towns()) {
            if (town.id() != null) {
                byId.put(town.id(), town);
            }
        }
    }

    /** @return the town, or {@code null} if it does not exist or could not be fetched. */
    public LokaTown byId(String townId) {
        if (townId == null || townId.isEmpty()) {
            return null;
        }
        LokaTown cached = byId.get(townId);
        if (cached != null) {
            return cached;
        }
        if (unknown.contains(townId)) {
            return null;
        }
        try {
            LokaTown town = api.findTownById(townId);
            if (town != null && town.id() != null) {
                byId.put(town.id(), town);
                return town;
            }
        } catch (LokaApiException e) {
            BetterLoka.LOGGER.debug("Could not resolve town {}", townId, e);
        }
        // Remember the miss so a battle list full of dead towns does not re-request each one.
        unknown.add(townId);
        return null;
    }

    /** @return the town's name, or {@code null} when it cannot be resolved. */
    public String nameOf(String townId) {
        LokaTown town = byId(townId);
        return town == null ? null : town.name();
    }
}
