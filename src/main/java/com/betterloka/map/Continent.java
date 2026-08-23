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
    KALROS("Kalros", "kalros", "north"),
    ASCALON("Ascalon", "ascalon", "west"),
    GARAMA("Garama", "garama", "south"),
    RIVINA("Rivina", "conquest", "lilboi"),
    BALAK("Balak", "conquest", "bigboi");

    private final String displayName;
    private final String instance;
    private final String world;

    Continent(String displayName, String instance, String world) {
        this.displayName = displayName;
        this.instance = instance;
        this.world = world;
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
