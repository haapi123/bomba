package com.betterloka.api.model;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * What the game API says about one territory beyond who holds it: its mutator, its buff, whether
 * reinforcements may be called to it, what it produces.
 *
 * <p>Read for a territory that is being fought over, to say what the fight is for and under which
 * rules it is fought.
 *
 * @param mutator        the fight rule in force, as Loka names it — {@code CAPPED_FIGHT}, {@code DOGPILE} —
 *                       or {@code null}
 * @param buff           what holding it grants — {@code NETHER_INDUSTRY} — or {@code null}
 * @param reins          whether reinforcements may be called to a fight here
 * @param resource       what its module produces — {@code Kelp} — or {@code null}
 * @param bountifulItem  the item a bountiful capture pays out, or {@code null}
 * @param infestedType   how it is infested — {@code KOD_WILDERNESS} — or {@code null}
 */
public record TerritoryDetails(String id, String world, String num, String areaName, String townId,
                               String mutator, String buff, boolean reins, String resource,
                               int conquestPoints, String bountifulItem, String infestedType) {

    public static TerritoryDetails fromJson(JsonObject json) {
        JsonObject tg = Json.object(json, "tg");
        return new TerritoryDetails(
                Json.string(json, "id"),
                Json.string(json, "world"),
                Json.string(json, "num"),
                Json.string(json, "areaName"),
                tg == null ? null : Json.string(tg, "townId"),
                blankToNull(Json.string(json, "mutator")),
                blankToNull(Json.string(json, "buff")),
                tg != null && Json.bool(tg, "reins", false),
                tg == null ? null : blankToNull(Json.string(tg, "moduleResource")),
                Json.integer(json, "conquestPoints", 0),
                tg == null ? null : blankToNull(Json.string(tg, "capBountifulItem")),
                tg == null ? null : blankToNull(Json.string(tg, "infestedType")));
    }

    /** {@code balak_northfields} or {@code CAPPED_FIGHT} as a person would write it. */
    public static String pretty(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder(name.length());
        for (String word : name.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
