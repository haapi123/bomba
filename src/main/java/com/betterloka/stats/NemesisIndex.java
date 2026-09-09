package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.EldritchApi;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who kills whom, over the conquest month.
 *
 * <p>A player's nemesis is whoever has killed them most this month. The only place that can be
 * worked out is a fight page's {@code deathInfo}, which names the killer of each death — Loka's own
 * battle records count kills and deaths per player and never say who they were against.
 *
 * <p>One fight page names the killer of every death in it, so parsing the fights one player was in
 * yields the full kill table for everyone who was there. That is what makes the second question —
 * whose nemesis you are, rather than who yours is — answerable at all.
 *
 * <p>What it cannot be is complete. EldritchBot lists a player's last ten fights and publishes no
 * index of all of them, so a month with thirty fights in it is read ten fights deep. Two things
 * soften that: every fight ever parsed is kept, so looking people up deepens the month rather than
 * re-reading it, and every count shown says how many fights it is over.
 */
public final class NemesisIndex {
    /** How many fights to read for one lookup. EldritchBot lists about ten and stops. */
    private static final int MAX_FIGHTS_PER_LOOKUP = 10;

    /** A kill table is only ever grown, never re-read, so a fight is stored once and kept. */
    private static final long FOREVER = Long.MAX_VALUE;

    /** Months to keep. The current one is what is asked for; the one before it is for the rollover. */
    private static final int MONTHS_KEPT = 3;

    /** One fight's kills, as stored. */
    private record StoredFight(String fightId, String month, List<String> killers,
                               List<String> victims) {
    }

    private record StoredIndex(List<StoredFight> fights) {
    }

    private static final Type STORE_TYPE = JsonStore.envelopeOf(StoredIndex.class);

    /** A player and how many times they killed, or were killed by, the player asked about. */
    public record Tally(String name, int kills) {
    }

    /** A player whose nemesis you could become, and by how many kills. */
    public record Chase(String name, int needed, int yours, int leader) {
    }

    /** What one lookup found, and how much of the month it is over. */
    public record Result(List<Tally> nemesisOf, List<Chase> couldBecome, int fightsRead,
                         boolean loading) {
        public static final Result EMPTY = new Result(List.of(), List.of(), 0, false);
    }

    /** How close a chase has to be to be worth showing. */
    public static final int MAX_KILLS_BEHIND = 2;

    private final EldritchApi eldritch;
    private final JsonStore<StoredIndex> disk;

    /** Fight id to the kills in it, for every fight ever read. */
    private final Map<String, List<EldritchApi.Kill>> fights = new ConcurrentHashMap<>();
    /** Fight id to the conquest month it belongs to. */
    private final Map<String, String> months = new ConcurrentHashMap<>();

    public NemesisIndex(EldritchApi eldritch, Path file) {
        this.eldritch = eldritch;
        this.disk = new JsonStore<>(file, STORE_TYPE, FOREVER);
        restore();
    }

    /**
     * The conquest month a date falls in: the first of the month to the last day of it.
     *
     * <p>Keyed the way the battle index keys its months, so the two read the same on screen.
     */
    public static String monthKey(LocalDate date) {
        return String.format(Locale.ROOT, "%04d-%02d", date.getYear(), date.getMonthValue());
    }

    /** The month a fight belongs to, from the {@code 9/7} EldritchBot dates the summaries carry. */
    public static String monthOf(FightSummary fight, LocalDate today) {
        LocalDate date = PlayerTrait.parseDate(fight.date(), today);
        return date == null ? null : monthKey(date);
    }

    /**
     * Reads the fights this player was in that fall in {@code month}, and works the tables out.
     *
     * <p>Blocking, and meant for a background worker.
     */
    public Result lookup(String playerName, List<FightSummary> recentFights, LocalDate today) {
        String month = monthKey(today);
        int read = 0;
        for (FightSummary fight : recentFights) {
            if (read >= MAX_FIGHTS_PER_LOOKUP) {
                break;
            }
            String fightMonth = monthOf(fight, today);
            if (!month.equals(fightMonth)) {
                continue;
            }
            String id = fight.ref() == null ? null : fight.ref().id();
            if (id == null) {
                continue;
            }
            read++;
            if (fights.containsKey(id)) {
                continue;
            }
            try {
                List<EldritchApi.Kill> kills = eldritch.fetchKills(id);
                fights.put(id, kills);
                months.put(id, fightMonth);
            } catch (ApiException e) {
                BetterLoka.LOGGER.debug("Could not read kills for fight {}", id, e);
            }
        }
        save();
        return of(playerName, month);
    }

    /** The tables for one player, from whatever has been read. Never blocks. */
    public Result of(String playerName, String month) {
        if (playerName == null || playerName.isBlank()) {
            return Result.EMPTY;
        }
        // victim -> killer -> how many times.
        Map<String, Map<String, Integer>> killsOn = new HashMap<>();
        int fightsInMonth = 0;
        for (Map.Entry<String, List<EldritchApi.Kill>> entry : fights.entrySet()) {
            if (!month.equals(months.get(entry.getKey()))) {
                continue;
            }
            fightsInMonth++;
            for (EldritchApi.Kill kill : entry.getValue()) {
                killsOn.computeIfAbsent(kill.victim(), key -> new HashMap<>())
                        .merge(kill.killer(), 1, Integer::sum);
            }
        }

        List<Tally> nemesisOf = new ArrayList<>();
        List<Chase> couldBecome = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : killsOn.entrySet()) {
            String victim = entry.getKey();
            if (victim.equalsIgnoreCase(playerName)) {
                continue;
            }
            Map<String, Integer> killers = entry.getValue();
            int mine = countFor(killers, playerName);
            int leader = killers.values().stream().mapToInt(Integer::intValue).max().orElse(0);

            // Equalling the leader takes the title: "killed them as many times or more".
            if (mine > 0 && mine >= leader) {
                nemesisOf.add(new Tally(victim, mine));
            } else {
                int needed = leader - mine;
                if (needed >= 1 && needed <= MAX_KILLS_BEHIND) {
                    couldBecome.add(new Chase(victim, needed, mine, leader));
                }
            }
        }

        nemesisOf.sort(Comparator.comparingInt(Tally::kills).reversed()
                .thenComparing(Tally::name, String.CASE_INSENSITIVE_ORDER));
        couldBecome.sort(Comparator.comparingInt(Chase::needed)
                .thenComparing(Chase::name, String.CASE_INSENSITIVE_ORDER));
        return new Result(List.copyOf(nemesisOf), List.copyOf(couldBecome), fightsInMonth, false);
    }

    private static int countFor(Map<String, Integer> killers, String playerName) {
        for (Map.Entry<String, Integer> entry : killers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(playerName)) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /** How many fights are held for a month, for a screen that wants to say how deep it read. */
    public int fightsKnown(String month) {
        int count = 0;
        for (String held : months.values()) {
            if (month.equals(held)) {
                count++;
            }
        }
        return count;
    }

    private void restore() {
        StoredIndex stored;
        try {
            stored = disk.read();
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the nemesis index", e);
            return;
        }
        if (stored == null || stored.fights() == null) {
            return;
        }
        for (StoredFight fight : stored.fights()) {
            if (fight.killers() == null || fight.victims() == null
                    || fight.killers().size() != fight.victims().size()) {
                continue;
            }
            List<EldritchApi.Kill> kills = new ArrayList<>(fight.killers().size());
            for (int i = 0; i < fight.killers().size(); i++) {
                kills.add(new EldritchApi.Kill(fight.killers().get(i), fight.victims().get(i)));
            }
            fights.put(fight.fightId(), List.copyOf(kills));
            months.put(fight.fightId(), fight.month());
        }
    }

    /**
     * Writes the kill tables back out, dropping months nobody will ask about again.
     *
     * <p>The rollover: on the first of the month the current key changes, last month's fights stop
     * being counted, and everything older than {@link #MONTHS_KEPT} is forgotten. Nothing has to
     * happen at midnight for that to be right — the month is read off the clock each time.
     */
    private void save() {
        Set<String> keep = new java.util.HashSet<>();
        YearMonth month = YearMonth.now();
        for (int back = 0; back < MONTHS_KEPT; back++) {
            keep.add(String.format(Locale.ROOT, "%04d-%02d",
                    month.minusMonths(back).getYear(), month.minusMonths(back).getMonthValue()));
        }

        List<StoredFight> out = new ArrayList<>();
        Map<String, List<EldritchApi.Kill>> surviving = new LinkedHashMap<>();
        for (Map.Entry<String, List<EldritchApi.Kill>> entry : fights.entrySet()) {
            String fightMonth = months.get(entry.getKey());
            if (fightMonth == null || !keep.contains(fightMonth)) {
                continue;
            }
            surviving.put(entry.getKey(), entry.getValue());
            List<String> killers = new ArrayList<>(entry.getValue().size());
            List<String> victims = new ArrayList<>(entry.getValue().size());
            for (EldritchApi.Kill kill : entry.getValue()) {
                killers.add(kill.killer());
                victims.add(kill.victim());
            }
            out.add(new StoredFight(entry.getKey(), fightMonth, killers, victims));
        }
        fights.keySet().retainAll(surviving.keySet());
        months.keySet().retainAll(surviving.keySet());

        try {
            disk.write(new StoredIndex(out));
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not write the nemesis index", e);
        }
    }
}
