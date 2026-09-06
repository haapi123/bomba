package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LabyApi;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The names a player has used before, and when.
 *
 * <p>This used to be read out of past seasons' ranked ladders, which only knew a name if the player
 * had been on a ladder under it — for the account this was first tested on, that meant seasons 11 to
 * 23 all saying "haapi" and no history at all, while the game's own {@code /find} listed two older
 * names it had never seen. Laby.net keeps a proper record and has twelve for that account, dated.
 *
 * <p>A name history barely changes, so it is cached for a week and only fetched when a profile is
 * actually opened.
 */
public final class NameHistoryService {
    /** Renames are rare and old ones never change, so a week costs nothing and saves the request. */
    private static final long TTL_MILLIS = 7L * 24 * 60 * 60 * 1000L;

    /** Enough for many profiles without the file growing without bound. */
    private static final int MAX_CACHED = 400;

    private static final Type CACHE_TYPE =
            JsonStore.envelopeOf(new TypeToken<Map<String, List<StoredName>>>() { }.getType());

    /** The disk form. {@code changedAt} is epoch millis, or {@code 0} for the first name. */
    private record StoredName(String name, long changedAt, boolean accurate) {
    }

    /**
     * One name the account used to have.
     *
     * @param accurate false once Mojang stopped publishing history in 2022, from when the date is
     *                 when Laby noticed the change rather than when it happened
     */
    public record FormerName(String name, Instant changedAt, boolean accurate) {
    }

    private final LabyApi laby;
    private final HttpTransport transport;
    private final JsonStore<Map<String, List<StoredName>>> disk;
    private final Map<String, List<StoredName>> cache = new ConcurrentHashMap<>();

    public NameHistoryService(LabyApi laby, HttpTransport transport, Path file) {
        this.laby = laby;
        this.transport = transport;
        this.disk = new JsonStore<>(file, CACHE_TYPE, TTL_MILLIS);
        Map<String, List<StoredName>> saved = disk.read();
        if (saved != null) {
            cache.putAll(saved);
        }
    }

    /**
     * Every name before the current one, most recent first.
     *
     * <p>Repeats are folded together — an account that changes away and back shows the name once —
     * and the name it goes by now is left out, since that is not a former name.
     */
    public CompletableFuture<List<FormerName>> previousNames(String currentName, UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        String key = uuid.toString().toLowerCase(Locale.ROOT);

        List<StoredName> known = cache.get(key);
        if (known != null) {
            return CompletableFuture.completedFuture(former(known, currentName));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<StoredName> fetched = new ArrayList<>();
                for (LabyApi.NameEntry entry : laby.nameHistory(uuid)) {
                    fetched.add(new StoredName(entry.name(),
                            entry.changedAt() == null ? 0 : entry.changedAt().toEpochMilli(),
                            entry.accurate()));
                }
                remember(key, fetched);
                return former(fetched, currentName);
            } catch (ApiException e) {
                // An absent history is not worth failing a profile over; the panel says so instead.
                BetterLoka.LOGGER.debug("No name history for {}", uuid, e);
                return List.<FormerName>of();
            }
        }, transport.bulkExecutor());
    }

    /** Walks the history newest first, keeping each name once and dropping the current one. */
    private static List<FormerName> former(List<StoredName> history, String currentName) {
        List<FormerName> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (currentName != null) {
            seen.add(currentName.toLowerCase(Locale.ROOT));
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            StoredName entry = history.get(i);
            if (entry.name() == null || !seen.add(entry.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new FormerName(entry.name(),
                    entry.changedAt() == 0 ? null : Instant.ofEpochMilli(entry.changedAt()),
                    entry.accurate()));
        }
        return List.copyOf(out);
    }

    private void remember(String key, List<StoredName> names) {
        cache.put(key, List.copyOf(names));
        Map<String, List<StoredName>> out = new LinkedHashMap<>(cache);
        while (out.size() > MAX_CACHED) {
            var iterator = out.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        try {
            disk.write(out);
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not cache name history", e);
        }
    }
}
