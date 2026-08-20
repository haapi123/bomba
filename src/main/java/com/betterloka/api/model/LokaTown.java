package com.betterloka.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A town as returned by {@code /towns} and its search endpoints. */
public final class LokaTown {
    private final String id;
    private final String name;
    private final String world;
    private final String slogan;
    private final double townLevel;
    private final double strength;
    private final boolean recruiting;
    private final boolean deleted;
    private final int memberCount;
    private final int vulnerabilityWindow;
    private final String territoryNum;
    private final String ownerId;
    private final List<String> subOwnerIds;

    private LokaTown(String id, String name, String world, String slogan, double townLevel,
                     double strength, boolean recruiting, boolean deleted, int memberCount,
                     int vulnerabilityWindow, String territoryNum, String ownerId,
                     List<String> subOwnerIds) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.slogan = slogan;
        this.townLevel = townLevel;
        this.strength = strength;
        this.recruiting = recruiting;
        this.deleted = deleted;
        this.memberCount = memberCount;
        this.vulnerabilityWindow = vulnerabilityWindow;
        this.territoryNum = territoryNum;
        this.ownerId = ownerId;
        this.subOwnerIds = subOwnerIds;
    }

    /**
     * A town that only exists as a name any more — restored from the saved log, where nothing is
     * kept but what a deleted town can no longer be asked for.
     */
    public static LokaTown deleted(String id, String name, String world) {
        return new LokaTown(id, name, world, null, 0, 0, false, true, 0, -1, null, null, List.of());
    }

    public static LokaTown fromJson(JsonObject json) {
        JsonObject members = Json.object(json, "members");
        List<String> subOwners = new ArrayList<>();
        if (members != null) {
            for (Map.Entry<String, JsonElement> member : members.entrySet()) {
                if (member.getValue().isJsonObject()
                        && Json.bool(member.getValue().getAsJsonObject(), "subowner", false)) {
                    subOwners.add(member.getKey());
                }
            }
        }
        return new LokaTown(
                Json.string(json, "id"),
                Json.string(json, "name"),
                Json.string(json, "world"),
                Json.string(json, "slogan"),
                Json.doubleValue(json, "townLevel", 0),
                Json.doubleValue(json, "strength", 0),
                Json.bool(json, "recruiting", false),
                Json.bool(json, "deleted", false),
                members == null ? 0 : members.size(),
                Json.integer(json, "vulnerabilityWindow", -1),
                Json.string(json, "territoryNum"),
                Json.string(json, "owner"),
                List.copyOf(subOwners));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String world() {
        return world;
    }

    public String slogan() {
        return slogan;
    }

    public double townLevel() {
        return townLevel;
    }

    public double strength() {
        return strength;
    }

    public boolean recruiting() {
        return recruiting;
    }

    public boolean deleted() {
        return deleted;
    }

    public int memberCount() {
        return memberCount;
    }

    /**
     * The hour of the day the town is attackable from, on Loka's clock, or {@code -1} if unknown.
     *
     * <p>Only the start is published. Across the live roster the values run 9 to 20 and pile up at 9
     * and 19, which is the European and American prime time — so this is an hour, not a duration.
     */
    public int vulnerabilityWindow() {
        return vulnerabilityWindow;
    }

    /** The town's home territory number. */
    public String territoryNum() {
        return territoryNum;
    }

    /** Identity ID of the town's owner. Resolve through {@code players/search/findByIdentityId}. */
    public String ownerId() {
        return ownerId;
    }

    /** Identity IDs of the members flagged as sub-owners. */
    public List<String> subOwnerIds() {
        return subOwnerIds;
    }

    /** Loka's internal world keys map to the in-game continent names. */
    public String continentName() {
        if (world == null) {
            return null;
        }
        return switch (world) {
            case "north" -> "Kalros";
            case "west" -> "Ascalon";
            case "south" -> "Garama";
            default -> world;
        };
    }
}
