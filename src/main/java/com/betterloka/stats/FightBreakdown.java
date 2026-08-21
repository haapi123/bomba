package com.betterloka.stats;

import com.betterloka.api.model.FightDetail;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A player's numbers over some subset of their recent fights — a format, a month, whatever the
 * caller selects.
 *
 * <p>Career totals cannot be sliced: EldritchBot publishes them as one lump and lists only the last
 * handful of fights per player, and which map a fight was on is on the fight's own page. So every
 * split here is over those recent fights and says so; a breakdown carries its own fight count so the
 * screen can never present it as a career.
 */
public record FightBreakdown(int fights, int wins, int kills, int deaths, int assists,
                             int golems, int lamps, int potions, int pearls) {

    public static final FightBreakdown EMPTY = new FightBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Whether a fight was a RIVI fight rather than ordinary Conquest.
     *
     * <p>EldritchBot names RIVI maps {@code the_something} — the_rivi_shores, the_jade_highlands,
     * the_verdant_hollows — and every one sampled ran about twenty players, against fifty to two
     * hundred and seventy on the Conquest territories, whose names are the plain biome names Loka's
     * own territory list uses. The name is the signal; the size is what confirms it means what it
     * looks like.
     */
    public static boolean isRivi(FightDetail detail) {
        return detail != null && detail.location() != null
                && detail.location().toLowerCase(Locale.ROOT).startsWith("the_");
    }

    /** Folds every fight whose detail has landed into one set of totals. */
    public static FightBreakdown of(List<FightSummary> fights) {
        int count = 0;
        int wins = 0;
        int kills = 0;
        int deaths = 0;
        int assists = 0;
        int golems = 0;
        int lamps = 0;
        int potions = 0;
        int pearls = 0;
        for (FightSummary fight : fights) {
            FightDetail detail = fight.detail();
            if (detail == null) {
                continue;
            }
            count++;
            if (detail.playerWon()) {
                wins++;
            }
            kills += detail.kills();
            deaths += detail.deaths();
            assists += detail.assists();
            golems += detail.golemKills();
            lamps += detail.lamps();
            potions += detail.potions();
            pearls += detail.pearls();
        }
        return new FightBreakdown(count, wins, kills, deaths, assists, golems, lamps, potions, pearls);
    }

    /** The RIVI fights, or the Conquest ones. */
    public static List<FightSummary> select(List<FightSummary> fights, boolean rivi) {
        List<FightSummary> selected = new ArrayList<>();
        for (FightSummary fight : fights) {
            if (fight.detail() != null && isRivi(fight.detail()) == rivi) {
                selected.add(fight);
            }
        }
        return selected;
    }

    /** The fights dated in the same calendar month as {@code today}. */
    public static List<FightSummary> thisMonth(List<FightSummary> fights, LocalDate today) {
        List<FightSummary> selected = new ArrayList<>();
        for (FightSummary fight : fights) {
            LocalDate date = PlayerTrait.parseDate(fight.date(), today);
            if (date != null && date.getYear() == today.getYear()
                    && date.getMonth() == today.getMonth()) {
                selected.add(fight);
            }
        }
        return selected;
    }

    public boolean isEmpty() {
        return fights == 0;
    }

    public int losses() {
        return fights - wins;
    }

    public double killDeathRatio() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }

    public String killDeathText() {
        return String.format(Locale.ROOT, "%.2f", killDeathRatio());
    }

    public double winRate() {
        return fights == 0 ? 0 : (double) wins / fights;
    }

    public String winRateText() {
        return String.format(Locale.ROOT, "%.0f%%", winRate() * 100);
    }
}
