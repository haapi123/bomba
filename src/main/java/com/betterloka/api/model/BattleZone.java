package com.betterloka.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A single Conquest battle. This is the only place the Loka API exposes per-player kills and
 * deaths, so every combat statistic BetterLoka shows is aggregated out of these records.
 */
public final class BattleZone {
    private final String id;
    private final String territory;
    private final long timeStarted;
    private final long timeEnded;
    private final boolean active;
    private final String attackerTownId;
    private final String defenderTownId;
    private final String attackerName;
    private final String defenderName;
    private final List<BattleParticipant> participants;
    private final int attackerCount;
    private final int defenderCount;

    public BattleZone(String id, String territory, long timeStarted, long timeEnded, boolean active,
                      String attackerTownId, String defenderTownId, String attackerName, String defenderName,
                      List<BattleParticipant> participants, int attackerCount, int defenderCount) {
        this.id = id;
        this.territory = territory;
        this.timeStarted = timeStarted;
        this.timeEnded = timeEnded;
        this.active = active;
        this.attackerTownId = attackerTownId;
        this.defenderTownId = defenderTownId;
        this.attackerName = attackerName;
        this.defenderName = defenderName;
        this.participants = participants;
        this.attackerCount = attackerCount;
        this.defenderCount = defenderCount;
    }

    public static BattleZone fromJson(JsonObject json) {
        String id = extractId(json);
        if (id == null) {
            return null;
        }

        List<BattleParticipant> participants = new ArrayList<>();
        int attackerCount = readSide(json, "attackingPlayers", true, participants);
        int defenderCount = readSide(json, "defendingPlayers", false, participants);

        String attackerTownId = Json.string(json, "attackerTownId");
        String defenderTownId = Json.string(json, "defenderTownId");
        return new BattleZone(
                id,
                Json.string(json, "name"),
                Json.longValue(json, "timeStarted", 0L),
                Json.longValue(json, "timeEnded", 0L),
                Json.bool(json, "active", false),
                attackerTownId == null ? null : attackerTownId.intern(),
                defenderTownId == null ? null : defenderTownId.intern(),
                Json.string(json, "attackerName"),
                Json.string(json, "defenderName"),
                List.copyOf(participants),
                attackerCount,
                defenderCount);
    }

    /** @return how many of that side actually fought. */
    private static int readSide(JsonObject json, String key, boolean attacker, List<BattleParticipant> out) {
        JsonObject side = Json.object(json, key);
        if (side == null) {
            return 0;
        }
        int participated = 0;
        for (Map.Entry<String, JsonElement> entry : side.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            BattleParticipant participant =
                    BattleParticipant.fromJson(entry.getKey(), entry.getValue().getAsJsonObject(), attacker);
            if (participant == null) {
                continue;
            }
            out.add(participant);
            if (participant.participated()) {
                participated++;
            }
        }
        return participated;
    }

    /**
     * The battle's Mongo ID is not a top level field — it only appears in the HAL self link, and it
     * is the only stable key available for de-duplicating pages during a sync.
     */
    private static String extractId(JsonObject json) {
        JsonObject links = Json.object(json, "_links");
        JsonObject self = links == null ? null : Json.object(links, "self");
        String href = self == null ? null : Json.string(self, "href");
        if (href == null) {
            return null;
        }
        int slash = href.lastIndexOf('/');
        if (slash < 0 || slash == href.length() - 1) {
            return null;
        }
        return href.substring(slash + 1);
    }

    public String id() {
        return id;
    }

    /** The territory the battle was fought over, e.g. {@code south-14}. */
    public String territory() {
        return territory;
    }

    public long timeStarted() {
        return timeStarted;
    }

    public long timeEnded() {
        return timeEnded;
    }

    public boolean active() {
        return active;
    }

    public String attackerTownId() {
        return attackerTownId;
    }

    public String defenderTownId() {
        return defenderTownId;
    }

    /** Only populated on older records; resolve {@link #attackerTownId()} instead when null. */
    public String attackerName() {
        return attackerName;
    }

    /** Only populated on older records; resolve {@link #defenderTownId()} instead when null. */
    public String defenderName() {
        return defenderName;
    }

    public List<BattleParticipant> participants() {
        return participants;
    }

    public int attackerCount() {
        return attackerCount;
    }

    public int defenderCount() {
        return defenderCount;
    }
}
