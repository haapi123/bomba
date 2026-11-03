package com.betterloka.grind;

/**
 * The three kinds of Nether shrine, by the mob that guards them.
 *
 * <p>Which one somebody heads for decides what they fight, so the kind is what the waypoints, the
 * toggles and the icons are all sorted by.
 */
public enum ShrineKind {
    GHAST("ghast", "Ghast", 0xF0F0F0),
    BLAZE("blaze", "Blaze", 0xFFC23A),
    MAGMA_CUBE("magma_cube", "Magma Cube", 0xFF6A1E);

    private final String id;
    private final String mobName;
    private final int color;

    ShrineKind(String id, String mobName, int color) {
        this.id = id;
        this.mobName = mobName;
        this.color = color;
    }

    /** A stable key for saved data and translation keys. */
    public String id() {
        return id;
    }

    /** The mob's name as the game spells it, which is also how the shrines are named. */
    public String mobName() {
        return mobName;
    }

    /** The colour the kind is labelled in, {@code 0xRRGGBB}. */
    public int color() {
        return color;
    }
}
