package com.betterloka.api.model;

import com.google.gson.JsonObject;

/**
 * A battle Loka has on its books: declared and waiting, or already under way.
 *
 * <p>Loka publishes no start time — {@code timeStarted} stays zero until the fight actually begins —
 * so when a declared fight will happen is worked out from the defending town's vulnerability window,
 * which is the rule that decides it.
 */
public record ScheduledFight(String territoryId, String name, String world, String relocatedName,
                             String attackerTownId, String attackerAllianceId,
                             String defenderTownId, String defenderAllianceId,
                             int attackerCount, int defenderCount,
                             boolean reinforcementsAllowed, boolean started,
                             long timeStarted, double attackerStrength, double defenderStrength) {

    public static ScheduledFight fromJson(JsonObject json) {
        return new ScheduledFight(
                Json.string(json, "territoryId"),
                Json.string(json, "name"),
                Json.string(json, "world"),
                Json.string(json, "relocatedFightName"),
                Json.string(json, "attackerTownId"),
                Json.string(json, "attackerAllianceId"),
                Json.string(json, "defenderTownId"),
                Json.string(json, "defenderAllianceId"),
                count(json, "attackingPlayers"),
                count(json, "defendingPlayers"),
                Json.bool(json, "reins", false),
                Json.bool(json, "started", false),
                Json.longValue(json, "timeStarted", 0),
                Json.doubleValue(json, "attackerStrength", 0),
                Json.doubleValue(json, "defenderStrength", 0));
    }

    /** The players on one side are a map keyed by UUID, so the head count is its size. */
    private static int count(JsonObject json, String key) {
        JsonObject side = Json.object(json, key);
        return side == null ? 0 : side.size();
    }

    public String continent() {
        return Territory.continentOf(world);
    }

    /** The territory number, taken off the battle's name — Loka writes it {@code west-191}. */
    public String territoryNumber() {
        if (name == null) {
            return null;
        }
        int dash = name.lastIndexOf('-');
        return dash < 0 || dash == name.length() - 1 ? null : name.substring(dash + 1);
    }

    public int total() {
        return attackerCount + defenderCount;
    }
}
