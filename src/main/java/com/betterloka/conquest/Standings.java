package com.betterloka.conquest;

import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ScheduledFight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who stands where in conquest: every alliance, and every town in none, with its Strength.
 *
 * <p>Strength belongs to the alliance once a town is in one — the town's own figure then sits at its
 * starting 100 — so a "side" here is the alliance if there is one and the town otherwise, which is
 * also how Loka's battle records count it. An alliance's continent is its leading town's.
 *
 * <p>A snapshot: built from one reading of the alliances and the towns, and never changed.
 */
public final class Standings {
    /** What every side starts a month's Balak campaign with, and what an unknown figure is read as. */
    public static final double BASELINE = 100;

    public static final Standings EMPTY = new Standings(List.of(), Map.of(), Map.of(), Map.of());

    /**
     * An alliance, or a town in none.
     *
     * @param world   the continent it is ranked on: Loka's world key, {@code west} for Ascalon
     * @param level   the town level its fights are counted at: the town's own, or its leading town's
     * @param townIds the towns in it; just the one for a town on its own
     */
    public record Side(String id, String name, boolean alliance, String world, double strength,
                       double balakStrength, double level, List<String> townIds) {
        /** Its Strength where a fight on this world is counted: Balak keeps a tally of its own. */
        public double strengthOn(String fightWorld) {
            return StrengthMath.isBalak(fightWorld) ? balakStrength : strength;
        }

        public boolean has(String townId) {
            return townId != null && townIds.contains(townId);
        }
    }

    /**
     * One line of a ranking.
     *
     * @param rank     1 for the strongest
     * @param before   its rank before the fight this ranking supposes, or the same rank if none
     * @param change   what the supposed fight did to its Strength; 0 for everybody else
     */
    public record Row(int rank, Side side, double strength, int before, int change) {
    }

    /**
     * What a declared fight is worth.
     *
     * @param attackerWin what the attackers gain, and the defenders lose, if the attackers win
     * @param defenderWin what the defenders gain, and the attackers lose, if the defenders win
     */
    public record Forecast(String world, Side attacker, Side defender, double attackerStrength,
                           double defenderStrength, int k, int attackerWin, int defenderWin) {
        public boolean tooLittle() {
            return attackerWin <= StrengthMath.TOO_LITTLE || defenderWin <= StrengthMath.TOO_LITTLE;
        }
    }

    /** A side found by name, and the town that was named if it was a town. */
    public record Match(Side side, LokaTown town) {
    }

    private final List<Side> sides;
    private final Map<String, Side> byAllianceId;
    private final Map<String, Side> byTownId;
    private final Map<String, LokaTown> towns;

    private Standings(List<Side> sides, Map<String, Side> byAllianceId, Map<String, Side> byTownId,
                      Map<String, LokaTown> towns) {
        this.sides = sides;
        this.byAllianceId = byAllianceId;
        this.byTownId = byTownId;
        this.towns = towns;
    }

    public static Standings of(Collection<LokaAlliance> alliances, Collection<LokaTown> allTowns) {
        Map<String, LokaTown> towns = new LinkedHashMap<>();
        for (LokaTown town : allTowns) {
            if (town.id() != null && !town.deleted()) {
                towns.put(town.id(), town);
            }
        }
        List<Side> sides = new ArrayList<>();
        Map<String, Side> byAllianceId = new HashMap<>();
        Map<String, Side> byTownId = new HashMap<>();
        for (LokaAlliance alliance : alliances) {
            if (alliance.id() == null || alliance.townIds().isEmpty()) {
                continue;
            }
            LokaTown leader = towns.get(alliance.leaderTownId());
            Side side = new Side(alliance.id(), alliance.name() == null ? "?" : alliance.name(), true,
                    homeWorld(alliance, leader, towns), alliance.strength(), known(alliance.balakStrength()),
                    leader != null ? leader.townLevel() : highestLevel(alliance, towns),
                    List.copyOf(alliance.townIds()));
            sides.add(side);
            byAllianceId.put(alliance.id(), side);
            for (String townId : alliance.townIds()) {
                byTownId.putIfAbsent(townId, side);
            }
        }
        for (LokaTown town : towns.values()) {
            if (byTownId.containsKey(town.id())) {
                continue;
            }
            Side side = new Side(town.id(), town.name() == null ? "?" : town.name(), false, town.world(),
                    town.strength(), known(town.balakStrength()), town.townLevel(), List.of(town.id()));
            sides.add(side);
            byTownId.put(town.id(), side);
        }
        return new Standings(List.copyOf(sides), Map.copyOf(byAllianceId), Map.copyOf(byTownId), Map.copyOf(towns));
    }

    private static double known(double balakStrength) {
        return balakStrength > 0 ? balakStrength : BASELINE;
    }

    /** The leading town's continent; failing that, the one most of its towns are on. */
    private static String homeWorld(LokaAlliance alliance, LokaTown leader, Map<String, LokaTown> towns) {
        if (leader != null && leader.world() != null) {
            return leader.world();
        }
        Map<String, Integer> count = new HashMap<>();
        for (String townId : alliance.townIds()) {
            LokaTown town = towns.get(townId);
            if (town != null && town.world() != null) {
                count.merge(town.world(), 1, Integer::sum);
            }
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static double highestLevel(LokaAlliance alliance, Map<String, LokaTown> towns) {
        double level = 0;
        for (String townId : alliance.townIds()) {
            LokaTown town = towns.get(townId);
            if (town != null) {
                level = Math.max(level, town.townLevel());
            }
        }
        return level > 0 ? level : StrengthMath.MAX_TOWN_LEVEL;
    }

    public boolean isEmpty() {
        return sides.isEmpty();
    }

    public LokaTown town(String townId) {
        return townId == null ? null : towns.get(townId);
    }

    /** The alliance a town is in, or the town itself; {@code null} for a town nobody has heard of. */
    public Side sideOfTown(String townId) {
        return townId == null ? null : byTownId.get(townId);
    }

    /** One side of a battle record, which names the alliance, the town, or both. */
    public Side side(String allianceId, String townId) {
        Side alliance = allianceId == null ? null : byAllianceId.get(allianceId);
        return alliance != null ? alliance : sideOfTown(townId);
    }

    /**
     * Finds a side by what somebody typed: a town or an alliance, whole or the start of it, in any
     * case. A name that fits several is not guessed at unless one of them is that name exactly.
     */
    public Match find(String typed) {
        String wanted = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) {
            return null;
        }
        Match starts = null;
        int startsCount = 0;
        Match contains = null;
        int containsCount = 0;
        List<Match> candidates = new ArrayList<>();
        for (LokaTown town : towns.values()) {
            candidates.add(new Match(byTownId.get(town.id()), town));
        }
        for (Side side : sides) {
            if (side.alliance()) {
                candidates.add(new Match(side, null));
            }
        }
        for (Match candidate : candidates) {
            String name = candidate.town() != null ? candidate.town().name() : candidate.side().name();
            if (name == null || candidate.side() == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals(wanted)) {
                return candidate;
            }
            if (lower.startsWith(wanted)) {
                starts = candidate;
                startsCount++;
            } else if (lower.contains(wanted)) {
                contains = candidate;
                containsCount++;
            }
        }
        if (startsCount == 1) {
            return starts;
        }
        return startsCount == 0 && containsCount == 1 ? contains : null;
    }

    /** Names that begin with, or failing that contain, what was typed: something to offer when it fits several. */
    public List<String> suggestions(String typed, int limit) {
        String wanted = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        List<String> starting = new ArrayList<>();
        List<String> containing = new ArrayList<>();
        if (wanted.isEmpty()) {
            return starting;
        }
        List<String> names = new ArrayList<>();
        for (LokaTown town : towns.values()) {
            names.add(town.name());
        }
        for (Side side : sides) {
            if (side.alliance()) {
                names.add(side.name());
            }
        }
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(wanted)) {
                starting.add(name);
            } else if (lower.contains(wanted)) {
                containing.add(name);
            }
        }
        starting.sort(String.CASE_INSENSITIVE_ORDER);
        containing.sort(String.CASE_INSENSITIVE_ORDER);
        starting.addAll(containing);
        return starting.size() > limit ? List.copyOf(starting.subList(0, limit)) : List.copyOf(starting);
    }

    /**
     * The ranking on one world, strongest first.
     *
     * <p>A continent ranks the sides that live on it. Balak ranks whoever has fought there this
     * month — everybody else still sits on the starting 100 and would only be a wall of ties.
     *
     * @param always sides to rank even if Balak would leave them out, such as the two in a fight
     */
    public List<Row> ranking(String world, Side... always) {
        return rank(world, null, null, 0, always);
    }

    /** The ranking as it would stand once {@code winner} has taken {@code change} off {@code loser}. */
    public List<Row> rankingAfter(String world, Side winner, Side loser, int change) {
        return rank(world, winner, loser, change, winner, loser);
    }

    private List<Row> rank(String world, Side winner, Side loser, int change, Side... always) {
        boolean balak = StrengthMath.isBalak(world);
        List<Side> ranked = new ArrayList<>();
        for (Side side : sides) {
            boolean named = false;
            for (Side each : always) {
                named |= each != null && each.id().equals(side.id());
            }
            boolean belongs = balak ? side.balakStrength() != BASELINE : world != null && world.equals(side.world());
            if (belongs || named) {
                ranked.add(side);
            }
        }
        Comparator<Side> byName = Comparator.comparing(side -> side.name().toLowerCase(Locale.ROOT));
        ranked.sort(Comparator.comparingDouble((Side side) -> -side.strengthOn(world)).thenComparing(byName));
        Map<String, Integer> before = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            before.put(ranked.get(i).id(), i + 1);
        }

        Map<String, Double> after = new HashMap<>();
        for (Side side : ranked) {
            double strength = side.strengthOn(world);
            if (winner != null && side.id().equals(winner.id())) {
                strength += change;
            } else if (loser != null && side.id().equals(loser.id())) {
                strength -= change;
            }
            after.put(side.id(), strength);
        }
        ranked.sort(Comparator.comparingDouble((Side side) -> -after.get(side.id())).thenComparing(byName));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Side side = ranked.get(i);
            int moved = winner != null && side.id().equals(winner.id()) ? change
                    : loser != null && side.id().equals(loser.id()) ? -change : 0;
            rows.add(new Row(i + 1, side, after.get(side.id()), before.get(side.id()), moved));
        }
        return rows;
    }

    /** A side's place on a world, 1 for the strongest, or 0 if it is not ranked there. */
    public int rankOf(String world, Side side) {
        if (side == null) {
            return 0;
        }
        for (Row row : ranking(world, side)) {
            if (row.side().id().equals(side.id())) {
                return row.rank();
            }
        }
        return 0;
    }

    /** The k-factor between two sides on a world, each counted at the level of the town given, or its own. */
    public int kFactor(String world, Side first, LokaTown firstTown, Side second, LokaTown secondTown) {
        double firstLevel = firstTown != null ? firstTown.townLevel() : first == null ? StrengthMath.MAX_TOWN_LEVEL : first.level();
        double secondLevel = secondTown != null ? secondTown.townLevel() : second == null ? StrengthMath.MAX_TOWN_LEVEL : second.level();
        return StrengthMath.kFactor(world, firstLevel, secondLevel);
    }

    /**
     * What a fight on the books is worth to each side, or {@code null} when it is worth nothing that
     * can be worked out: a fight over neutral ground, one on Rivina, or one whose sides are unknown.
     *
     * <p>The record's own Strength figures are used when Loka has filled them in — they are what the
     * fight will be counted with — and the standings' otherwise.
     */
    public Forecast forecast(ScheduledFight fight) {
        if (fight == null || !fight.defended() || !StrengthMath.countsForStrength(fight.world())) {
            return null;
        }
        Side attacker = side(fight.attackerAllianceId(), fight.attackerTownId());
        Side defender = side(fight.defenderAllianceId(), fight.defenderTownId());
        double attackerStrength = fight.attackerStrength() > 0 ? fight.attackerStrength()
                : attacker == null ? 0 : attacker.strengthOn(fight.world());
        double defenderStrength = fight.defenderStrength() > 0 ? fight.defenderStrength()
                : defender == null ? 0 : defender.strengthOn(fight.world());
        if (attackerStrength <= 0 || defenderStrength <= 0) {
            return null;
        }
        int k = kFactor(fight.world(), attacker, town(fight.attackerTownId()), defender, town(fight.defenderTownId()));
        return new Forecast(fight.world(), attacker, defender, attackerStrength, defenderStrength, k,
                StrengthMath.change(attackerStrength, defenderStrength, k),
                StrengthMath.change(defenderStrength, attackerStrength, k));
    }
}
