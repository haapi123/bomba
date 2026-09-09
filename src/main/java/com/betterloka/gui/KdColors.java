package com.betterloka.gui;

import java.util.Locale;

/**
 * The colour a K/D is shown in, as a band rather than a shade per number.
 *
 * <p>One table, in one place, and it is the only thing that decides a K/D's colour anywhere in the
 * mod. The ramp runs red through orange and yellow to green across 0 to 5, which is the range almost
 * every player lives in; past that the colour leaves the ramp entirely, because a 12 and a 4 are not
 * the same kind of number and a greener green does not say so.
 *
 * <p>Bands are matched on the K/D <em>as displayed</em> — rounded to two decimals, the same way
 * {@code %.2f} renders it — so a number on screen and the band it is in never disagree.
 *
 * <p>Every colour is picked to carry on Minecraft's dark background, which rules out the deep blues
 * and purples that look right on paper and vanish in game. The bottom band is a lighter red than the
 * obvious one for the same reason: at {@code #B32020} it measures darker than vanilla's own
 * {@code DARK_RED} and is the one number a player most wants to read at a glance.
 */
public final class KdColors {
    /**
     * One band: everything up to and including {@code upTo}, in {@code hex}.
     *
     * @param upTo the highest K/D this band covers, as displayed to two decimals
     * @param hex  {@code 0xRRGGBB}, no alpha — that is what {@code Text.withColor} takes
     */
    public record Band(double upTo, int hex) {
    }

    /**
     * The table. Ordered, and read from the top down.
     *
     * <p>Half-point steps to 5.0, where the eye can still tell one from the next, then wider bands
     * where the numbers are rarer and the exact value matters less than the tier.
     */
    private static final Band[] BANDS = {
            new Band(0.49, 0xC93030),   // deep red      — losing badly
            new Band(0.99, 0xDD5A2B),   // red-orange    — below even
            new Band(1.49, 0xE2702C),   // orange        — even to decent
            new Band(1.99, 0xEE9A2E),   // amber
            new Band(2.49, 0xF0BE3A),   // yellow
            new Band(2.99, 0xD8CE42),   // yellow-green
            new Band(3.49, 0xB4D24A),   // light green
            new Band(3.99, 0x86C951),   // green
            new Band(4.49, 0x5CBF5A),   // deeper green
            new Band(5.00, 0x3FB562),   // top of the ramp
            new Band(7.50, 0x4FB0FF),   // light blue    — past 5, off the ramp
            new Band(10.00, 0x2E8BFF),  // blue
            new Band(15.00, 0xB478FF),  // light purple  — past 10
            new Band(20.00, 0x9B5CFF),  // purple
    };

    /** Past every band: the number that stops being a ratio and starts being a reputation. */
    private static final int ABOVE_ALL = 0xFFD24A;

    private KdColors() {
    }

    /**
     * The colour for a K/D.
     *
     * @return {@code 0xRRGGBB}, with no alpha channel
     */
    public static int getKdColor(double kd) {
        if (Double.isNaN(kd)) {
            return BANDS[0].hex();
        }
        // Matched on the displayed value, so the number and its colour can never disagree.
        double shown = Math.round(kd * 100.0) / 100.0;
        for (Band band : BANDS) {
            if (shown <= band.upTo()) {
                return band.hex();
            }
        }
        return ABOVE_ALL;
    }

    /** The same colour, opaque, for drawing into a screen where ARGB is wanted. */
    public static int getKdColorArgb(double kd) {
        return 0xFF000000 | getKdColor(kd);
    }

    /** {@code #B32020}, for a config screen or a log line. */
    public static String hexOf(double kd) {
        return String.format(Locale.ROOT, "#%06X", getKdColor(kd));
    }

    /** The table itself, for anything that wants to show the scale. */
    public static Band[] bands() {
        return BANDS.clone();
    }
}
