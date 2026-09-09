package com.betterloka.towns;

import com.betterloka.api.model.Territory;

/**
 * One thing that happened to a territory.
 *
 * <p>The distinction that matters is between a territory changing hands and a town ceasing to exist.
 * A territory taken in a fight moves from one living town to another and says nothing about either;
 * a territory still recorded to a town that Loka no longer has means that town fell.
 */
public record TownLogEvent(long at, Kind kind, String townId, String townName, String world,
                           String num, String areaName, int x, int y, int z, String otherTown) {

    public enum Kind {
        /**
         * The holding town no longer exists. This is the one worth watching for: the territory still
         * names a town, but Loka has deleted it.
         */
        TOWN_FELL,
        /** Taken from one living town by another — a fight, and not interesting here. */
        CAPTURED,
        /** Given up without anyone taking it. */
        RELEASED,
        /** Claimed from nobody. */
        CLAIMED;

        public boolean isTownGone() {
            return this == TOWN_FELL;
        }
    }

    public static TownLogEvent of(Kind kind, Territory territory, String townId, String townName,
                                  String otherTown) {
        return new TownLogEvent(System.currentTimeMillis(), kind, townId, townName, territory.world(),
                territory.num(), territory.areaName(), territory.x(), territory.y(), territory.z(),
                otherTown);
    }

    /**
     * A town that appeared in Loka's deleted list since the last poll, with no territory attached.
     *
     * <p>The territory-bound form only ever saw a town that left dangling claims behind it, and Loka
     * clears those — so a town whose land was reclaimed before anyone looked was invisible for good.
     * This is the town falling, which is the thing being watched for; the land it leaves is a
     * separate question with its own list.
     */
    public static TownLogEvent townDeleted(String townId, String townName, String world) {
        return new TownLogEvent(System.currentTimeMillis(), Kind.TOWN_FELL, townId, townName,
                world, null, null, 0, 0, 0, null);
    }

    /** Whether this names a particular territory, or only the town. */
    public boolean hasTerritory() {
        return num != null && !num.isBlank();
    }

    public String continent() {
        return Territory.continentOf(world);
    }

    public String coordinates() {
        return x + ", " + y + ", " + z;
    }

    /** Same territory, same thing happening to it — used to keep the log from repeating itself. */
    public String identity() {
        return kind + "|" + world + "/" + num + "|" + townId + "|" + otherTown;
    }
}
