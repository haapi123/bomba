package com.betterloka.api.model;

import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * One player's line in a battle. Note that the API leaves {@code uuid}, {@code identityId} and
 * {@code townId} null on older records — the participant map is keyed by UUID, so the key is the
 * authoritative identifier and the inline {@code uuid} field is not.
 */
public final class BattleParticipant {
    /** The player signed up for the battle but never showed. */
    public static final String STATE_REGISTERED = "REGISTERED";
    /** The player actually fought. */
    public static final String STATE_PARTICIPATED = "PARTICIPATED";

    private final UUID uuid;
    private final int kills;
    private final int deaths;
    private final boolean attacker;
    private final boolean participated;
    private final String townId;

    public BattleParticipant(UUID uuid, int kills, int deaths, boolean attacker, boolean participated, String townId) {
        this.uuid = uuid;
        this.kills = kills;
        this.deaths = deaths;
        this.attacker = attacker;
        this.participated = participated;
        this.townId = townId;
    }

    public static BattleParticipant fromJson(String uuidKey, JsonObject json, boolean attacker) {
        UUID uuid = Json.parseUuid(uuidKey);
        if (uuid == null) {
            uuid = Json.uuid(json, "uuid");
        }
        if (uuid == null) {
            return null;
        }
        String townId = Json.string(json, "townId");
        return new BattleParticipant(
                uuid,
                Json.integer(json, "kills", 0),
                Json.integer(json, "deaths", 0),
                attacker,
                STATE_PARTICIPATED.equals(Json.string(json, "participationState")),
                townId == null ? null : townId.intern());
    }

    public UUID uuid() {
        return uuid;
    }

    public int kills() {
        return kills;
    }

    public int deaths() {
        return deaths;
    }

    public boolean attacker() {
        return attacker;
    }

    /**
     * Whether the player was actually in the fight, as opposed to merely registered for it. Only
     * participants count towards a battle's headcount.
     */
    public boolean participated() {
        return participated;
    }

    /**
     * The town this player fought for in this battle. Loka lets players reinforce towns other than
     * their own, so this is not necessarily the town they are a member of.
     */
    public String townId() {
        return townId;
    }
}
