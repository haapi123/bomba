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
import com.betterloka.data.TownCache;

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

    private final LokaApi loka;
    private final EldritchApi eldritch;
    private final TownCache towns;

    public PlayerStatsService(LokaApi loka, EldritchApi eldritch, TownCache towns) {
        this.loka = loka;
        this.eldritch = eldritch;
        this.towns = towns;
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
                    FightDetail detail = eldritch.fetchFight(row.ref().id(), profile.name());
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

        return new PlayerProfile(profile.name(), profile.rank(), profile.uuid(), profile.firstSeen(),
                townLookup.join(), profile.townName(), profile.stats(), fightingFor, fighting.join(),
                List.copyOf(resolved),
                anyDetail ? PlayerProfile.FightsState.READY : PlayerProfile.FightsState.UNAVAILABLE);
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
