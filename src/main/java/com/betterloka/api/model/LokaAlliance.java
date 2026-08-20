package com.betterloka.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** An alliance and the towns in it, as returned by {@code /alliances}. */
public record LokaAlliance(String id, String name, double strength, int vulnerabilityWindow,
                           String leaderTownId, List<String> townIds) {

    public static LokaAlliance fromJson(JsonObject json) {
        List<String> towns = new ArrayList<>();
        JsonElement array = json.get("townIds");
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    towns.add(element.getAsString());
                }
            }
        }
        return new LokaAlliance(
                Json.string(json, "id"),
                Json.string(json, "name"),
                Json.doubleValue(json, "strength", 0),
                Json.integer(json, "vulnerabilityWindow", -1),
                Json.string(json, "leaderId"),
                List.copyOf(towns));
    }

    public boolean contains(String townId) {
        return townId != null && townIds.contains(townId);
    }
}
