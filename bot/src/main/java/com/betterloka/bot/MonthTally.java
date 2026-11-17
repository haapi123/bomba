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
}
