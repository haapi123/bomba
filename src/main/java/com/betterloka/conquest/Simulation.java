package com.betterloka.conquest;

import com.betterloka.api.model.LokaTown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Several fights played out one after another, each counted from the Strength the one before it
 * left: five wins in a row are not five times the first, because every win makes the next one worth
 * less (and every loss the next win worth more).
 *
 * <p>A fight whose stakes Loka would not allow — either side's win worth 3 or less — is not counted:
 * since November 2026 it cannot be placed, and the few on record changed nobody's Strength.
 */
public final class Simulation {
    /** The most fights one simulation plays out. */
    public static final int MAX_FIGHTS = 30;

    /** One fight to play: against whom, and whether the side being simulated wins it. */
    public record Fight(String opponentId, boolean win) {
    }

    /**
     * One fight as it went.
     *
     * @param allowed    whether Loka would let it be placed; if not, nothing moved
     * @param change     what the winner gained and the loser lost
     * @param rankBefore the simulated side's place before it, 1 for the strongest
     * @param rankAfter  and after it
     */
    public record Step(int number, Standings.Side opponent, boolean win, boolean allowed, int k, int change,
                       double youBefore, double youAfter, double themBefore, double themAfter,
                       int rankBefore, int rankAfter) {
    }

    /**
     * Every fight, and where they leave everybody.
     *
     * @param ranking the ranking after the last fight; each row says where the side stood at the start
     */
    public record Result(List<Step> steps, List<Standings.Row> ranking, double start, double end,
                         int startRank, int endRank) {
        public int net() {
            return (int) Math.round(end - start);
        }

        public int counted() {
            int counted = 0;
            for (Step step : steps) {
                if (step.allowed()) {
                    counted++;
                }
            }
            return counted;
        }
    }

    private Simulation() {
    }

    /**
     * Plays the fights out for {@code you} on {@code world}, each at the Strength the one before left.
     * A fight against a side that is not in the standings, or against itself, is skipped.
     *
     * @param youTown the town whose level counts for {@code you}, or {@code null} for the side's own
     */
    public static Result run(Standings standings, String world, Standings.Side you, LokaTown youTown,
                             List<Fight> fights) {
        Map<String, Standings.Side> sides = new HashMap<>();
        for (Standings.Row row : standings.ranking(world, you)) {
            sides.put(row.side().id(), row.side());
        }
        Map<String, Double> strength = new HashMap<>();
        double start = you.strengthOn(world);
        strength.put(you.id(), start);
        int startRank = place(standings.rankingWith(world, strength, you), you);

        List<Step> steps = new ArrayList<>();
        int rank = startRank;
        for (Fight fight : fights.subList(0, Math.min(MAX_FIGHTS, fights.size()))) {
            Standings.Side them = sides.get(fight.opponentId());
            if (them == null) {
                // Balak leaves out whoever has not fought there; a side named in a fight is ranked anyway.
                for (Standings.Row row : standings.ranking(world, you, standings.side(fight.opponentId(), fight.opponentId()))) {
                    if (row.side().id().equals(fight.opponentId())) {
                        them = row.side();
                        sides.put(them.id(), them);
                    }
                }
            }
            if (them == null || them.id().equals(you.id())) {
                continue;
            }
            double yours = strength.get(you.id());
            double theirs = strength.getOrDefault(them.id(), them.strengthOn(world));
            int k = standings.kFactor(world, you, youTown, them, null);
            StrengthMath.Stakes stakes = StrengthMath.stakes(yours, theirs, k);
            boolean allowed = !stakes.tooLittle();
            int change = allowed ? (fight.win() ? stakes.ifFirstWins() : stakes.ifSecondWins()) : 0;
            double youAfter = fight.win() ? yours + change : yours - change;
            double themAfter = fight.win() ? theirs - change : theirs + change;
            strength.put(you.id(), youAfter);
            strength.put(them.id(), themAfter);
            int rankAfter = place(standings.rankingWith(world, strength, you, them), you);
            steps.add(new Step(steps.size() + 1, them, fight.win(), allowed, k, change, yours, youAfter, theirs,
                    themAfter, rank, rankAfter));
            rank = rankAfter;
        }

        List<Standings.Side> named = new ArrayList<>();
        named.add(you);
        for (Step step : steps) {
            named.add(step.opponent());
        }
        List<Standings.Row> ranking = standings.rankingWith(world, strength, named.toArray(new Standings.Side[0]));
        return new Result(List.copyOf(steps), ranking, start, strength.get(you.id()), startRank, place(ranking, you));
    }

    private static int place(List<Standings.Row> ranking, Standings.Side side) {
        for (Standings.Row row : ranking) {
            if (row.side().id().equals(side.id())) {
                return row.rank();
            }
        }
        return 0;
    }
}
