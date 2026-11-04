package com.betterloka.helper;

/**
 * Which way the player is looking, to eight points, and which way along the axes that is.
 *
 * <p>Minecraft's yaw is 0 facing south (+Z) and grows clockwise seen from above: 90 west, 180 north,
 * 270 east. Plain Java, so it is tested outside the game.
 */
public enum Facing {
    SOUTH("south", "+Z"),
    SOUTH_WEST("south_west", "-X +Z"),
    WEST("west", "-X"),
    NORTH_WEST("north_west", "-X -Z"),
    NORTH("north", "-Z"),
    NORTH_EAST("north_east", "+X -Z"),
    EAST("east", "+X"),
    SOUTH_EAST("south_east", "+X +Z");

    private final String id;
    private final String axes;

    Facing(String id, String axes) {
        this.id = id;
        this.axes = axes;
    }

    /** The point of the compass nearest a yaw, any yaw — negative or past a full turn included. */
    public static Facing of(float yaw) {
        double turned = ((yaw % 360) + 360) % 360;
        int index = (int) Math.round(turned / 45.0) % 8;
        return values()[index];
    }

    /** The translation key for its name: {@code North-East}. */
    public String key() {
        return "betterloka.helper.facing." + id;
    }

    /** Which way along the axes it points: {@code +X -Z}. */
    public String axes() {
        return axes;
    }
}
