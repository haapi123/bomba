package com.betterloka.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The places on Loka worth a marker whatever you are doing: each continent's docks, its resource
 * island and its boss — Kalros' Witch Hunter, Balak's Warden, Garama's Golem — and everything at spawn.
 *
 * <p>Plain Java, so which continent you are on is worked out — and tested — outside the game.
 */
public final class ImportantPlaces {
    private ImportantPlaces() {
    }

    /**
     * Where the places are.
     *
     * <p>{@code spawnX}/{@code spawnZ} are each continent's world spawn as Loka's own map gives it
     * (its {@code configuration} centres) — the docks, where the boat comes in. The game is told its
     * world's spawn when it arrives, so that is how the mod tells the continents apart: Loka runs
     * them as separate servers, and their worlds may well all be called the same thing.
     */
    public enum Region {
        ALADRA("aladra", "Aladra", Integer.MIN_VALUE, Integer.MIN_VALUE, "aladra", "spawn", "hub", "lobby"),
        KALROS("kalros", "Kalros", 3717, 4234, "north", "kalros"),
        ASCALON("ascalon", "Ascalon", 5760, 5255, "west", "ascalon"),
        GARAMA("garama", "Garama", 4382, 2097, "south", "garama"),
        BALAK("balak", "Balak", 2302, 3010, "bigboi", "balak"),
        RIVINA("rivina", "Rivina", 2385, 1041, "lilboi", "rivina", "rivi");

        private final String id;
        private final String displayName;
        private final int spawnX;
        private final int spawnZ;
        private final String[] worldNames;

        Region(String id, String displayName, int spawnX, int spawnZ, String... worldNames) {
            this.id = id;
            this.displayName = displayName;
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
            this.worldNames = worldNames;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static Region byId(String id) {
            for (Region region : values()) {
                if (region.id.equalsIgnoreCase(id)) {
                    return region;
                }
            }
            return null;
        }
    }

    /** What a place's marker shows. */
    public enum Icon {
        DOCKS, RESOURCE_ISLAND, WITCH, WARDEN, GOLEM, HOUSE, MARKET, END_PORTAL, ZYRE, ROULETTE, NETHER_PORTAL,
        BIG_FIGHTS, SMALL_FIGHTS, COMPASS
    }

    /** One place: where, what, and the key its name is translated under. */
    public record Place(Region region, String id, int x, int y, int z, Icon icon) {
        public String nameKey() {
            return "betterloka.places." + id;
        }

        public double distanceTo(double fromX, double fromZ) {
            return Math.hypot(x + 0.5 - fromX, z + 0.5 - fromZ);
        }
    }

    public static final List<Place> ALL = List.of(
            new Place(Region.ALADRA, "town_portal", -145, 67, 21, Icon.HOUSE),
            new Place(Region.ALADRA, "loka_market", -73, 79, 57, Icon.MARKET),
            new Place(Region.ALADRA, "end_portal", -92, 113, 126, Icon.END_PORTAL),
            new Place(Region.ALADRA, "zyre_portal", -46, 115, 143, Icon.ZYRE),
            new Place(Region.ALADRA, "gambling", -144, 77, 181, Icon.ROULETTE),
            new Place(Region.ALADRA, "nether_portal", -266, 69, 128, Icon.NETHER_PORTAL),
            new Place(Region.ALADRA, "reins_teleports", -343, 75, 29, Icon.BIG_FIGHTS),
            new Place(Region.ALADRA, "rivi_portal", -311, 69, -33, Icon.SMALL_FIGHTS),
            new Place(Region.ALADRA, "continent_portals", -168, 65, -48, Icon.COMPASS),

            new Place(Region.KALROS, "kalros_docks", 3727, 80, 4205, Icon.DOCKS),
            new Place(Region.KALROS, "witch_hunter", 3099, 120, 5318, Icon.WITCH),
            new Place(Region.KALROS, "kalros_ri", 3171, 122, 5131, Icon.RESOURCE_ISLAND),

            new Place(Region.ASCALON, "ascalon_docks", 5720, 108, 5253, Icon.DOCKS),
            new Place(Region.ASCALON, "ascalon_ri", 6866, 107, 4532, Icon.RESOURCE_ISLAND),

            new Place(Region.GARAMA, "garama_docks", 4386, 83, 2061, Icon.DOCKS),
            new Place(Region.GARAMA, "garama_ri", 3342, 109, 1221, Icon.RESOURCE_ISLAND),
            new Place(Region.GARAMA, "golem", 3043, 85, 679, Icon.GOLEM),

            new Place(Region.BALAK, "balak_docks", 2300, 80, 3012, Icon.DOCKS),
            new Place(Region.BALAK, "warden", 3870, 100, 2482, Icon.WARDEN),

            new Place(Region.RIVINA, "rivi_docks", 2385, 85, 1041, Icon.DOCKS));

    /** The places on one continent, in the order above. */
    public static List<Place> on(Region region) {
        List<Place> out = new ArrayList<>();
        for (Place place : ALL) {
            if (place.region() == region) {
                out.add(place);
            }
        }
        return out;
    }

    /** How far a world's spawn may be from a continent's for the two to be taken as the same. */
    static final int SPAWN_MATCH_BLOCKS = 600;

    /** How near the middle of the world spawn sits on Aladra, the spawn server. */
    static final int ALADRA_SPAWN_RADIUS = 1000;

    /**
     * Which continent the player is on.
     *
     * <p>The world's name first, in case Loka names them — {@code north}, {@code bigboi}, its map's
     * own keys, or the continent's name. Failing that the world's spawn: each continent's is a known
     * place far from the others, and Aladra's is near the middle of the world.
     *
     * @param worldName the path of the world's key, e.g. {@code overworld}; may be {@code null}
     * @param hasSpawn  whether the game has been told the world's spawn
     * @return the continent, or {@code null} when it cannot be told
     */
    public static Region detect(String worldName, boolean nether, boolean hasSpawn, int spawnX, int spawnZ) {
        if (nether) {
            return null;
        }
        if (worldName != null) {
            String name = worldName.toLowerCase(Locale.ROOT);
            for (Region region : Region.values()) {
                for (String candidate : region.worldNames) {
                    if (name.equals(candidate) || name.endsWith("_" + candidate) || name.endsWith("/" + candidate)) {
                        return region;
                    }
                }
            }
        }
        if (!hasSpawn) {
            return null;
        }
        Region nearest = null;
        double best = Double.MAX_VALUE;
        for (Region region : Region.values()) {
            if (region == Region.ALADRA) {
                continue;
            }
            double distance = Math.hypot(spawnX - region.spawnX, spawnZ - region.spawnZ);
            if (distance < best) {
                best = distance;
                nearest = region;
            }
        }
        if (best <= SPAWN_MATCH_BLOCKS) {
            return nearest;
        }
        if (Math.hypot(spawnX, spawnZ) <= ALADRA_SPAWN_RADIUS) {
            return Region.ALADRA;
        }
        return null;
    }
}
