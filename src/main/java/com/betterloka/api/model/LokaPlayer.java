package com.betterloka.api.model;

import com.betterloka.api.ArenaApi;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player as returned by {@code /players/search/findByName} and friends.
 *
 * <p>The API exposes identity data here and this season's ranked 1v1 record ({@code rankedStats}) —
 * no other combat statistics. Everything kill/death related is derived from {@link BattleZone}
 * records instead.
 */
public final class LokaPlayer {
    /** This season on one ranked ladder, as the player's own record has it. */
    public record Ranked(ArenaRank rank, int wins, int losses, int streak) {
        public int games() {
            return wins + losses;
        }
    }

    private final String id;
    private final String identityId;
    private final String name;
    private final String rank;
    private final UUID uuid;
    private final String townName;
    private final Map<ArenaApi.Ladder, Ranked> ranked;

    private LokaPlayer(String id, String identityId, String name, String rank, UUID uuid, String townName,
                       Map<ArenaApi.Ladder, Ranked> ranked) {
        this.id = id;
        this.identityId = identityId;
        this.name = name;
        this.rank = rank;
        this.uuid = uuid;
        this.townName = townName;
        this.ranked = ranked;
    }

    public static LokaPlayer fromJson(JsonObject json) {
        return new LokaPlayer(
                Json.string(json, "id"),
                Json.string(json, "identityId"),
                Json.string(json, "name"),
                Json.string(json, "rank"),
                Json.uuid(json, "uuid"),
                json.has("town") && json.get("town").isJsonObject()
                        ? Json.string(json.getAsJsonObject("town"), "name")
                        : null,
                rankedOf(Json.object(json, "rankedStats")));
    }

    /** {@code rankedStats}: one object per ladder the player has played this season. */
    static Map<ArenaApi.Ladder, Ranked> rankedOf(JsonObject stats) {
        Map<ArenaApi.Ladder, Ranked> out = new EnumMap<>(ArenaApi.Ladder.class);
        if (stats == null) {
            return out;
        }
        for (ArenaApi.Ladder ladder : ArenaApi.Ladder.values()) {
            JsonObject one = Json.object(stats, ladder.profileKey());
            if (one != null) {
                out.put(ladder, new Ranked(ArenaRank.parse(Json.string(one, "rank")),
                        Json.integer(one, "wins", 0), Json.integer(one, "losses", 0), Json.integer(one, "streak", 0)));
            }
        }
        return out;
    }

    /** This season's ranked record per ladder they have played; empty if none. */
    public Map<ArenaApi.Ladder, Ranked> ranked() {
        return ranked;
    }

    public String id() {
        return id;
    }

    /**
     * The identity shared by all of this person's accounts. Town membership is keyed by this,
     * not by {@link #id()}.
     */
    public String identityId() {
        return identityId;
    }

    public String name() {
        return name;
    }

    public String rank() {
        return rank;
    }

    public UUID uuid() {
        return uuid;
    }

    /**
     * Town name inlined on the player document. Frequently {@code null} even for players who are
     * in a town, so treat it as a hint only and prefer a {@code /towns/search/findByMember} lookup.
     */
    public String inlineTownName() {
        return townName;
    }

    /**
     * When this person first appeared on Loka, read out of the embedded timestamp of their identity
     * ObjectID. Mongo puts the creation time in the first four bytes of every ObjectID, and the
     * identity points at the person's oldest account, so this is the creation date of their first
     * ever Loka profile.
     */
    public Instant firstSeen() {
        Instant fromIdentity = ObjectIds.timestamp(identityId);
        return fromIdentity != null ? fromIdentity : ObjectIds.timestamp(id);
    }

    /** When this particular account was created, which differs from {@link #firstSeen()} for alts. */
    public Instant accountCreated() {
        return ObjectIds.timestamp(id);
    }
}
