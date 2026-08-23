package com.betterloka.map;

/**
 * A place the player has marked on the map and wants to find in the world.
 *
 * @param label     what to draw beside it
 * @param world     Loka's world key, so a marker set on one continent is not drawn on another
 * @param x         world coordinates of the territory's own marker — the point worth walking to
 * @param icon      the marker Loka draws for this territory, so the one in the world is the same one
 *                  seen on the map; {@code null} falls back to a plain diamond
 */
public record Waypoint(String label, String world, double x, double y, double z, int color,
                       String icon) {

    /** Straight-line distance from a position, ignoring height. */
    public double distanceTo(double fromX, double fromZ) {
        double dx = x - fromX;
        double dz = z - fromZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Distance in chunks, which is what a Conquest player counts in. */
    public int chunksTo(double fromX, double fromZ) {
        return (int) Math.round(distanceTo(fromX, fromZ) / 16.0);
    }

    /** {@code 1234 64 -560}, the form that can be pasted straight into a command. */
    public String coordinates() {
        return String.format(java.util.Locale.ROOT, "%d %d %d",
                Math.round(x), Math.round(y), Math.round(z));
    }
}
