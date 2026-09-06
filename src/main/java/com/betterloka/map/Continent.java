package com.betterloka.map;

/**
 * The five continents Loka's public map serves.
 *
 * <p>Each is a separate Dynmap instance on {@code map.lokamc.com}, and its {@code world} is the same
 * key the game API files territories under — which is how a polygon drawn here and a territory
 * fetched from the API are known to be the same place.
 *
 * <p>{@code lilboi} and {@code bigboi} are Rivina and Balak. They read like internal names and were
 * once mistaken here for event maps, but Loka's own map serves them as continents alongside the
 * other three and they hold claimed territory like any of them.
 */
public enum Continent {
    KALROS("Kalros", "kalros", "north", 4184, 2204),
    ASCALON("Ascalon", "ascalon", "west", 3776, 4540),
    GARAMA("Garama", "garama", "south", 4216, 2636),
    RIVINA("Rivina", "conquest", "lilboi", 2136, 1326),
    BALAK("Balak", "conquest", "bigboi", 2128, 1766);

    private final String displayName;
    private final String instance;
    private final String world;
    private final double centerX;
    private final double centerZ;

    Continent(String displayName, String instance, String world, double centerX, double centerZ) {
        this.displayName = displayName;
        this.instance = instance;
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /**
     * Where Loka's own map opens this continent.
     *
     * <p>These are the coordinates its website passes to the map, not the centres Dynmap reports.
     * They differ, and by a lot — Dynmap puts Balak's centre at {@code 2302, 3010} against the
     * website's {@code 2128, 1766}, better than a thousand blocks off the island — because Dynmap's
     * is wherever the world spawned and the website's is where a person would want to be looking.
     */
    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public String displayName() {
        return displayName;
    }

    /** The Dynmap instance this continent is served by; Rivina and Balak share one. */
    public String instance() {
        return instance;
    }

    /** Loka's internal world key, the same one the game API uses. */
    public String world() {
        return world;
    }

    public static Continent ofWorld(String world) {
        for (Continent continent : values()) {
            if (continent.world.equalsIgnoreCase(world)) {
                return continent;
            }
        }
        return null;
    }
}
