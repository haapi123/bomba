package com.betterloka.stats;

import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.LokaTown;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything the Player Finder shows about one player. */
public record PlayerProfile(
        String name,
        /**
         * The name EldritchBot files the career under, which is whatever they were called at their
         * last fight. Differs from {@link #name} exactly when they have renamed since — which makes
         * it a former name, and the only one available for a player who has never duelled.
         */
        String careerName,
        /** Loka rank, e.g. {@code sentry}. Null when Loka has no record of the account. */
        String rank,
        UUID uuid,
        /** When this person first appeared on Loka, from their identity ObjectID. */
        Instant firstSeen,

        /** Full town record from Loka's API, or {@code null} if they are townless. */
        LokaTown town,
        /** Town name as EldritchBot has it — a fallback for when Loka's roster lookup comes back empty. */
        String townName,

        /** Career totals. Never null: a player with no fights gets a zeroed record. */
        EldritchStats stats,
        /** The town they most recently fought for; Loka lets players reinforce towns not their own. */
        String fightingFor,
        boolean inFightNow,

        List<FightSummary> recentFights,
        FightsState fightsState) {

    /** Where the per-fight breakdown stands; the career totals above are always present. */
    public enum FightsState {
        /** Fight pages are still downloading — rows show without their kill counts. */
        LOADING,
        READY,
        /** The fight pages could not be fetched. */
        UNAVAILABLE
    }

    /** @return the town name to display, preferring Loka's record over EldritchBot's. */
    public String displayTown() {
        if (town != null && town.name() != null) {
            return town.name();
        }
        return townName;
    }

    /** @return the career name when it is an older one, or {@code null} when it is just the name. */
    public String formerName() {
        if (careerName == null || careerName.isBlank() || careerName.equalsIgnoreCase(name)) {
            return null;
        }
        return careerName;
    }

    public boolean hasFights() {
        return stats.totalFights() > 0 || stats.kills() > 0 || stats.deaths() > 0;
    }
}
