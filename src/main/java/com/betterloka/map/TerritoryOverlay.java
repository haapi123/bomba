package com.betterloka.map;

import java.util.Arrays;
import java.util.List;

/**
 * Paints the claims into a tile of ground, once, the way Loka's own map draws them.
 *
 * <p>The website hands every territory to Leaflet as a polygon with the style the marker file gives
 * it: a fill colour at the file's fill opacity, an outline colour at the file's outline opacity, and
 * an outline weight in screen pixels. Copying those values is the whole of looking the same. The map
 * used to substitute its own — fixed fill alphas, a per-town palette for outlines, thick outer rims
 * and thinned inner seams — and across the five continents the file uses six fill opacities, two
 * outline opacities and two weights, so no fixed table could ever have matched it.
 *
 * <p>Painting into the tile, rather than drawing the polygons every frame, is the other half. The
 * outlines were drawn as one small quad per pixel of edge, every frame: with a whole continent on
 * screen that was twenty thousand quads and three tenths of a second a frame, which is what made the
 * map lag. Painted once when the tile is decoded, a frame draws only the tiles, and the claims come
 * along at no cost at all.
 *
 * <p>Order matters and follows Leaflet's: each polygon is filled and then outlined before the next is
 * touched, so a neighbour's fill lies over the half of an outline that falls inside it, exactly as it
 * does on the website.
 *
 * <p>Plain Java on plain arrays, with no Minecraft in it, so it is tested outside the game.
 */
public final class TerritoryOverlay {
    /** The colour of open water where Loka rendered nothing, for claims that run out over it. */
    public static final int OCEAN_ARGB = 0xFF17263A;

    /**
     * How many texels wide one pixel of outline weight is painted.
     *
     * <p>A tile is drawn at between a half and a whole real pixel per texel — that is how its zoom is
     * chosen — so a one-texel line would come out at half a pixel and fade under filtering. One and a
     * half texels lands between three quarters of a pixel and a pixel and a half, which is the
     * website's one-pixel line give or take the zoom.
     */
    static final double TEXELS_PER_OUTLINE_PIXEL = 1.5;

    private TerritoryOverlay() {
    }

    /**
     * Paints every territory touching this tile over its pixels.
     *
     * @param argb           the tile's pixels, row by row, painted in place
     * @param size           the tile's width and height in texels
     * @param worldLeft      world X of the tile's western edge
     * @param worldTop       world Z of the tile's northern edge
     * @param blocksPerTexel how much ground one texel covers
     * @return whether anything was painted, so a tile of empty sea can stay unmade
     */
    public static boolean paint(int[] argb, int size, double worldLeft, double worldTop,
                                double blocksPerTexel, List<MapTerritory> territories) {
        double worldRight = worldLeft + size * blocksPerTexel;
        double worldBottom = worldTop + size * blocksPerTexel;
        boolean painted = false;
        double[] crossings = new double[64];
        for (MapTerritory territory : territories) {
            double halfOutlineBlocks = outlineTexels(territory) / 2 * blocksPerTexel;
            if (territory.maxX() + halfOutlineBlocks < worldLeft
                    || territory.minX() - halfOutlineBlocks > worldRight
                    || territory.maxZ() + halfOutlineBlocks < worldTop
                    || territory.minZ() - halfOutlineBlocks > worldBottom) {
                continue;
            }
            if (territory.xs().length > crossings.length) {
                crossings = new double[territory.xs().length];
            }
            double fillAlpha = clamp01(territory.fillOpacity());
            if (fillAlpha > 0) {
                int colour = territory.fillColor();
                fill(size, size, worldLeft, worldTop, blocksPerTexel, territory, crossings,
                        index -> argb[index] = blend(argb[index], colour, fillAlpha));
            }
            double opacity = clamp01(territory.strokeOpacity());
            if (opacity > 0) {
                int colour = territory.strokeColor();
                outline(size, size, worldLeft, worldTop, blocksPerTexel, territory, outlineTexels(territory),
                        (index, coverage) -> argb[index] = blend(argb[index], colour, opacity * coverage));
            }
            painted = true;
        }
        return painted;
    }

    /** Whether any territory reaches into this square of ground at all. */
    public static boolean touches(double worldLeft, double worldTop, double span,
                                  List<MapTerritory> territories) {
        for (MapTerritory territory : territories) {
            if (territory.maxX() >= worldLeft && territory.minX() <= worldLeft + span
                    && territory.maxZ() >= worldTop && territory.minZ() <= worldTop + span) {
                return true;
            }
        }
        return false;
    }

    /** A tile of plain water, for claims that run past the last ground Loka rendered. */
    public static int[] ocean(int size) {
        int[] argb = new int[size * size];
        Arrays.fill(argb, OCEAN_ARGB);
        return argb;
    }

    static double outlineTexels(MapTerritory territory) {
        return Math.max(1.0, territory.strokeWeight() * TEXELS_PER_OUTLINE_PIXEL);
    }

    /** Something to do to each texel a fill covers, by its index in the row-by-row array. */
    @FunctionalInterface
    private interface Covered {
        void at(int index);
    }

    /** Something to do to each texel an outline reaches, with how much of it the line covers. */
    @FunctionalInterface
    private interface Partly {
        void at(int index, double coverage);
    }

    private static void fill(int width, int height, double worldLeft, double worldTop,
                             double blocksPerTexel, MapTerritory territory, double[] crossings,
                             Covered covered) {
        double[] xs = territory.xs();
        double[] zs = territory.zs();
        int top = Math.max(0, (int) Math.floor((territory.minZ() - worldTop) / blocksPerTexel));
        int bottom = Math.min(height - 1, (int) Math.ceil((territory.maxZ() - worldTop) / blocksPerTexel));
        for (int row = top; row <= bottom; row++) {
            double worldZ = worldTop + (row + 0.5) * blocksPerTexel;
            int found = 0;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((zs[i] > worldZ) != (zs[j] > worldZ)) {
                    crossings[found++] = xs[i] + (xs[j] - xs[i]) * (worldZ - zs[i]) / (zs[j] - zs[i]);
                }
            }
            if (found < 2) {
                continue;
            }
            Arrays.sort(crossings, 0, found);
            for (int pair = 0; pair + 1 < found; pair += 2) {
                // Texel centres inside the span, so neighbours that share an edge share no texel.
                int from = Math.max(0, (int) Math.ceil((crossings[pair] - worldLeft) / blocksPerTexel - 0.5));
                int to = Math.min(width - 1,
                        (int) Math.ceil((crossings[pair + 1] - worldLeft) / blocksPerTexel - 0.5) - 1);
                int base = row * width;
                for (int x = from; x <= to; x++) {
                    covered.at(base + x);
                }
            }
        }
    }

    /**
     * Each edge as an anti-aliased line of the territory's outline weight.
     *
     * <p>Coverage is worked out from the distance to the edge rather than stepped pixel by pixel, so
     * the slanted sides of a hex come out as smooth as the straight ones.
     */
    private static void outline(int width, int height, double worldLeft, double worldTop,
                                double blocksPerTexel, MapTerritory territory, double outlineTexels,
                                Partly partly) {
        double half = outlineTexels / 2;
        double[] xs = territory.xs();
        double[] zs = territory.zs();
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            double x1 = (xs[j] - worldLeft) / blocksPerTexel;
            double y1 = (zs[j] - worldTop) / blocksPerTexel;
            double x2 = (xs[i] - worldLeft) / blocksPerTexel;
            double y2 = (zs[i] - worldTop) / blocksPerTexel;
            int minX = Math.max(0, (int) Math.floor(Math.min(x1, x2) - half - 1));
            int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x1, x2) + half + 1));
            int minY = Math.max(0, (int) Math.floor(Math.min(y1, y2) - half - 1));
            int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y1, y2) + half + 1));
            if (minX > maxX || minY > maxY) {
                continue;
            }
            double dx = x2 - x1;
            double dy = y2 - y1;
            double lengthSquared = dx * dx + dy * dy;
            for (int y = minY; y <= maxY; y++) {
                double py = y + 0.5;
                int base = y * width;
                for (int x = minX; x <= maxX; x++) {
                    double px = x + 0.5;
                    double t = lengthSquared == 0 ? 0
                            : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lengthSquared));
                    double ex = px - (x1 + t * dx);
                    double ey = py - (y1 + t * dy);
                    double distance = Math.sqrt(ex * ex + ey * ey);
                    double coverage = half + 0.5 - distance;
                    if (coverage <= 0) {
                        continue;
                    }
                    partly.at(base + x, Math.min(1, coverage));
                }
            }
        }
    }

    /** Transparent white: the colour of a highlight texel nothing covers. */
    static final int CLEAR_WHITE = 0x00FFFFFF;

    /**
     * A see-through white wash of some territories, for lighting up a search result.
     *
     * <p>Drawn over the map as one texture rather than shape by shape, for the reason the claims are
     * painted into the tiles: an outline drawn every frame as a quad per pixel of edge is what made
     * the map lag, and a town lights up a dozen hexes at once.
     *
     * <p>The texels nothing covers are white at zero alpha rather than black, so smooth sampling at
     * the edge fades the wash out instead of ringing it with grey.
     *
     * @param fillAlpha     how opaque the inside is, 0 to 1
     * @param outlineTexels how wide the edge is drawn, fully opaque
     * @return the texels row by row, ARGB with straight alpha
     */
    public static int[] highlight(int width, int height, double worldLeft, double worldTop,
                                  double blocksPerTexel, List<MapTerritory> territories,
                                  double fillAlpha, double outlineTexels) {
        double[] alpha = new double[width * height];
        double[] crossings = new double[64];
        double inside = clamp01(fillAlpha);
        for (MapTerritory territory : territories) {
            if (territory.xs().length > crossings.length) {
                crossings = new double[territory.xs().length];
            }
            fill(width, height, worldLeft, worldTop, blocksPerTexel, territory, crossings,
                    index -> alpha[index] = Math.max(alpha[index], inside));
            outline(width, height, worldLeft, worldTop, blocksPerTexel, territory, outlineTexels,
                    (index, coverage) -> alpha[index] = Math.max(alpha[index], coverage));
        }
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = ((int) Math.round(alpha[i] * 255) << 24) | 0xFFFFFF;
        }
        return argb;
    }

    /** {@code over} laid on {@code under} at {@code alpha}; the result stays opaque. */
    static int blend(int under, int over, double alpha) {
        double keep = 1 - alpha;
        int r = (int) Math.round(((under >> 16) & 0xFF) * keep + ((over >> 16) & 0xFF) * alpha);
        int g = (int) Math.round(((under >> 8) & 0xFF) * keep + ((over >> 8) & 0xFF) * alpha);
        int b = (int) Math.round((under & 0xFF) * keep + (over & 0xFF) * alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double clamp01(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }

    /**
     * A digest of everything about the claims that shows in a painted tile.
     *
     * <p>Tiles painted under one digest are redone when it changes — a capture, a new colour — and
     * not otherwise, so a refresh that finds nothing new repaints nothing.
     */
    public static long fingerprint(List<MapTerritory> territories) {
        long hash = 1125899906842597L;
        for (MapTerritory territory : territories) {
            hash = 31 * hash + territory.number().hashCode();
            hash = 31 * hash + territory.fillColor();
            hash = 31 * hash + territory.strokeColor();
            hash = 31 * hash + Double.hashCode(territory.fillOpacity());
            hash = 31 * hash + Double.hashCode(territory.strokeOpacity());
            hash = 31 * hash + territory.strokeWeight();
            hash = 31 * hash + Arrays.hashCode(territory.xs());
            hash = 31 * hash + Arrays.hashCode(territory.zs());
        }
        return hash;
    }
}
