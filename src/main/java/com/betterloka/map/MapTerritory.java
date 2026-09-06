package com.betterloka.map;

/**
 * One territory as Loka's own map draws it: the real outline, not a bounding box.
 *
 * <p>The shape comes from the map's marker data, so it is the same polygon the website fills in —
 * which is what makes clicking one here mean the same thing as clicking it there.
 *
 * @param number    the territory number, as shown in game
 * @param areaName  the region it sits in, e.g. {@code Cherry Grove}
 * @param owner     the town holding it, or {@code null} when it is neutral
 * @param alliance  the alliance holding it, or {@code null}
 * @param mutator   the active mutator's name, or {@code null}
 * @param xs        polygon vertices, world X
 * @param zs        polygon vertices, world Z; the same length as {@code xs}
 * @param centerX   where the territory's marker sits — the point worth walking to
 * @param fillColor   the colour Loka's map fills it with, {@code 0xRRGGBB}
 * @param strokeColor the colour it outlines it with — near-black on Loka's own map, which is what
 *                    separates one hex from the next
 * @param icon        the marker Loka draws in the middle: a keep for a held territory, a plainer
 *                    one for neutral ground
 * @param conquestPoints what holding this is worth per day, or {@code -1} where Loka does not say.
 *                    Only the Conquest continents carry it — Rivina publishes it on all eighteen of
 *                    its territories, and Kalros, Ascalon, Garama and Balak on none of theirs.
 */
public record MapTerritory(String number, String areaName, String owner, String alliance,
                           String mutator, double[] xs, double[] zs,
                           double centerX, double centerZ, int fillColor, int strokeColor,
                           String icon, int conquestPoints) {

    /** Whether Loka published a conquest-point value for this territory. */
    public boolean hasConquestPoints() {
        return conquestPoints >= 0;
    }

    public boolean neutral() {
        return owner == null || owner.isBlank();
    }

    /** @return the name to show, falling back to the number when the area has none. */
    public String label() {
        if (areaName == null || areaName.isBlank()) {
            return "#" + number;
        }
        return areaName + " " + number;
    }

    /**
     * Whether a world position is inside this territory.
     *
     * <p>Ray casting: count the edges a ray from the point crosses, and an odd count means inside.
     * Loka's territories are not rectangles — the sample polygons run to twenty-six vertices — so a
     * bounding-box test would claim ground that belongs to a neighbour.
     */
    public boolean contains(double x, double z) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean straddles = (zs[i] > z) != (zs[j] > z);
            if (straddles && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    public double minX() {
        return min(xs);
    }

    public double maxX() {
        return max(xs);
    }

    public double minZ() {
        return min(zs);
    }

    public double maxZ() {
        return max(zs);
    }

    private static double min(double[] values) {
        double lowest = Double.MAX_VALUE;
        for (double value : values) {
            lowest = Math.min(lowest, value);
        }
        return lowest;
    }

    private static double max(double[] values) {
        double highest = -Double.MAX_VALUE;
        for (double value : values) {
            highest = Math.max(highest, value);
        }
        return highest;
    }
}
