package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;
import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.FightDetail;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;
import com.betterloka.data.JsonStore;
import com.betterloka.data.TownCache;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Builds a {@link PlayerProfile} from EldritchBot's career totals plus Loka's identity and town data.
 *
 * <p>The whole profile takes a handful of requests: one to EldritchBot for every career number, one
 * to Loka for rank and account age, one for the town roster, and one per recent fight for its
 * breakdown. The headline card is handed over as soon as the first two land, and the fight rows fill
 * in behind it.
 */
public final class PlayerStatsService {
    /** How many recent fights the Player Finder lists as rows. */
    public static final int RECENT_FIGHT_COUNT = 5;

    /**
     * How many are actually fetched.
     *
     * <p>EldritchBot lists nine and stops, and the RIVI split and the month totals are only as good
     * as the number of fights behind them, so all nine are downloaded even though five are shown.
     */
    public static final int FETCHED_FIGHT_COUNT = 9;

    /**
     * A finished fight never changes, so its breakdown is worth keeping for a long time.
     *
     * <p>Fight pages are the mod's heaviest remaining download — about 110 KB each, nine per profile.
     * Caching them makes looking the same player up twice free, and costs nothing in staleness
     * because the numbers are settled the moment the fight ends.
     */
    private static final long FIGHT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000L;

    /** Enough for many profiles' worth of fights without the file growing without bound. */
    private static final int MAX_CACHED_FIGHTS = 600;

    private static final Type FIGHT_CACHE_TYPE =
            JsonStore.envelopeOf(new TypeToken<Map<String, FightDetail>>() {
            }.getType());

    private final LokaApi loka;
    private final EldritchApi eldritch;
    private final TownCache towns;

    private final JsonStore<Map<String, FightDetail>> fightDisk;
    private final Map<String, FightDetail> fightCache = new ConcurrentHashMap<>();
    private final AtomicBoolean fightCacheDirty = new AtomicBoolean();

    public PlayerStatsService(LokaApi loka, EldritchApi eldritch, TownCache towns) {
        this(loka, eldritch, towns, null);
    }

    /** @param fightCacheFile where to keep parsed fight breakdowns, or {@code null} not to. */
    public PlayerStatsService(LokaApi loka, EldritchApi eldritch, TownCache towns, Path fightCacheFile) {
        this.loka = loka;
        this.eldritch = eldritch;
        this.towns = towns;
        this.fightDisk = fightCacheFile == null
                ? null
                : new JsonStore<>(fightCacheFile, FIGHT_CACHE_TYPE, FIGHT_TTL_MILLIS);
        if (fightDisk != null) {
            Map<String, FightDetail> saved = fightDisk.read();
            if (saved != null) {
                fightCache.putAll(saved);
            }
        }
    }

    /** One player's line in one fight; both halves are needed, since the breakdown is per player. */
    private static String fightKey(String fightId, String player) {
        return fightId + "|" + player.toLowerCase(java.util.Locale.ROOT);
    }

    /** Reads a fight's breakdown, from disk if it has been seen before. */
    private FightDetail fightDetail(String fightId, String player) throws ApiException {
        String key = fightKey(fightId, player);
        FightDetail cached = fightCache.get(key);
        if (cached != null) {
            return cached;
        }
        FightDetail detail = eldritch.fetchFight(fightId, player);
        if (detail != null) {
            fightCache.put(key, detail);
            fightCacheDirty.set(true);
        }
        return detail;
    }

    /** Writes the fight cache back out, trimmed, once a profile has finished loading. */
    private void saveFightCache() {
        if (fightDisk == null || !fightCacheDirty.getAndSet(false)) {
            return;
        }
        Map<String, FightDetail> toSave = fightCache;
        if (toSave.size() > MAX_CACHED_FIGHTS) {
            // No access order to work from, so drop an arbitrary half rather than grow forever.
            Map<String, FightDetail> trimmed = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, FightDetail> entry : toSave.entrySet()) {
                if (trimmed.size() >= MAX_CACHED_FIGHTS / 2) {
                    break;
                }
                trimmed.put(entry.getKey(), entry.getValue());
            }
            fightCache.keySet().retainAll(trimmed.keySet());
            toSave = trimmed;
        }
        fightDisk.write(new java.util.LinkedHashMap<>(toSave));
    }

    /**
     * Runs the lookup off the render thread.
     *
     * @param onHeadline called with the profile as soon as the career totals resolve, before the
     *                   per-fight breakdown is fetched. Not called if the player does not exist.
     * @return the completed profile, fights included. Fails with {@link ApiException} when neither
     * EldritchBot nor Loka knows the name.
     */
    public CompletableFuture<PlayerProfile> lookup(String name, Consumer<PlayerProfile> onHeadline) {
        CompletableFuture<PlayerProfile> headline = CompletableFuture.supplyAsync(() -> {
            try {
                return buildHeadline(name);
            } catch (ApiException e) {
                throw new CompletionException(e);
            }
        }, eldritch.executor());

        headline.thenAccept(onHeadline);

        return headline.thenApplyAsync(this::withFightDetail, eldritch.executor());
    }

    private PlayerProfile buildHeadline(String name) throws ApiException {
        // Loka's record (rank, account age) is fetched alongside EldritchBot's rather than after it:
        // the two services are independent, and the headline is only as fast as the slower one.
        CompletableFuture<LokaPlayer> lokaLookup = CompletableFuture.supplyAsync(() -> {
            try {
                return loka.findPlayerByName(name);
            } catch (ApiException e) {
                // Optional: a player EldritchBot knows but Loka's API has dropped still gets a profile.
                BetterLoka.LOGGER.debug("Loka has no player record for {}", name, e);
                return null;
            }
        }, eldritch.bulkExecutor());

        EldritchStats stats = eldritch.fetchStats(name);
        LokaPlayer lokaPlayer = lokaLookup.join();

        UUID uuid = lokaPlayer != null ? lokaPlayer.uuid() : null;
        Instant firstSeen = lokaPlayer != null ? lokaPlayer.firstSeen() : null;
        String rank = lokaPlayer != null ? lokaPlayer.rank() : null;

        List<FightSummary> pending = new ArrayList<>();
        for (EldritchStats.RecentFight ref : stats.recentFights()) {
            if (pending.size() >= FETCHED_FIGHT_COUNT) {
                break;
            }
            pending.add(FightSummary.pending(ref));
        }

        // The newest row already names the side they fought on, so "fighting for" is right from the
        // first frame rather than waiting on the fight pages.
        String fightingFor = pending.isEmpty() ? stats.town() : pending.get(0).ownTown();
        if (fightingFor == null) {
            fightingFor = stats.town();
        }

        // Town details and the live-battle check are deliberately absent here: they are several more
        // requests for decoration, and the headline should not wait on them.
        return new PlayerProfile(stats.name(), rank, uuid, firstSeen, null, stats.town(), stats,
                fightingFor, false, List.copyOf(pending),
                pending.isEmpty() ? PlayerProfile.FightsState.READY : PlayerProfile.FightsState.LOADING);
    }

    /** The town roster and the in-fight flag, both off the headline's critical path. */
    private LokaTown resolveTown(PlayerProfile profile) {
        try {
            if (profile.uuid() != null) {
                LokaPlayer player = loka.findPlayerByUuid(profile.uuid());
                LokaTown byMember = loka.findTownByMember(player.identityId());
                if (byMember != null) {
                    return byMember;
                }
            }
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not resolve town for {}", profile.name(), e);
        }
        return profile.townName() == null ? null : towns.byName(profile.townName());
    }

    /** Fetches each listed fight's page in parallel and folds the player's line into the summary. */
    private PlayerProfile withFightDetail(PlayerProfile profile) {
        if (profile.recentFights().isEmpty()) {
            return profile;
        }

        CompletableFuture<LokaTown> townLookup =
                CompletableFuture.supplyAsync(() -> resolveTown(profile), eldritch.bulkExecutor());
        CompletableFuture<Boolean> fighting = CompletableFuture.supplyAsync(
                () -> isInFightNow(profile.name(), profile.uuid()), eldritch.bulkExecutor());

        List<FightSummary> rows = profile.recentFights();
        List<CompletableFuture<FightSummary>> tasks = new ArrayList<>(rows.size());
        for (FightSummary row : rows) {
            tasks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    FightDetail detail = fightDetail(row.ref().id(), profile.name());
                    return detail == null ? row : new FightSummary(row.ref(), detail);
                } catch (ApiException e) {
                    BetterLoka.LOGGER.debug("Could not load fight {}", row.ref().id(), e);
                    return row;
                }
            }, eldritch.bulkExecutor()));
        }

        List<FightSummary> resolved = new ArrayList<>(rows.size());
        boolean anyDetail = false;
        for (CompletableFuture<FightSummary> task : tasks) {
            FightSummary summary = task.join();
            anyDetail |= summary.hasDetail();
            resolved.add(summary);
        }

        // The town on the newest fight is who they are actually fighting for, which is not always
        // the town they are a member of.
        String fightingFor = profile.fightingFor();
        for (FightSummary summary : resolved) {
            if (summary.ownTown() != null) {
                fightingFor = summary.ownTown();
                break;
            }
        }

        saveFightCache();

        return new PlayerProfile(profile.name(), profile.rank(), profile.uuid(), profile.firstSeen(),
                townLookup.join(), profile.townName(), profile.stats(), fightingFor, fighting.join(),
                List.copyOf(resolved),
                anyDetail ? PlayerProfile.FightsState.READY : PlayerProfile.FightsState.UNAVAILABLE);
    }

    /**
     * The other accounts this person plays on.
     *
     * <p>Loka groups accounts under a shared identity, and that grouping is what its own
     * {@code /find} reports. Two small requests, off the profile's critical path, and the searched
     * account itself is left out of the result — it is not its own alt.
     */
    public CompletableFuture<List<PlayerIdentity.Account>> alts(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LokaPlayer player = loka.findPlayerByName(name);
                if (player == null || player.identityId() == null) {
                    return List.<PlayerIdentity.Account>of();
                }
                List<PlayerIdentity.Account> accounts = new ArrayList<>();
                for (LokaPlayer account : loka.findAccountsByIdentity(player.identityId())) {
                    if (account.name() != null && !account.name().equalsIgnoreCase(player.name())) {
                        accounts.add(new PlayerIdentity.Account(
                                account.name(), account.uuid(), account.rank()));
                    }
                }
                return List.copyOf(accounts);
            } catch (ApiException e) {
                BetterLoka.LOGGER.debug("Could not resolve alts for {}", name, e);
                return List.<PlayerIdentity.Account>of();
            }
        }, eldritch.bulkExecutor());
    }

    /** EldritchBot only publishes finished fights, so a battle in progress has to come from Loka. */
    private boolean isInFightNow(String name, UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            for (BattleZone battle : loka.fetchActiveBattles()) {
                for (BattleParticipant participant : battle.participants()) {
                    if (uuid.equals(participant.uuid())) {
                        return true;
                    }
                }
            }
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not check active battles for {}", name, e);
        }
        return false;
    }
}
