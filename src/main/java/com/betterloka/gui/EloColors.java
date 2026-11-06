package com.betterloka.gui;

import java.util.Locale;

/**
 * The colour a Conquest Elo is shown in, as a band — the same idea as {@link KdColors}, one table in
 * one place.
 *
 * <p>Loka Elo starts everyone at 750, and that is where nearly everybody stays: of about fourteen
 * thousand rated players, three in four sit between 650 and 810, one in twenty is above a thousand
 * and one in a hundred above 1800. So the starting rating is a neutral grey, losing ground is red,
 * and the colours climb through the ratings that actually mean something — yellow, green, blue,
 * purple — to gold for the few dozen at the very top.
 *
 * <p>Every colour carries on Minecraft's dark background, like the K/D ones.
 */
public final class EloColors {
    /** One band: everything up to and including {@code upTo}, in {@code hex} ({@code 0xRRGGBB}). */
    public record Band(long upTo, int hex) {
    }

    private static final Band[] BANDS = {
            new Band(649, 0xDD5A2B),    // red-orange   — below where everyone starts
            new Band(849, 0xB8B8C0),    // grey         — about where everyone starts
            new Band(1099, 0xF0BE3A),   // yellow       — above the crowd
            new Band(1399, 0xB4D24A),   // light green
            new Band(1799, 0x5CBF5A),   // green        — the top one in a hundred begins
            new Band(2199, 0x4FB0FF),   // light blue
            new Band(2599, 0x2E8BFF),   // blue
            new Band(2999, 0xB478FF),   // purple
    };

    /** Past every band: the handful at the top of the leaderboard. */
    private static final int ABOVE_ALL = 0xFFD24A;

    private EloColors() {
    }

    /** The colour for a rating, matched on the rating as shown — rounded to a whole number. */
    public static int color(double elo) {
        long shown = Math.round(elo);
        for (Band band : BANDS) {
            if (shown <= band.upTo()) {
                return band.hex();
            }
        }
        return ABOVE_ALL;
    }

    /** The same colour, opaque, for drawing into a screen. */
    public static int argb(double elo) {
        return 0xFF000000 | color(elo);
    }

    /** {@code #4FB0FF}, for a log line or a legend. */
    public static String hexOf(double elo) {
        return String.format(Locale.ROOT, "#%06X", color(elo));
    }

    public static Band[] bands() {
        return BANDS.clone();
    }
}
