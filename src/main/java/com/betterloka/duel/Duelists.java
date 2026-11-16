package com.betterloka.duel;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.ArenaApi;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.stats.ArenaService;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A duelist's ranked record for the duel screens: their own profile on Loka (this season, every
 * game of it) and the past seasons' tables (best rank, last season, career), put together.
 *
 * <p>Two requests at most per player — the profile, and the current ladders if they are not already
 * in — and the history index the mod keeps anyway. Kept for two minutes, so a rematch or opening
 * the summary again asks nothing.
 */
public final class Duelists {
    private static final long KEEP_MILLIS = 2 * 60_000L;
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * What is known of one duelist.
     *
     * @param profile their Loka profile, or {@code null} if it could not be read
     * @param careers per ladder; empty if the ladders could not be read
     */
    public record Duelist(String name, UUID uuid, LokaPlayer profile, List<ArenaService.Career> careers) {
        public ArenaService.Career career(ArenaApi.Ladder ladder) {
            for (ArenaService.Career career : careers) {
                if (career.ladder() == ladder) {
                    return career;
                }
            }
            return null;
        }

        /** This season over both ladders: their own profile has every game of it, the tables the top. */
        public ArenaService.Totals season() {
            int wins = 0;
            int losses = 0;
            if (profile != null && !profile.ranked().isEmpty()) {
                for (LokaPlayer.Ranked ranked : profile.ranked().values()) {
                    wins += ranked.wins();
                    losses += ranked.losses();
                }
            } else {
                for (ArenaService.Career career : careers) {
                    if (career.current() != null) {
                        wins += career.current().wins();
                        losses += career.current().losses();
                    }
                }
            }
            return new ArenaService.Totals(wins, losses, wins + losses > 0 ? 1 : 0);
        }

        /** Their rank on the ladder now, from their profile or else the table. */
        public com.betterloka.api.model.ArenaRank rankNow(ArenaApi.Ladder ladder) {
            LokaPlayer.Ranked ranked = profile == null ? null : profile.ranked().get(ladder);
            if (ranked != null && ranked.rank() != null) {
                return ranked.rank();
            }
            ArenaService.Career career = career(ladder);
            return career == null || career.current() == null ? null : career.current().rank();
        }

        /** Their win streak on the ladder now, or 0. */
        public int streak(ArenaApi.Ladder ladder) {
            LokaPlayer.Ranked ranked = profile == null ? null : profile.ranked().get(ladder);
            return ranked == null ? 0 : ranked.streak();
        }

        /** Every season Loka has a table of, plus this one, over both ladders. */
        public ArenaService.Totals career() {
            int wins = 0;
            int losses = 0;
            int seasons = 0;
            for (ArenaService.Career career : careers) {
                wins += career.career().wins();
                losses += career.career().losses();
                seasons = Math.max(seasons, career.career().seasons());
            }
            return new ArenaService.Totals(wins, losses, seasons);
        }

        /** The season being played now, or -1 if the ladders are not known. */
        public int seasonNumber() {
            return careers.isEmpty() ? -1 : careers.get(0).season();
        }

        public int lastSeasonNumber() {
            return careers.isEmpty() ? -1 : careers.get(0).lastNumber();
        }
    }

    private record Held(CompletableFuture<Duelist> lookup, long at) {
    }

    private final Supplier<LokaApi> loka;
    private final Supplier<ArenaService> arena;
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    public Duelists(Supplier<LokaApi> loka, Supplier<ArenaService> arena) {
        this.loka = loka;
        this.arena = arena;
    }

    /** The duelist's record, asked for once per two minutes. Never fails: what is missing is empty. */
    public CompletableFuture<Duelist> load(String name, UUID uuid) {
        String key = uuid != null ? uuid.toString() : "n:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        long now = System.currentTimeMillis();
        Held existing = held.get(key);
        if (existing != null && now - existing.at() < KEEP_MILLIS && !existing.lookup().isCompletedExceptionally()) {
            return existing.lookup();
        }
        CompletableFuture<Duelist> lookup = fetch(name, uuid);
        held.put(key, new Held(lookup, now));
        return lookup;
    }

    private CompletableFuture<Duelist> fetch(String name, UUID uuid) {
        LokaApi api = loka.get();
        CompletableFuture<LokaPlayer> profile = api == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> {
                    try {
                        LokaPlayer byUuid = null;
                        if (uuid != null) {
                            try {
                                byUuid = api.findPlayerByUuid(uuid);
                            } catch (ApiException notThere) {
                                BetterLoka.LOGGER.debug("No Loka profile by UUID for {}", name, notThere);
                            }
                        }
                        return byUuid != null || name == null ? byUuid : api.findPlayerByName(name);
                    } catch (ApiException | RuntimeException e) {
                        BetterLoka.LOGGER.debug("No Loka profile for duelist {}", name, e);
                        return null;
                    }
                }, api.executor());
        return profile.thenCompose(found -> {
            String knownName = found != null && found.name() != null ? found.name() : name;
            UUID knownUuid = found != null && found.uuid() != null ? found.uuid() : uuid;
            ArenaService service = arena.get();
            CompletableFuture<List<ArenaService.Career>> careers = service == null
                    ? CompletableFuture.completedFuture(List.of())
                    : service.careers(knownName, knownUuid, found == null ? Map.of() : found.ranked())
                    .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(failure -> {
                        BetterLoka.LOGGER.debug("No ranked history for duelist {}", knownName, failure);
                        return List.of();
                    });
            return careers.thenApply(list -> new Duelist(knownName, knownUuid, found, list));
        });
    }
}
