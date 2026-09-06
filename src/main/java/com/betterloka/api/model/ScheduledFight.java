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
                             String attackerName, String defenderName,
                             Side attackers, Side defenders,
                             boolean reinforcementsAllowed, boolean started,
                             long timeStarted, double attackerStrength, double defenderStrength) {

    /**
     * One side's turnout: who signed up, and who actually came.
     *
     * <p>Loka records a {@code participationState} against each player — {@code REGISTERED} for
     * somebody down for the fight, {@code ENTERED} and {@code PARTICIPATED} for somebody who
     * actually warped in. Loka publishes nothing about who is online, so those two numbers are what
     * the screen can honestly put a slash between.
     *
     * @param present  players who warped in
     * @param signedUp everybody on the sheet, present or not
     */
    public record Side(int present, int signedUp) {
        static Side of(JsonObject json, String key) {
            JsonObject side = Json.object(json, key);
            if (side == null) {
                return new Side(0, 0);
            }
            int present = 0;
            for (var entry : side.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                String state = Json.string(entry.getValue().getAsJsonObject(),
                        "participationState");
                if ("ENTERED".equals(state) || "PARTICIPATED".equals(state)) {
                    present++;
                }
            }
            return new Side(present, side.size());
        }
    }

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
                // Loka names both sides on the record itself, colour codes and all.
                com.betterloka.api.HtmlText.plain(Json.string(json, "attackerName")),
                com.betterloka.api.HtmlText.plain(Json.string(json, "defenderName")),
                Side.of(json, "attackingPlayers"),
                Side.of(json, "defendingPlayers"),
                Json.bool(json, "reins", false),
                Json.bool(json, "started", false),
                Json.longValue(json, "timeStarted", 0),
                Json.doubleValue(json, "attackerStrength", 0),
                Json.doubleValue(json, "defenderStrength", 0));
    }

    public int attackerCount() {
        return attackers.present();
    }

    public int defenderCount() {
        return defenders.present();
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
        return attackers.present() + defenders.present();
    }

    /**
     * The fight's own name, made readable.
     *
     * <p>Loka writes these as identifiers — {@code lilboi-9}, {@code the_verdant_hallows} — and a
     * raw one on screen tells nobody anything. Used only when the sides cannot be named.
     */
    public String readableName() {
        String source = relocatedName != null && !relocatedName.isBlank() ? relocatedName : name;
        if (source == null || source.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder(source.length());
        boolean startOfWord = true;
        for (char character : source.toCharArray()) {
            if (character == '_' || character == '-') {
                out.append(' ');
                startOfWord = true;
                continue;
            }
            out.append(startOfWord ? Character.toUpperCase(character) : character);
            startOfWord = false;
        }
        return out.toString();
    }
}
