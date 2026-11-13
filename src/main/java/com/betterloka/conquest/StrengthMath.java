package com.betterloka.conquest;

/**
 * How much Strength a conquest fight is worth.
 *
 * <p>Loka's wiki gives the shape of it — an Elo rating, with a k-factor that falls with the lower of
 * the two towns' levels — but its constants are out of date. The ones here were fitted to finished
 * battles from October and November 2026, whose records carry both sides' Strength as the fight
 * began: the thirty-four fights whose effect could be read cleanly, with nothing else moving either
 * side's Strength before its next fight, come out to the point, on the continents and on Balak.
 *
 * <pre>
 *   change  = floor(k / (1 + 10 ^ ((winner - loser) / 250))) - 1
 *   k       = 28 - (25 - lower town level)      on Kalros, Ascalon and Garama
 *   k       = 14                                on Balak
 * </pre>
 *
 * <p>The winner gains {@code change} and the loser loses exactly as much. Two equal sides at level 25
 * trade 13; beating a much stronger side is worth up to {@code k - 2}, beating a much weaker one
 * next to nothing. Reinforcements do not change it, whatever the wiki says.
 *
 * <p>Not covered: the wiki's penalties for a territory that decays (25) and for losing the last one
 * (50), and what a neutral territory on Balak is worth, which no published number explains.
 */
public final class StrengthMath {
    /** The k-factor between two level 25 towns on a continent. */
    public static final int MAX_K = 28;
    /** Balak's k-factor, which ignores town levels. */
    public static final int BALAK_K = 14;
    /** The highest town level. */
    public static final int MAX_TOWN_LEVEL = 25;
    /** How many points of difference make one side ten times as likely to win, in the formula's eyes. */
    public static final double SCALE = 250.0;
    /**
     * Since November 2026 Loka does not let a fight be placed if either side winning would be worth
     * this little or less; the few that were placed anyway changed nobody's Strength.
     */
    public static final int TOO_LITTLE = 3;

    private StrengthMath() {
    }

    /** Loka's world key for Balak. */
    public static boolean isBalak(String world) {
        return "bigboi".equalsIgnoreCase(world);
    }

    /** Whether fights on this world move Strength at all: Rivina's are for conquest points. */
    public static boolean countsForStrength(String world) {
        return world != null && !"lilboi".equalsIgnoreCase(world);
    }

    /** The k-factor for a fight between towns of these levels on this world. */
    public static int kFactor(String world, double townLevel, double otherTownLevel) {
        return isBalak(world) ? BALAK_K : kFactor(Math.min(townLevel, otherTownLevel));
    }

    /** The continents' k-factor, from the lower of the two towns' levels. */
    public static int kFactor(double lowerTownLevel) {
        int level = (int) Math.floor(Math.max(0, Math.min(MAX_TOWN_LEVEL, lowerTownLevel)));
        return MAX_K - (MAX_TOWN_LEVEL - level);
    }

    /** What the winner gains and the loser loses. Never negative. */
    public static int change(double winnerStrength, double loserStrength, int k) {
        double expected = 1.0 / (1.0 + Math.pow(10.0, (winnerStrength - loserStrength) / SCALE));
        return Math.max(0, (int) Math.floor(k * expected) - 1);
    }

    /**
     * Both ways a fight between two sides can go.
     *
     * @param ifFirstWins  what the first side gains, and the second loses, if the first wins
     * @param ifSecondWins what the second side gains, and the first loses, if the second wins
     */
    public record Stakes(double first, double second, int k, int ifFirstWins, int ifSecondWins) {
        /** Whether Loka's rule against fights worth next to nothing applies. */
        public boolean tooLittle() {
            return ifFirstWins <= TOO_LITTLE || ifSecondWins <= TOO_LITTLE;
        }
    }

    public static Stakes stakes(double first, double second, int k) {
        return new Stakes(first, second, k, change(first, second, k), change(second, first, k));
    }
}
