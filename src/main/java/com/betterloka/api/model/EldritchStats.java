package com.betterloka.api.model;

import java.util.List;

/**
 * A player's career totals as published by EldritchBot, which parses Loka's Conquest 2 fight logs.
 *
 * <p>Loka's own API only exposes kills and deaths buried inside individual battle records, with no
 * way to query by player — reaching the same numbers there meant downloading the entire battle
 * history. EldritchBot has already done that aggregation, so one request replaces several hundred.
 */
public record EldritchStats(
        String name,
        /** Minecraft UUID without dashes, as EldritchBot stores it. */
        String uuid,
        String town,
        /** Human-readable date of their most recent fight, e.g. {@code Wed Aug 12 2026}. */
        String lastFight,

        int kills,
        int deaths,
        int assists,

        int food,
        int potions,
        int pearls,
        int ancientIngots,

        int wins,
        int losses,
        int golems,
        int lamps,
        int firstBloods,
        int closeCalls,

        /** The player who has killed them most, or {@code null} if EldritchBot has no pick. */
        String nemesisName,
        int nemesisDeaths,

        /** Most recent fights, newest first. Only identity and outcome — per-fight numbers live on
         * the fight pages these point at. */
        List<RecentFight> recentFights) {

    /**
     * One row of the recent fights table on a player's page.
     *
     * @param ownTown the side this player fought on. EldritchBot marks that cell with its
     *                {@code assists} class, which is the only place the page says which side they
     *                were on — and it is not always the same column.
     */
    public record RecentFight(String id, String date, boolean victory,
                              String attackerTown, String defenderTown, String ownTown) {

        /** The town on the other side of this fight. */
        public String opponent() {
            if (ownTown == null) {
                return defenderTown;
            }
            return ownTown.equals(attackerTown) ? defenderTown : attackerTown;
        }

        public String opponentOf(String town) {
            if (town == null) {
                return opponent();
            }
            return town.equals(attackerTown) ? defenderTown : attackerTown;
        }
    }

    public int totalFights() {
        return wins + losses;
    }

    /** Kills per death; with no deaths on record this is simply the kill count, as in-game. */
    public double killDeathRatio() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    /** Share of fights won, or 0 when they have never fought. */
    public double winRate() {
        int total = totalFights();
        return total == 0 ? 0 : (double) wins / total;
    }
}
