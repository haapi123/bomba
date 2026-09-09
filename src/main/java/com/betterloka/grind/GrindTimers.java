package com.betterloka.grind;

/**
 * The timers the Loka Grinder module keeps.
 *
 * <p>Held here rather than on the screen so they keep running with the GUI shut — which is the only
 * way a twenty-minute timer is any use, since nobody stares at a menu for twenty minutes.
 */
public final class GrindTimers {
    /** A shulker takes twenty minutes to come back. */
    public static final long SHULKER_MILLIS = 20 * 60 * 1000L;

    /** Glowstone runs three hours. */
    public static final long GLOWSTONE_MILLIS = 3 * 60 * 60 * 1000L;

    private final GrindTimer shulker = new GrindTimer(SHULKER_MILLIS);
    private final GrindTimer glowstone = new GrindTimer(GLOWSTONE_MILLIS);

    public GrindTimer shulker() {
        return shulker;
    }

    /** The glowstone timer: three hours by default, adjustable from the screen. */
    public GrindTimer glowstone() {
        return glowstone;
    }
}
