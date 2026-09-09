package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a whole-continent download costs, and that the number quoted is the number spent.
 *
 * <p>The figure on screen is what somebody agrees to before a request is sent, so it has to be the
 * same arithmetic the sweep then walks — a count that flatters the sweep is worse than no count.
 */
class TerrainDownloadTest {
    /** Ascalon, the widest of the five, measured from its claims. */
    private static final TerrainDownload.Bounds ASCALON =
            new TerrainDownload.Bounds(893, 6804, 244, 9280);

    private static int rowsTimesColumns(TerrainDownload.Bounds bounds, int level) {
        int step = 1 << level;
        int columns = (MapTerrain.tileXAt(bounds.maxX(), level)
                - MapTerrain.tileXAt(bounds.minX(), level)) / step + 1;
        int rows = (MapTerrain.tileYAt(bounds.minZ(), level)
                - MapTerrain.tileYAt(bounds.maxZ(), level)) / step + 1;
        return rows * columns;
    }

    @Test
    void theCountIsEveryTileOfEveryLevelDownToTheDeepest() {
        for (int deepest = 0; deepest <= MapTerrain.BASE_LEVEL; deepest++) {
            int expected = 0;
            for (int level = MapTerrain.BASE_LEVEL; level >= deepest; level--) {
                expected += rowsTimesColumns(ASCALON, level);
            }
            assertEquals(expected, TerrainDownload.tileCount(ASCALON, deepest),
                    "down to level " + deepest);
        }
    }

    /** Each level in is four times the ground, give or take the edges rounding outward. */
    @Test
    void eachLevelInIsAboutFourTimesTheTiles() {
        for (int level = MapTerrain.BASE_LEVEL; level > 0; level--) {
            int coarse = rowsTimesColumns(ASCALON, level);
            int fine = rowsTimesColumns(ASCALON, level - 1);
            double ratio = fine / (double) coarse;
            assertTrue(ratio > 3.4 && ratio < 4.6,
                    "level " + level + " to " + (level - 1) + " multiplied by " + ratio);
        }
    }

    /**
     * The default stops two levels short of the sharpest, and that has to stay a deliberate choice.
     *
     * <p>Going one deeper is about four times the disk and four times the requests at somebody
     * else's CDN, so it is not a number to drift.
     */
    @Test
    void theDefaultDepthIsTheOneThatWasCosted() {
        assertEquals(2, TerrainDownload.DEFAULT_DEEPEST_LEVEL);
        int atDefault = TerrainDownload.tileCount(ASCALON, TerrainDownload.DEFAULT_DEEPEST_LEVEL);
        int oneDeeper = TerrainDownload.tileCount(ASCALON, TerrainDownload.DEFAULT_DEEPEST_LEVEL - 1);
        assertTrue(oneDeeper > atDefault * 3,
                "one level deeper should be several times the work, was " + oneDeeper + " vs " + atDefault);
    }

    /** Bounds that make no sense are refused rather than swept as an empty or inverted rectangle. */
    @Test
    void boundsWithNothingInThemAreNotValid() {
        assertFalse(new TerrainDownload.Bounds(Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE).valid());
        assertTrue(ASCALON.valid());
        // A single point is a real, if tiny, continent.
        assertTrue(new TerrainDownload.Bounds(100, 100, 200, 200).valid());
    }

    @Test
    void progressReadsAsAFractionAndKnowsWhenItIsDone() {
        assertEquals(0, TerrainDownload.Progress.IDLE.fraction());
        assertFalse(TerrainDownload.Progress.IDLE.finished());

        var half = new TerrainDownload.Progress(Continent.BALAK, 50, 100, 1024, 40, true, false);
        assertEquals(0.5, half.fraction(), 1e-9);
        assertFalse(half.finished(), "a download still running is not finished");

        var over = new TerrainDownload.Progress(Continent.BALAK, 100, 100, 2048, 80, false, false);
        assertEquals(1.0, over.fraction(), 1e-9);
        assertTrue(over.finished());

        // Cancelled part-way is not finished, and must not report itself as such.
        var stopped = new TerrainDownload.Progress(Continent.BALAK, 30, 100, 512, 25, false, true);
        assertFalse(stopped.finished());
    }
}
