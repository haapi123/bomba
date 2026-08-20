package com.betterloka.api.model;

import java.util.Locale;

/**
 * A ranked-arena rank, e.g. {@code Netherite III}.
 *
 * <p>Ranks are a tier plus a level within it, and the leaderboard is ordered by them, so they have
 * to be comparable to answer "the best rank they ever reached". The order below is the one the live
 * standings are in: Bedrock at the top, then Netherite III down to Netherite I, and so on.
 */
public record ArenaRank(Tier tier, int level, String label) implements Comparable<ArenaRank> {

    /** Ascending. Bedrock is the top rank and carries no level. */
    public enum Tier {
        WOOD, STONE, COPPER, IRON, GOLD, EMERALD, DIAMOND, NETHERITE, BEDROCK;

        static Tier of(String name) {
            for (Tier tier : values()) {
                if (tier.name().equalsIgnoreCase(name)) {
                    return tier;
                }
            }
            return null;
        }
    }

    /**
     * @param label the rank as the leaderboard writes it
     * @return the parsed rank, or {@code null} if it is blank or a tier this does not know
     */
    public static ArenaRank parse(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        int space = trimmed.indexOf(' ');
        Tier tier = Tier.of(space < 0 ? trimmed : trimmed.substring(0, space));
        if (tier == null) {
            return null;
        }
        // The site renders the level by how long the numeral is — "III" is the third badge — so the
        // level is its length rather than a parsed roman numeral.
        int level = space < 0 ? 0 : trimmed.substring(space + 1).trim().length();
        return new ArenaRank(tier, level, trimmed);
    }

    @Override
    public int compareTo(ArenaRank other) {
        int byTier = Integer.compare(tier.ordinal(), other.tier.ordinal());
        return byTier != 0 ? byTier : Integer.compare(level, other.level);
    }

    /** The colour Loka draws this tier in, as ARGB. */
    public int color() {
        return switch (tier) {
            case WOOD -> 0xFF99755A;
            case STONE -> 0xFF6B6B6B;
            case COPPER -> 0xFFEA714D;
            case IRON -> 0xFFE0DEDC;
            case GOLD -> 0xFFE3B92D;
            case EMERALD -> 0xFF2FD45F;
            case DIAMOND -> 0xFFBCDAEC;
            case NETHERITE -> 0xFF977547;
            case BEDROCK -> 0xFFD98CFF;
        };
    }

    @Override
    public String toString() {
        return label == null ? tier.name().toLowerCase(Locale.ROOT) : label;
    }
}
