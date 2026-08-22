package com.betterloka.grind;

/**
 * The timers the Loka Grinder module keeps.
 *
 * <p>Held here rather than on the screen so they keep running with the GUI shut — which is the only
 * way a seventeen-minute timer is any use, since nobody stares at a menu for seventeen minutes.
 */
public final class GrindTimers {
    /** A shulker takes seventeen minutes to come back. */
    public static final long SHULKER_MILLIS = 17 * 60 * 1000L;

    private final GrindTimer shulker = new GrindTimer(SHULKER_MILLIS);
    private final GrindTimer glowstone = new GrindTimer(60_000L);

    public GrindTimer shulker() {
        return shulker;
    }

    /**
     * The glowstone timer.
     *
     * <p>Its length is a setting rather than a constant: unlike the shulker's seventeen minutes,
     * nobody has told this mod what Loka's glowstone cycle actually is, so guessing a number and
     * presenting it as fact would be worse than letting it be set.
     */
    public GrindTimer glowstone() {
        return glowstone;
    }
}
