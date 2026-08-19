package com.betterloka.api.model;

/**
 * One player's line in one fight, read from the {@code fightData} blob EldritchBot embeds in every
 * fight page. Richer than Loka's own battle records, which carry only kills and deaths.
 */
public record FightDetail(
        String id,
        /** Biome data-name of the territory, e.g. {@code ice_taiga}. */
        String location,
        boolean attackersWon,
        int attackerCount,
        int defenderCount,

        boolean playerAttacked,
        /** The town the player fought for — not always the town they belong to. */
        String playerTown,

        int kills,
        int golemKills,
        int deaths,
        int assists,
        int lamps,
        int potions,
        int pearls,
        int closeCalls) {

    public boolean playerWon() {
        return playerAttacked == attackersWon;
    }

    public int ownSideCount() {
        return playerAttacked ? attackerCount : defenderCount;
    }

    public int enemySideCount() {
        return playerAttacked ? defenderCount : attackerCount;
    }
}
