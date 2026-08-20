package com.betterloka.api.model;

import com.google.gson.JsonObject;

/**
 * One Conquest territory: where it is, what it is called, and which town holds it.
 *
 * <p>{@code townId} outlives the town. When a town is deleted Loka does not clear the territories it
 * held — they keep pointing at an id that no longer resolves — which is what makes a fallen town
 * identifiable at all rather than merely a territory that changed hands.
 */
public record Territory(String id, String world, String num, String areaName, String townId,
                        int x, int y, int z, long lastBattle) {

    public static Territory fromJson(JsonObject json) {
        JsonObject tg = Json.object(json, "tg");
        int[] beacon = parseBeacon(tg == null ? null : Json.string(tg, "beacon"));
        return new Territory(
                Json.string(json, "id"),
                Json.string(json, "world"),
                Json.string(json, "num"),
                Json.string(json, "areaName"),
                tg == null ? null : Json.string(tg, "townId"),
                beacon[0], beacon[1], beacon[2],
                tg == null ? 0 : Json.longValue(tg, "lastBattle", 0));
    }

    /**
     * The beacon is {@code "<world>,<x>,<y>,<z>"}, with the numbers sometimes whole and sometimes
     * decimal, so they are read as doubles and rounded.
     */
    private static int[] parseBeacon(String beacon) {
        int[] coords = new int[3];
        if (beacon == null) {
            return coords;
        }
        String[] parts = beacon.split(",");
        if (parts.length < 4) {
            return coords;
        }
        for (int i = 0; i < 3; i++) {
            try {
                coords[i] = (int) Math.round(Double.parseDouble(parts[i + 1].trim()));
            } catch (NumberFormatException e) {
                coords[i] = 0;
            }
        }
        return coords;
    }

    /** Stable across polls, and readable in the saved log: {@code north/74}. */
    public String key() {
        return world + "/" + num;
    }

    public boolean isOwned() {
        return townId != null && !townId.isEmpty();
    }

    public String coordinates() {
        return x + ", " + y + ", " + z;
    }

    /** Loka's internal world keys map to the in-game continent names. */
    public String continent() {
        return continentOf(world);
    }

    public static String continentOf(String world) {
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
