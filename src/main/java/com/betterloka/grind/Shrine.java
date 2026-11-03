package com.betterloka.grind;

/**
 * One Nether shrine: where it is, and the patch of ground around it that counts as being there.
 *
 * @param y the height, or {@code null} where it is not known — a marker is then drawn at eye level
 *          rather than at a made-up number
 */
public record Shrine(ShrineKind kind, int number, int x, Integer y, int z) {
    /**
     * Half the side of a shrine's area. The area is twenty blocks square around the coordinates, and
     * the whole column counts: shrines are told apart by where they stand on the map, not by height.
     */
    public static final int AREA_HALF = 10;

    /** A stable key for saved timers: {@code ghast-1}. */
    public String id() {
        return kind.id() + "-" + number;
    }

    /** {@code Ghast Shrine 1}. */
    public String label() {
        return kind.mobName() + " Shrine " + number;
    }

    public boolean hasY() {
        return y != null;
    }

    /** Whether a position is on the shrine's ground. */
    public boolean contains(double worldX, double worldZ) {
        return Math.abs(worldX - (x + 0.5)) <= AREA_HALF && Math.abs(worldZ - (z + 0.5)) <= AREA_HALF;
    }

    /** Straight-line distance across the ground, ignoring height. */
    public double distanceTo(double worldX, double worldZ) {
        double dx = x + 0.5 - worldX;
        double dz = z + 0.5 - worldZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** {@code 1037 177 1190}, or {@code 864 ~ 908} without a height, ready for a command. */
    public String coordinates() {
        return x + " " + (hasY() ? String.valueOf(y) : "~") + " " + z;
    }
}
