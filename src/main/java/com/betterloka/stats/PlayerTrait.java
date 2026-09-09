package com.betterloka.stats;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.ArenaRank;
import com.betterloka.api.model.EldritchStats;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A one-line read on some part of a player's game, drawn as a coloured chip.
 *
 * <p>A trait is only produced when there is something to base it on. A player who never duels has no
 * duel chip rather than a bad one — an absent chip means "no record", which is different from a poor
 * one, and conflating the two would libel everyone who simply does not play that part of the game.
 */
public record PlayerTrait(Kind kind, Level level, Object[] detail) {

    /** Which part of the game the chip is about. */
    public enum Kind {
        KILL_DEATH("betterloka.trait.kd"),
        ACTIVITY("betterloka.trait.activity"),
        CHARGE("betterloka.trait.charge"),
        DUELS("betterloka.trait.duels");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /** The chip's own label, e.g. "K/D 3.30". */
        public String labelKey() {
            return key + ".label";
        }

        /** The sentence shown on hover, saying what earned this level. */
        public String reasonKey(Level level) {
            return key + "." + level.name().toLowerCase(Locale.ROOT);
        }
    }

    /** Green, yellow, red. */
    public enum Level {
        GOOD, MIXED, POOR
    }

    /**
     * How many recent fights EldritchBot lists on a player page. The list is capped, so a player
     * whose every listed fight is recent has at least this many and probably more — which is as
     * precise as "how much do they play" can be made from it.
     */
    private static final int RECENT_FIGHT_ROWS = 9;

    private static final int ACTIVITY_WINDOW_DAYS = 30;

    /**
     * How often a charge is finished: lamps taken against golems downed.
     *
     * <p>Golems are the denominator because they are what a charge is aimed at, and the lamp is what
     * it is for — fifty lamps off a hundred golems is half the chances converted. This replaces
     * charges per fight, which counted the two together and so could not tell somebody who takes
     * every lamp going from somebody who only ever hits golems.
     */
    private static final int CHARGE_GOOD_PERCENT = 71;
    private static final int CHARGE_POOR_PERCENT = 30;

    /** Below this many golems the rate is noise — one lamp off two golems is not fifty per cent. */
    private static final int CHARGE_MINIMUM_GOLEMS = 10;

    /** @return every trait there is evidence for, in a fixed order so the row does not jump about. */
    public static List<PlayerTrait> of(PlayerProfile profile, List<ArenaService.Standing> arena, LocalDate today) {
        List<PlayerTrait> traits = new ArrayList<>();
        addIfPresent(traits, killDeath(profile.stats()));
        addIfPresent(traits, activity(profile.stats(), today));
        addIfPresent(traits, charge(profile.stats()));
        addIfPresent(traits, duels(arena));
        return List.copyOf(traits);
    }

    private static void addIfPresent(List<PlayerTrait> traits, PlayerTrait trait) {
        if (trait != null) {
            traits.add(trait);
        }
    }

    private static PlayerTrait killDeath(EldritchStats stats) {
        if (stats.kills() == 0 && stats.deaths() == 0) {
            return null;
        }
        double ratio = stats.killDeathRatio();
        Level level = ratio > 3.0 ? Level.GOOD : (ratio >= 1.0 ? Level.MIXED : Level.POOR);
        return new PlayerTrait(Kind.KILL_DEATH, level,
                new Object[]{String.format(Locale.ROOT, "%.2f", ratio), stats.kills(), stats.deaths()});
    }

    /**
     * How much they have played lately.
     *
     * <p>EldritchBot lists a fixed number of recent fights, so a full list of recent ones cannot be
     * counted past its cap: the honest reading is "at least this many", and that is what the chip
     * claims.
     */
    private static PlayerTrait activity(EldritchStats stats, LocalDate today) {
        List<EldritchStats.RecentFight> recent = stats.recentFights();
        if (recent.isEmpty()) {
            // Never fought at all is a fact about their record, not about the last month.
            return stats.totalFights() > 0
                    ? new PlayerTrait(Kind.ACTIVITY, Level.POOR, new Object[]{0, ACTIVITY_WINDOW_DAYS})
                    : null;
        }
        int within = 0;
        for (EldritchStats.RecentFight fight : recent) {
            LocalDate date = parseDate(fight.date(), today);
            if (date != null && ChronoUnit.DAYS.between(date, today) <= ACTIVITY_WINDOW_DAYS) {
                within++;
            }
        }
        boolean saturated = within >= Math.min(RECENT_FIGHT_ROWS, recent.size());
        Level level = within == 0 ? Level.POOR : (saturated ? Level.GOOD : Level.MIXED);
        return new PlayerTrait(Kind.ACTIVITY, level, new Object[]{within, ACTIVITY_WINDOW_DAYS});
    }

    private static PlayerTrait charge(EldritchStats stats) {
        int golems = stats.golems();
        if (golems < CHARGE_MINIMUM_GOLEMS) {
            return null;
        }
        int lamps = stats.lamps();
        // Capped at a hundred: a lamp can be taken without a golem going down, and a rate over
        // 100% reads like a bug rather than like somebody who was very good at it.
        int percent = (int) Math.round(Math.min(1.0, (double) lamps / golems) * 100);
        Level level = percent >= CHARGE_GOOD_PERCENT
                ? Level.GOOD
                : (percent > CHARGE_POOR_PERCENT ? Level.MIXED : Level.POOR);
        return new PlayerTrait(Kind.CHARGE, level, new Object[]{percent, lamps, golems});
    }

    /**
     * Ranked 1v1, taken from whichever ladder they stand highest on — a Netherite potion player who
     * has barely touched barebones is a good dueller, not a mixed one.
     */
    private static PlayerTrait duels(List<ArenaService.Standing> arena) {
        if (arena == null) {
            return null;
        }
        ArenaEntry best = null;
        ArenaApi.Ladder bestLadder = null;
        for (ArenaService.Standing standing : arena) {
            ArenaEntry current = standing.current();
            if (current == null || current.rank() == null) {
                continue;
            }
            if (best == null || current.rank().compareTo(best.rank()) > 0) {
                best = current;
                bestLadder = standing.ladder();
            }
        }
        if (best == null) {
            return null;
        }
        ArenaRank rank = best.rank();
        Level level;
        if (rank.compareTo(new ArenaRank(ArenaRank.Tier.DIAMOND, 3, null)) > 0) {
            level = Level.GOOD;
        } else if (rank.compareTo(new ArenaRank(ArenaRank.Tier.EMERALD, 1, null)) >= 0) {
            level = Level.MIXED;
        } else {
            level = Level.POOR;
        }
        return new PlayerTrait(Kind.DUELS, level, new Object[]{rank.label(), bestLadder.shortName(), best.position()});
    }

    /**
     * EldritchBot dates a fight {@code M/D} with no year, so a date that would be in the future
     * belongs to last year.
     */
    static LocalDate parseDate(String date, LocalDate today) {
        if (date == null) {
            return null;
        }
        String[] parts = date.trim().split("/");
        if (parts.length != 2) {
            return null;
        }
        try {
            MonthDay monthDay = MonthDay.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            LocalDate candidate = monthDay.atYear(today.getYear());
            return candidate.isAfter(today) ? monthDay.atYear(today.getYear() - 1) : candidate;
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }
}
