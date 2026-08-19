package com.betterloka.stats;

/**
 * One line of a player's recent fight history.
 *
 * <p>Head counts are the number of players who actually fought, not the number who signed up —
 * Loka's battle records keep both, and the sign-up figure routinely overstates a fight by a third.
 */
public record FightSummary(
        String territory,
        long timeEnded,
        String attackerTown,
        String defenderTown,
        int attackerCount,
        int defenderCount,
        boolean playerAttacked,
        String foughtForTown,
        int kills,
        int deaths,
        boolean live) {

    /** The player's own side's head count. */
    public int ownSideCount() {
        return playerAttacked ? attackerCount : defenderCount;
    }

    /** The opposing side's head count. */
    public int enemySideCount() {
        return playerAttacked ? defenderCount : attackerCount;
    }

    public String enemyTown() {
        return playerAttacked ? defenderTown : attackerTown;
    }
}
