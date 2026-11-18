package com.betterloka.bot;

import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This month's conquest kills and damage per player, out of Loka's finished battles.
 *
 * <p>Every battle record has a line per player with their kills and {@code damageToPlayers}; these
 * are added up for the battles that ended this month. Rivina's battles ({@code lilboi-…}) are left
 * out unless asked for, as the mod's Player Finder keeps them apart from conquest. A battle is
 * counted once however often it is seen, and a new month starts from nothing.
 */
final class MonthTally {
    /** Rivina's battles are named after its world. */
    private static final String RIVINA_WORLD = "lilboi";

    /** The player with the most of something, and how much. */
    record Leader(UUID uuid, double value) {
    }

    private final ZoneId zone;
    private final boolean countRivina;
    private YearMonth month;
    private final Set<String> counted = new HashSet<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Double> damage = new HashMap<>();

    MonthTally(ZoneId zone, boolean countRivina) {
        this.zone = zone;
        this.countRivina = countRivina;
    }

    /** The month being counted, moving on (and starting over) when the clock has. */
    YearMonth month(long now) {
        YearMonth current = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone));
        if (!current.equals(month)) {
            month = current;
            counted.clear();
            kills.clear();
            damage.clear();
        }
        return month;
    }

    /** When this month began, as epoch millis: battles that ended before it are last month's. */
    long monthStart(long now) {
        return month(now).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Whether a battle has been counted already: newest first, a sweep stops at the first one. */
    boolean has(BattleZone battle) {
        return battle.id() != null && counted.contains(battle.id());
    }

    /**
     * Counts one battle, if it is finished, of this month, of conquest, and not counted yet.
     *
     * @return whether it was counted
     */
    boolean add(BattleZone battle, long now) {
        if (battle.active() || battle.timeEnded() <= 0 || battle.id() == null) {
            return false;
        }
        if (battle.timeEnded() < monthStart(now) || counted.contains(battle.id())) {
            return false;
        }
        String name = battle.territory();
        if (!countRivina && name != null && name.startsWith(RIVINA_WORLD)) {
            counted.add(battle.id());
            return false;
        }
        counted.add(battle.id());
        for (BattleParticipant player : battle.participants()) {
            if (player.kills() > 0) {
                kills.merge(player.uuid(), player.kills(), Integer::sum);
            }
            if (player.damageDealt() > 0) {
                damage.merge(player.uuid(), player.damageDealt(), Double::sum);
            }
        }
        return true;
    }

    Leader topKills() {
        Leader best = null;
        for (Map.Entry<UUID, Integer> entry : kills.entrySet()) {
            if (best == null || entry.getValue() > best.value()) {
                best = new Leader(entry.getKey(), entry.getValue());
            }
        }
        return best;
    }

    Leader topDamage() {
        Leader best = null;
        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            if (best == null || entry.getValue() > best.value()) {
                best = new Leader(entry.getKey(), entry.getValue());
            }
        }
        return best;
    }

    int battles() {
        return counted.size();
    }

    /** The tally as kept on disk: plain fields, so Gson reads it without adapters. */
    private static final class Saved {
        String month;
        boolean complete;
        java.util.List<String> counted;
        Map<String, Integer> kills;
        Map<String, Double> damage;
    }

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /**
     * Keeps the tally on disk, so a restart carries on from it instead of reading the whole month's
     * battles from Loka again.
     *
     * @param complete whether the month has been read back to its first day
     */
    void save(java.nio.file.Path file, boolean complete) {
        if (file == null || month == null) {
            return;
        }
        Saved saved = new Saved();
        saved.month = month.toString();
        saved.complete = complete;
        saved.counted = new java.util.ArrayList<>(counted);
        saved.kills = new HashMap<>();
        kills.forEach((uuid, value) -> saved.kills.put(uuid.toString(), value));
        saved.damage = new HashMap<>();
        damage.forEach((uuid, value) -> saved.damage.put(uuid.toString(), value));
        java.nio.file.Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            java.nio.file.Files.writeString(temp, GSON.toJson(saved));
            java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            // Only a restart's convenience: the tally in memory is the one that counts.
        }
    }

    /**
     * Takes up the tally kept on disk, if it is of this month.
     *
     * @return whether that month had been read back to its first day; {@code false} with nothing to take up
     */
    boolean load(java.nio.file.Path file, long now) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return false;
        }
        try {
            Saved saved = GSON.fromJson(java.nio.file.Files.readString(file), Saved.class);
            YearMonth current = month(now);
            if (saved == null || saved.month == null || !current.toString().equals(saved.month)) {
                return false;
            }
            if (saved.counted != null) {
                counted.addAll(saved.counted);
            }
            if (saved.kills != null) {
                saved.kills.forEach((uuid, value) -> kills.put(UUID.fromString(uuid), value));
            }
            if (saved.damage != null) {
                saved.damage.forEach((uuid, value) -> damage.put(UUID.fromString(uuid), value));
            }
            return saved.complete;
        } catch (java.io.IOException | RuntimeException e) {
            counted.clear();
            kills.clear();
            damage.clear();
            return false;
        }
    }
}
