package com.betterloka.stats;

import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.FightDetail;

/**
 * One row of a player's recent fight history.
 *
 * <p>A player's EldritchBot page lists which fights they were in and how each ended, but the
 * per-player numbers live on the individual fight pages. The row therefore renders as soon as the
 * profile loads and gains its kills and deaths a moment later, when {@link #detail()} arrives.
 */
public record FightSummary(EldritchStats.RecentFight ref, FightDetail detail) {

    public static FightSummary pending(EldritchStats.RecentFight ref) {
        return new FightSummary(ref, null);
    }

    public boolean hasDetail() {
        return detail != null;
    }

    public String date() {
        return ref.date();
    }

    public boolean victory() {
        return ref.victory();
    }

    /** Territory name once the detail lands, falling back to the two towns before that. */
    public String location() {
        return detail != null && detail.location() != null ? detail.location() : null;
    }

    /**
     * The town the player fought for. Known from the player page's own row, so it is available
     * before the fight page loads; the detail confirms it.
     */
    public String ownTown() {
        if (detail != null && detail.playerTown() != null) {
            return detail.playerTown();
        }
        return ref.ownTown();
    }

    public String enemyTown() {
        String own = ownTown();
        return own != null ? ref.opponentOf(own) : ref.opponent();
    }

    public int ownSideCount() {
        return detail == null ? 0 : detail.ownSideCount();
    }

    public int enemySideCount() {
        return detail == null ? 0 : detail.enemySideCount();
    }

    public int kills() {
        return detail == null ? 0 : detail.kills();
    }

    public int deaths() {
        return detail == null ? 0 : detail.deaths();
    }

    public int assists() {
        return detail == null ? 0 : detail.assists();
    }

    public int golemKills() {
        return detail == null ? 0 : detail.golemKills();
    }

    public int lamps() {
        return detail == null ? 0 : detail.lamps();
    }
}
