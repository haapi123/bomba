package com.betterloka.grind;

import java.util.List;

/**
 * Loka's Nether shrines: where each is, and whose ground a position is on.
 *
 * <p>Plain Java with no Minecraft in it, so it is tested outside the game.
 */
public final class Shrines {
    /** How long a shrine takes to come back, until the player sets it otherwise. */
    public static final long COOLDOWN_MILLIS = 20 * 60 * 1000L;

    /**
     * The cooldown 0.34 assumed, which is what its saved end times were counted with. Only used to
     * turn those back into start times.
     */
    static final long LEGACY_COOLDOWN_MILLIS = 15 * 60 * 1000L;

    /**
     * Every shrine, as the player gave them.
     *
     * <p>Blaze Shrine 2 came with two numbers rather than three — {@code 864, 908} — which can only
     * be X and Z: no height in the Nether reaches 864. Its height is left unknown rather than
     * invented.
     */
    public static final List<Shrine> ALL = List.of(
            new Shrine(ShrineKind.GHAST, 1, 1037, 177, 1190),
            new Shrine(ShrineKind.GHAST, 2, 527, 129, 963),
            new Shrine(ShrineKind.GHAST, 3, 1207, 71, 1386),
            new Shrine(ShrineKind.GHAST, 4, 630, 67, 1230),
            new Shrine(ShrineKind.BLAZE, 1, 1169, 68, 1285),
            new Shrine(ShrineKind.BLAZE, 2, 864, null, 908),
            new Shrine(ShrineKind.BLAZE, 3, 351, 172, 1173),
            new Shrine(ShrineKind.BLAZE, 4, 397, 72, 1267),
            new Shrine(ShrineKind.MAGMA_CUBE, 1, 1016, 71, 1202),
            new Shrine(ShrineKind.MAGMA_CUBE, 2, 1347, 178, 1346),
            new Shrine(ShrineKind.MAGMA_CUBE, 3, 675, 153, 1194));

    private Shrines() {
    }

    public static Shrine byId(String id) {
        for (Shrine shrine : ALL) {
            if (shrine.id().equals(id)) {
                return shrine;
            }
        }
        return null;
    }

    /** The shrine whose ground a position is on, the nearest one should two ever touch. */
    public static Shrine at(double worldX, double worldZ) {
        Shrine best = null;
        for (Shrine shrine : ALL) {
            if (shrine.contains(worldX, worldZ)
                    && (best == null || shrine.distanceTo(worldX, worldZ) < best.distanceTo(worldX, worldZ))) {
                best = shrine;
            }
        }
        return best;
    }
}
