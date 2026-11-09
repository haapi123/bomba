package com.betterloka.gui;

/**
 * The colour a ping is shown in: green while it is fine to fight on, yellow and orange as it starts
 * to be felt, red where it decides fights. One table, like the K/D's and the Elo's.
 */
public final class PingColors {
    private PingColors() {
    }

    /** {@code 0xRRGGBB} for a ping in milliseconds. */
    public static int color(int millis) {
        if (millis < 0) {
            return 0x9A9AA8;
        }
        if (millis <= 60) {
            return 0x5FD37A;
        }
        if (millis <= 120) {
            return 0xB4D24A;
        }
        if (millis <= 200) {
            return 0xF0BE3A;
        }
        if (millis <= 300) {
            return 0xE2702C;
        }
        return 0xDD3B2B;
    }

    public static int argb(int millis) {
        return 0xFF000000 | color(millis);
    }
}
