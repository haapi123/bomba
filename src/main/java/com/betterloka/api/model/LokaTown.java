package com.betterloka.api.model;

import com.google.gson.JsonObject;

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

    private LokaTown(String id, String name, String world, String slogan, double townLevel,
                     double strength, boolean recruiting, boolean deleted, int memberCount) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.slogan = slogan;
        this.townLevel = townLevel;
        this.strength = strength;
        this.recruiting = recruiting;
        this.deleted = deleted;
        this.memberCount = memberCount;
    }

    /**
     * A town that only exists as a name any more — restored from the saved log, where nothing is
     * kept but what a deleted town can no longer be asked for.
     */
    public static LokaTown deleted(String id, String name, String world) {
        return new LokaTown(id, name, world, null, 0, 0, false, true, 0);
    }

    public static LokaTown fromJson(JsonObject json) {
        JsonObject members = Json.object(json, "members");
        return new LokaTown(
                Json.string(json, "id"),
                Json.string(json, "name"),
                Json.string(json, "world"),
                Json.string(json, "slogan"),
                Json.doubleValue(json, "townLevel", 0),
                Json.doubleValue(json, "strength", 0),
                Json.bool(json, "recruiting", false),
                Json.bool(json, "deleted", false),
                members == null ? 0 : members.size());
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
