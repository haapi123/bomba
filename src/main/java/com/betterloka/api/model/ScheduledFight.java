package com.betterloka.api.model;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A battle Loka has on its books: declared and waiting, or already under way.
 *
 * <p>Loka publishes no start time — {@code timeStarted} stays zero until the fight actually begins —
 * but the record's id is a MongoDB ObjectId, which opens with the moment it was declared, and a fight
 * goes off a fixed time after that — see {@code FightTiming}.
 *
 * @param id the battle's own id, from its self link; {@code null} on a record without one
 */
public record ScheduledFight(String id, String territoryId, String name, String world, String relocatedName,
                             String attackerTownId, String attackerAllianceId,
                             String defenderTownId, String defenderAllianceId,
                             String attackerName, String defenderName,
                             Side attackers, Side defenders,
                             boolean reinforcementsAllowed, boolean started,
                             long timeStarted, double attackerStrength, double defenderStrength) {

    /**
     * One player on a fight's sheet.
     *
     * @param identityId the player's identity, which resolves to a name through
     *                   {@code /players/search/findByIdentityId}
     * @param state      {@code REGISTERED} for somebody down to warp in, {@code ENTERED} or
     *                   {@code PARTICIPATED} for somebody who did
     */
    public record Warper(String uuid, String identityId, String townId, String state, int kills, int deaths) {
        public boolean present() {
            return "ENTERED".equals(state) || "PARTICIPATED".equals(state);
        }
    }

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
     * @param players  each of them
     */
    public record Side(int present, int signedUp, List<Warper> players) {
        static Side of(JsonObject json, String key) {
            JsonObject side = Json.object(json, key);
            if (side == null) {
                return new Side(0, 0, List.of());
            }
            int present = 0;
            List<Warper> players = new ArrayList<>();
            for (var entry : side.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject player = entry.getValue().getAsJsonObject();
                Warper warper = new Warper(entry.getKey(), Json.string(player, "identityId"),
                        Json.string(player, "townId"), Json.string(player, "participationState"),
                        Json.integer(player, "kills", 0), Json.integer(player, "deaths", 0));
                players.add(warper);
                if (warper.present()) {
                    present++;
                }
            }
            return new Side(present, side.size(), List.copyOf(players));
        }
    }

    public static ScheduledFight fromJson(JsonObject json) {
        return new ScheduledFight(
                selfId(json),
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

    /** The id is not a field of its own: it is the last part of the record's self link. */
    private static String selfId(JsonObject json) {
        JsonObject links = Json.object(json, "_links");
        JsonObject self = links == null ? null : Json.object(links, "self");
        String href = self == null ? null : Json.string(self, "href");
        if (href == null) {
            return Json.string(json, "id");
        }
        int slash = href.lastIndexOf('/');
        return slash < 0 || slash == href.length() - 1 ? null : href.substring(slash + 1);
    }

    /** Whether a town holds the ground being fought over; a fight over neutral ground has nobody defending. */
    public boolean defended() {
        return defenderTownId != null && !defenderTownId.isBlank();
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

    /** Everybody down to warp in, both sides. */
    public int warping() {
        return attackers.signedUp() + defenders.signedUp();
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
