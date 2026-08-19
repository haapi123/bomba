package com.betterloka.stats;

import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;

import java.time.Instant;
import java.util.List;

/** Everything the Player Finder shows about one player. */
public record PlayerProfile(
        LokaPlayer player,
        /** The town they are a member of, or {@code null} if townless. */
        LokaTown town,
        /**
         * The town they most recently fought for. Loka lets players reinforce other towns, so this
         * is not always {@link #town()}.
         */
        String fightingFor,
        Instant firstSeen,
        int kills,
        int deaths,
        int battlesFought,
        List<FightSummary> recentFights,
        StatsState statsState) {

    /** Where the combat half of the profile stands. Identity and town resolve in one request; the
     * battle history behind the kill counts can still be downloading. */
    public enum StatsState {
        /** The battle history is still syncing — the combat numbers are not meaningful yet. */
        PENDING,
        READY,
        /** The battle history could not be fetched and nothing was cached from a previous run. */
        UNAVAILABLE
    }

    /** An identity-only profile, shown while the battle history is still downloading. */
    public static PlayerProfile identityOnly(LokaPlayer player, LokaTown town) {
        return new PlayerProfile(player, town, null, player.firstSeen(), 0, 0, 0, List.of(), StatsState.PENDING);
    }

    public PlayerProfile withStatsState(StatsState state) {
        return new PlayerProfile(player, town, fightingFor, firstSeen, kills, deaths, battlesFought,
                recentFights, state);
    }

    /** Kills per death; with no deaths on record this is simply the kill count, as in-game. */
    public double killDeathRatio() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    public boolean statsReady() {
        return statsState == StatsState.READY;
    }
}
