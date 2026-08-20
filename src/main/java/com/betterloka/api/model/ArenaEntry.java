package com.betterloka.api.model;

import com.google.gson.JsonObject;

import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * One player's line on a ranked 1v1 leaderboard.
 *
 * <p>The site publishes a snapshot per ladder, and for past seasons one per week; the weekly
 * snapshots are cumulative, so a season's last week is how that season finished.
 */
public record ArenaEntry(String name, UUID uuid, int wins, int losses, ArenaRank rank, int streak,
                         int surplus, int position) {

    /**
     * @param statsKey which ladder's block to read — {@code potionranked} or {@code barebonesranked}
     * @param position the row's place in the standings, 1-based
     * @return the entry, or {@code null} if the row carries no record for this ladder
     */
    public static ArenaEntry fromJson(JsonObject json, String statsKey, int position) {
        JsonObject stats = Json.object(Json.object(json, "arenaStats"), statsKey);
        if (stats == null) {
            return null;
        }
        return new ArenaEntry(
                Json.string(json, "name"),
                decodeUuid(Json.string(json, "uuid")),
                Json.integer(stats, "wins", 0),
                Json.integer(stats, "losses", 0),
                ArenaRank.parse(Json.string(stats, "rank")),
                Json.integer(stats, "streak", 0),
                Json.integer(json, "surplus", 0),
                position);
    }

    /** Duels played: the ladder publishes wins and losses, and 1v1 has no draws. */
    public int duels() {
        return wins + losses;
    }

    /** @return the share of duels won, 0 to 1, or 0 when they have not played any. */
    public double winRatio() {
        int played = duels();
        return played == 0 ? 0 : (double) wins / played;
    }

    public String winRatioText() {
        return String.format(Locale.ROOT, "%.1f%%", winRatio() * 100);
    }

    /**
     * The leaderboard sends UUIDs as base64 of the raw sixteen bytes, with each half in little-endian
     * order — Java's {@code UUID} written out as two longs. Names change between seasons, so this is
     * the only stable way to follow one player across them.
     *
     * @return the UUID, or {@code null} if the field is missing or malformed
     */
    public static UUID decodeUuid(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length != 16) {
            return null;
        }
        return new UUID(littleEndianLong(bytes, 0), littleEndianLong(bytes, 8));
    }

    private static long littleEndianLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }
}
