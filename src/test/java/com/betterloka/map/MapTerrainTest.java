package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tile grid, against addresses Loka's own map was watched asking for.
 *
 * <p>The four URLs below are real requests captured from a browser opening
 * {@code lokamc.com/map?c=balak}. Working the grid out from Dynmap's documentation is what produced
 * two versions of this that fetched from the wrong place, so what is pinned here is what the
 * website does, not what the maths ought to say.
 */
class MapTerrainTest {
    private static String url(String world, int level, int x, int y) {
        return MapTerrain.url(new MapTerrain.Key(world, level, x, y));
    }

    @Test
    void addressesMatchTheOnesTheWebsiteRequests() {
        assertEquals("https://assets.lokamc.com/static/images/map/bigboi/flat/2_-2/zzzz_80_-64.jpg",
                url("bigboi", 4, 80, -64));
        assertEquals("https://assets.lokamc.com/static/images/map/bigboi/flat/1_-2/zzzz_48_-64.jpg",
                url("bigboi", 4, 48, -64));
        assertEquals("https://assets.lokamc.com/static/images/map/bigboi/flat/4_0/zzzz_144_0.jpg",
                url("bigboi", 4, 144, 0));
        assertEquals("https://assets.lokamc.com/static/images/map/bigboi/flat/3_-3/zzzz_112_-80.jpg",
                url("bigboi", 4, 112, -80));
    }

    @Test
    void theSharpestZoomHasNoPrefix() {
        assertEquals("", MapTerrain.zoomPrefix(0));
        assertEquals("z_", MapTerrain.zoomPrefix(1));
        assertEquals("zzzzz_", MapTerrain.zoomPrefix(5));
        assertEquals("https://assets.lokamc.com/static/images/map/north/flat/3_-5/116_-133.jpg",
                url("north", 0, 116, -133));
    }

    /**
     * Balak's centre, the point Loka's website opens that continent on.
     *
     * <p>Both tiles named here were watched being fetched by the page centred on that point, which
     * is what settles the row: naming a row for its southern edge instead also puts 1766 inside a
     * tile, just one row off, and that reads as ground drawn 512 blocks north of the territories
     * sitting on it.
     */
    @Test
    void aWorldPointLandsOnTheTileCoveringIt() {
        assertEquals(64, MapTerrain.tileXAt(2128, 4));
        assertEquals(-48, MapTerrain.tileYAt(1766, 4));
        // The same point at a sharper zoom, also seen requested by the page.
        assertEquals(-52, MapTerrain.tileYAt(1766, 2));

        for (int level : new int[] {0, 2, 4, 5}) {
            double left = MapTerrain.tileWorldX(MapTerrain.tileXAt(2128, level));
            double top = MapTerrain.tileWorldZ(MapTerrain.tileYAt(1766, level));
            double span = MapTerrain.blocksPerTile(level);

            assertTrue(left <= 2128 && 2128 < left + span, "x " + left + " at level " + level);
            assertTrue(top <= 1766 && 1766 < top + span, "z " + top + " at level " + level);
        }
    }

    /** A row is named for its northern edge, the way a column is named for its western one. */
    @Test
    void aTileCoversGroundSouthAndEastOfTheCornerItIsNamedFor() {
        assertEquals(0, MapTerrain.tileWorldZ(0));
        assertEquals(-32, MapTerrain.tileWorldZ(1));
        assertEquals(1536, MapTerrain.tileWorldZ(-48));
        assertEquals(2048, MapTerrain.tileWorldX(64));
    }

    /** Positions are always counted in the sharpest zoom's tiles, so they are multiples of 2^level. */
    @Test
    void coarserZoomsSnapToTheirOwnGrid() {
        for (int level = 0; level <= MapTerrain.BASE_LEVEL; level++) {
            int step = 1 << level;
            assertEquals(0, MapTerrain.tileXAt(3717, level) % step, "level " + level);
            assertEquals(0, MapTerrain.tileYAt(4234, level) % step, "level " + level);
        }
    }

    /**
     * The level picked always has ground at least as fine as the screen asks for, never coarser.
     *
     * <p>A level one step out is a tile stretched to twice its size, and stretching is exactly what
     * made the ground come out in squares. The one place it is allowed is at the ends of what Loka
     * renders.
     */
    @Test
    void theLevelPickedIsNeverCoarserThanTheScreen() {
        // Deliberately not only powers of two. An earlier version of this test used nothing else,
        // and every power of two happens to land on an exact match — so it passed while the choice
        // was a step out at every zoom in between, which is where a map spends most of its time.
        double[] zooms = {0.05, 0.125, 0.2, 0.25, 0.4, 0.5, 0.9, 1, 1.182, 1.7, 2, 3.3, 4, 4.508,
                6.5, 7.9, 8, 13.5, 16, 64};
        for (double blocksPerPixel : zooms) {
            int level = MapTerrain.levelFor(blocksPerPixel);
            assertTrue(level >= 0 && level <= MapTerrain.BASE_LEVEL, "level " + level);
            double source = MapTerrain.blocksPerTile(level) / (double) MapTerrain.TILE_PIXELS;

            // Level 0 is as sharp as Loka renders, so closer than that has to be magnified.
            if (level > 0) {
                assertTrue(source <= blocksPerPixel + 1e-9,
                        "at " + blocksPerPixel + " blocks a pixel, level " + level
                                + " is drawn at " + source + " and would be stretched");
            }
            // And as far out as it can go while staying that sharp, or the map fetches four times
            // the tiles for ground finer than the screen can show.
            if (level < MapTerrain.BASE_LEVEL) {
                double next = MapTerrain.blocksPerTile(level + 1) / (double) MapTerrain.TILE_PIXELS;
                assertTrue(next > blocksPerPixel + 1e-9,
                        "at " + blocksPerPixel + " blocks a pixel, level " + (level + 1)
                                + " at " + next + " would still have done");
            }
        }
    }

    /**
     * Tiles of one level sit on a whole-tile grid with nothing between them.
     *
     * <p>The screen draws a level as one sheet scaled to the view and places each tile at its index
     * within that sheet, which is only seamless if an index step is exactly a tile's span of world.
     * This is the arithmetic that holds it together.
     */
    @Test
    void oneIndexStepIsExactlyOneTileOfWorld() {
        for (int level = 0; level <= MapTerrain.BASE_LEVEL; level++) {
            int step = 1 << level;
            int firstX = MapTerrain.tileXAt(3717, level);
            int lastY = MapTerrain.tileYAt(4234, level);
            double span = MapTerrain.blocksPerTile(level);
            for (int i = 0; i < 4; i++) {
                assertEquals(i * span, MapTerrain.tileWorldX(firstX + i * step) - MapTerrain.tileWorldX(firstX),
                        1e-9, "level " + level + " column " + i);
                // Rows count the other way: a larger index is further north, so the offset from the
                // northernmost row of a window grows as the index falls.
                assertEquals(i * span, MapTerrain.tileWorldZ(lastY - i * step) - MapTerrain.tileWorldZ(lastY),
                        1e-9, "level " + level + " row " + i);
            }
        }
    }

    @Test
    void tilesEitherSideOfTheOriginDoNotCollapseOntoOne() {
        // Truncation instead of a floor puts -31 and 0 in the same tile, and the ground north of
        // the origin is then drawn one tile out for the whole continent.
        assertEquals(-32, MapTerrain.tileXAt(-1, 5));
        assertEquals(0, MapTerrain.tileXAt(1, 5));
        assertEquals(-1, MapTerrain.tileXAt(-1, 0));
        assertEquals(0, MapTerrain.tileXAt(1, 0));

        assertEquals(1, MapTerrain.tileYAt(-1, 0));
        assertEquals(0, MapTerrain.tileYAt(1, 0));
    }

    /** Whatever the zoom, the tile picked for a point is the one whose span holds it. */
    @Test
    void everyZoomAgreesOnWhichTileHoldsAPoint() {
        for (double worldX : new double[] {-2000, -33, -1, 0, 1, 861, 2128, 4088}) {
            for (double worldZ : new double[] {-2000, -33, -1, 0, 1, 860, 1766, 3034}) {
                for (int level = 0; level <= MapTerrain.BASE_LEVEL; level++) {
                    double span = MapTerrain.blocksPerTile(level);
                    double left = MapTerrain.tileWorldX(MapTerrain.tileXAt(worldX, level));
                    double top = MapTerrain.tileWorldZ(MapTerrain.tileYAt(worldZ, level));

                    assertTrue(left <= worldX && worldX < left + span,
                            "x " + worldX + " level " + level + " -> " + left);
                    assertTrue(top <= worldZ && worldZ < top + span,
                            "z " + worldZ + " level " + level + " -> " + top);
                }
            }
        }
    }

    @Test
    void theZoomChosenKeepsTilesNearTheSizeTheyWereDrawn() {
        // Far out: the coarse layer, or a continent would be thousands of tiles.
        assertEquals(MapTerrain.BASE_LEVEL, MapTerrain.levelFor(30));
        assertEquals(MapTerrain.BASE_LEVEL, MapTerrain.levelFor(8));
        // Close in: the sharpest Loka renders.
        assertEquals(0, MapTerrain.levelFor(0.25));

        // In between, a tile is drawn at no more than the 128 pixels it holds — the other way round
        // from what this used to require, and the correction is the point. A tile drawn larger than
        // it is has been stretched, which is the blockiness; drawn smaller it has merely been shrunk,
        // which is sharp. The zoom running out at either end is the only excuse for stretching.
        for (double blocksPerPixel : new double[] {0.5, 1, 2, 4, 6, 12}) {
            int level = MapTerrain.levelFor(blocksPerPixel);
            double onScreen = MapTerrain.blocksPerTile(level) / blocksPerPixel;
            assertTrue(onScreen <= MapTerrain.TILE_PIXELS + 1e-9
                            || level == 0 || level == MapTerrain.BASE_LEVEL,
                    "tile stretched to " + onScreen + "px at " + blocksPerPixel);
            assertTrue(level <= MapTerrain.BASE_LEVEL);
        }
    }
}
