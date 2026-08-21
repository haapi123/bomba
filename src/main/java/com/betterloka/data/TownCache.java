package com.betterloka.data;

import com.betterloka.BetterLoka;
import com.betterloka.api.LokaApi;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.LokaTown;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * How long the roster stays good on disk.
     *
     * <p>Loka ships every town with its full member list, so the roster is close to a megabyte for
     * eighty-odd towns — by a distance the most expensive thing the mod fetches. It changes when
     * somebody founds, disbands or renames a town, which is not something that needs noticing within
     * the hour, so it is kept overnight and re-read from disk on the next launch.
     */
    private static final long DISK_TTL_MILLIS = 12 * 60 * 60 * 1000L;

    private static final Type SAVED_TYPE =
            JsonStore.envelopeOf(new TypeToken<List<LokaTown.Saved>>() {
            }.getType());

    private final LokaApi api;
    private final Map<String, LokaTown> byId = new ConcurrentHashMap<>();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();
    private final JsonStore<List<LokaTown.Saved>> disk;
    private volatile long lastBulkLoad;

    public TownCache(LokaApi api) {
        this(api, null);
    }

    /** @param cacheFile where to keep the roster between sessions, or {@code null} not to. */
    public TownCache(LokaApi api, Path cacheFile) {
        this.api = api;
        this.disk = cacheFile == null ? null : new JsonStore<>(cacheFile, SAVED_TYPE, DISK_TTL_MILLIS);
    }

    /** Pulls every living town. Cheap enough to call before each search; it self-throttles. */
    public synchronized void ensureLoaded() {
        if (System.currentTimeMillis() - lastBulkLoad < BULK_TTL_MILLIS && !byId.isEmpty()) {
            return;
        }
        if (loadFromDisk()) {
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
            saveToDisk();
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not refresh the town list", e);
        }
    }

    /** @return true when a fresh enough roster was on disk and no request is needed. */
    private boolean loadFromDisk() {
        if (disk == null) {
            return false;
        }
        List<LokaTown.Saved> saved = disk.read();
        if (saved == null || saved.isEmpty()) {
            return false;
        }
        for (LokaTown.Saved town : saved) {
            if (town.id() != null) {
                byId.put(town.id(), LokaTown.fromSaved(town));
            }
        }
        lastBulkLoad = System.currentTimeMillis();
        BetterLoka.LOGGER.debug("Loaded {} towns from the saved roster", saved.size());
        return true;
    }

    private void saveToDisk() {
        if (disk == null) {
            return;
        }
        List<LokaTown.Saved> saved = new java.util.ArrayList<>();
        for (LokaTown town : byId.values()) {
            if (!town.deleted()) {
                saved.add(town.toSaved());
            }
        }
        disk.write(saved);
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
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not resolve town {}", townId, e);
        }
        // Remember the miss so a battle list full of dead towns does not re-request each one.
        unknown.add(townId);
        return null;
    }

    /** @return the town with this name, or {@code null} if there is no living town by that name. */
    public LokaTown byName(String townName) {
        if (townName == null || townName.isEmpty()) {
            return null;
        }
        ensureLoaded();
        for (LokaTown town : byId.values()) {
            if (townName.equalsIgnoreCase(town.name())) {
                return town;
            }
        }
        try {
            LokaTown town = api.findTownByName(townName);
            if (town != null && town.id() != null) {
                byId.put(town.id(), town);
            }
            return town;
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not resolve town {}", townName, e);
            return null;
        }
    }

    /** Every living town, loading the roster first if it is stale. */
    public List<LokaTown> all() {
        ensureLoaded();
        List<LokaTown> towns = new java.util.ArrayList<>();
        for (LokaTown town : byId.values()) {
            if (!town.deleted()) {
                towns.add(town);
            }
        }
        return towns;
    }

    /** @return the town's name, or {@code null} when it cannot be resolved. */
    public String nameOf(String townId) {
        LokaTown town = byId(townId);
        return town == null ? null : town.name();
    }
}
