package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.model.EldritchStats;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies K/D ratios for the nameplates drawn above players in the world.
 *
 * <p>Rendering happens every frame, so lookups here never block: a miss returns empty and schedules
 * a fetch in the background, and the number appears a moment later. Each name is fetched at most
 * once per cache window however many times it is drawn, and players EldritchBot has never seen are
 * remembered as misses so a lobby full of newcomers cannot turn into a request loop.
 */
public final class NameplateKdService {
    private static final long HIT_TTL_MILLIS = 15 * 60 * 1000L;
    private static final long MISS_TTL_MILLIS = 5 * 60 * 1000L;

    /** Above this many pending fetches, new ones wait — a big fight should trickle, not flood. */
    private static final int MAX_IN_FLIGHT = 6;

    private record Entry(Double killDeath, long fetchedAt) {
        boolean expired() {
            long ttl = killDeath == null ? MISS_TTL_MILLIS : HIT_TTL_MILLIS;
            return System.currentTimeMillis() - fetchedAt > ttl;
        }
    }

    private final EldritchApi api;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public NameplateKdService(EldritchApi api) {
        this.api = api;
    }

    /**
     * @return the player's K/D if it is already cached, otherwise empty — and a fetch is queued so a
     * later frame has it.
     */
    public OptionalDouble killDeathOf(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return OptionalDouble.empty();
        }
        String key = EldritchApi.cacheKey(playerName);
        Entry entry = cache.get(key);
        if (entry != null && !entry.expired()) {
            return entry.killDeath() == null ? OptionalDouble.empty() : OptionalDouble.of(entry.killDeath());
        }
        queueFetch(key, playerName);
        // Keep showing the stale value while the refresh runs, rather than flickering back to blank.
        return entry != null && entry.killDeath() != null ? OptionalDouble.of(entry.killDeath()) : OptionalDouble.empty();
    }

    private void queueFetch(String key, String playerName) {
        if (inFlight.size() >= MAX_IN_FLIGHT || !inFlight.add(key)) {
            return;
        }
        api.bulkExecutor().execute(() -> {
            Double value = null;
            try {
                EldritchStats stats = api.fetchQuickStats(playerName);
                if (stats != null && (stats.kills() > 0 || stats.deaths() > 0)) {
                    value = stats.killDeathRatio();
                }
            } catch (ApiException e) {
                if (!e.notFound()) {
                    BetterLoka.LOGGER.debug("Could not fetch K/D for {}", playerName, e);
                }
            } finally {
                cache.put(key, new Entry(value, System.currentTimeMillis()));
                inFlight.remove(key);
            }
        });
    }

    /** Drops everything, so a settings change or a server switch starts from a clean slate. */
    public void clear() {
        cache.clear();
    }
}
