package com.betterloka.map;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claims painted into a tile, checked pixel by pixel against what Leaflet would draw.
 *
 * <p>The tile in these tests is 64 texels covering 64 blocks, one block a texel, so a world
 * coordinate and a texel index are the same number and the expected pixels can be read straight off
 * the shapes.
 */
class TerritoryOverlayTest {
    private static final int SIZE = 64;
    private static final int GROUND = 0xFF808080;

    private static MapTerritory square(String number, double x, double z, double side, int fill,
                                       double fillOpacity, int stroke, double strokeOpacity, int weight) {
        return new MapTerritory(number, "Test", "Owner", null, null,
                new double[] {x, x + side, x + side, x},
                new double[] {z, z, z + side, z + side},
                x + side / 2, z + side / 2, fill, stroke, "territory_owned", -1, false, null,
                fillOpacity, strokeOpacity, weight);
    }

    private static int[] ground() {
        int[] argb = new int[SIZE * SIZE];
        Arrays.fill(argb, GROUND);
        return argb;
    }

    private static int at(int[] argb, int x, int y) {
        return argb[y * SIZE + x];
    }

    @Test
    void theInsideIsTheFillColourAtTheFileOpacity() {
        int[] tile = ground();
        MapTerritory claim = square("1", 10, 10, 30, 0xFF3C44AA, 0.65, 0xFF141638, 1.0, 1);
        assertTrue(TerritoryOverlay.paint(tile, SIZE, 0, 0, 1, List.of(claim)));

        // Well inside, where no outline reaches: exactly the fill laid over the ground at 0.65.
        int expected = TerritoryOverlay.blend(GROUND, 0xFF3C44AA, 0.65);
        assertEquals(expected, at(tile, 25, 25));
    }

    @Test
    void groundOutsideEveryClaimIsLeftAlone() {
        int[] tile = ground();
        TerritoryOverlay.paint(tile, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 20, 0xFF3C44AA, 0.65, 0xFF141638, 1.0, 1)));
        assertEquals(GROUND, at(tile, 50, 50));
        assertEquals(GROUND, at(tile, 2, 2));
    }

    /** The edge is drawn in the outline colour — that is what separates two claims of one town. */
    @Test
    void theEdgeCarriesTheOutlineColour() {
        int[] tile = ground();
        int stroke = 0xFF09090B;
        TerritoryOverlay.paint(tile, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 30, 0xFFFFFFFF, 0.0, stroke, 1.0, 1)));
        // The texel straddling the western edge at x = 10 is mostly outline.
        int onEdge = at(tile, 9, 25) == GROUND ? at(tile, 10, 25) : at(tile, 9, 25);
        assertTrue(brightness(onEdge) < brightness(GROUND) * 0.6,
                "the outline should darken the edge, got " + Integer.toHexString(onEdge));
    }

    /** Twenty territories in the file have no fill at all: they are drawn as an outline only. */
    @Test
    void aZeroFillOpacityLeavesTheInsideUntouched() {
        int[] tile = ground();
        TerritoryOverlay.paint(tile, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 30, 0xFFFF0000, 0.0, 0xFF000000, 1.0, 1)));
        assertEquals(GROUND, at(tile, 25, 25));
    }

    @Test
    void aLighterOutlineOpacityDarkensLess() {
        int[] full = ground();
        int[] light = ground();
        TerritoryOverlay.paint(full, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 30, 0, 0.0, 0xFF000000, 1.0, 1)));
        TerritoryOverlay.paint(light, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 30, 0, 0.0, 0xFF000000, 0.7, 1)));
        assertTrue(brightness(at(light, 25, 10)) > brightness(at(full, 25, 10)),
                "0.7 opacity must darken less than 1.0");
    }

    /**
     * Two claims sharing an edge must not both fill the texels along it.
     *
     * <p>If they did, the seam would be the two fills stacked — darker, and a different colour from
     * either side — which the website never shows.
     */
    @Test
    void neighboursNeverBothFillTheSameTexel() {
        int[] left = ground();
        int[] right = ground();
        int[] both = ground();
        MapTerritory a = square("1", 10, 10, 20, 0xFFFF0000, 0.5, 0, 0.0, 1);
        MapTerritory b = square("2", 30, 10, 20, 0xFF0000FF, 0.5, 0, 0.0, 1);
        TerritoryOverlay.paint(left, SIZE, 0, 0, 1, List.of(a));
        TerritoryOverlay.paint(right, SIZE, 0, 0, 1, List.of(b));
        TerritoryOverlay.paint(both, SIZE, 0, 0, 1, List.of(a, b));
        for (int x = 0; x < SIZE; x++) {
            boolean inA = at(left, x, 20) != GROUND;
            boolean inB = at(right, x, 20) != GROUND;
            assertFalse(inA && inB, "texel " + x + " filled by both neighbours");
        }
    }

    /** Leaflet fills and outlines each polygon before the next, so a later fill lies over an earlier outline. */
    @Test
    void aLaterFillLiesOverTheHalfOfAnEarlierOutlineInsideIt() {
        MapTerritory first = square("1", 10, 10, 20, 0, 0.0, 0xFF000000, 1.0, 2);
        MapTerritory second = square("2", 30, 10, 20, 0xFFFFFFFF, 0.9, 0, 0.0, 1);
        int[] tile = ground();
        TerritoryOverlay.paint(tile, SIZE, 0, 0, 1, List.of(first, second));
        int[] outlineOnly = ground();
        TerritoryOverlay.paint(outlineOnly, SIZE, 0, 0, 1, List.of(first));
        // Just inside the second claim, the first one's outline has been painted over in white.
        assertTrue(brightness(at(tile, 30, 20)) > brightness(at(outlineOnly, 30, 20)));
    }

    @Test
    void aClaimThatMissesTheTilePaintsNothing() {
        int[] tile = ground();
        boolean painted = TerritoryOverlay.paint(tile, SIZE, 0, 0, 1,
                List.of(square("1", 500, 500, 30, 0xFFFF0000, 0.65, 0xFF000000, 1.0, 1)));
        assertFalse(painted);
        assertTrue(Arrays.stream(tile).allMatch(p -> p == GROUND));
    }

    /** The tile's own world offset and scale are honoured, not assumed to start at the origin. */
    @Test
    void worldCoordinatesAreMappedThroughTheTilesPlaceAndScale() {
        int[] tile = ground();
        // A tile covering x 1000..1128 and z 2000..2128 at two blocks a texel.
        MapTerritory claim = square("1", 1040, 2040, 40, 0xFF00FF00, 1.0, 0, 0.0, 1);
        TerritoryOverlay.paint(tile, SIZE, 1000, 2000, 2, List.of(claim));
        assertEquals(0xFF00FF00, at(tile, 30, 30), "world 1060,2060 is texel 30,30");
        assertEquals(GROUND, at(tile, 5, 5));
    }

    @Test
    void touchesTellsWhetherATileNeedsMakingAtAll() {
        List<MapTerritory> claims = List.of(square("1", 100, 100, 50, 0, 0.65, 0, 1.0, 1));
        assertTrue(TerritoryOverlay.touches(64, 64, 64, claims));
        assertFalse(TerritoryOverlay.touches(1000, 1000, 64, claims));
    }

    @Test
    void theFingerprintFollowsWhatShowsAndNothingElse() {
        MapTerritory claim = square("1", 0, 0, 10, 0xFF3C44AA, 0.65, 0xFF09090B, 1.0, 1);
        long before = TerritoryOverlay.fingerprint(List.of(claim));
        assertEquals(before, TerritoryOverlay.fingerprint(
                List.of(square("1", 0, 0, 10, 0xFF3C44AA, 0.65, 0xFF09090B, 1.0, 1))),
                "the same claims must not repaint anything");
        assertNotEquals(before, TerritoryOverlay.fingerprint(
                List.of(square("1", 0, 0, 10, 0xFFBB890C, 0.65, 0xFF09090B, 1.0, 1))),
                "a capture changes the colour, and that must repaint");
        assertNotEquals(before, TerritoryOverlay.fingerprint(
                List.of(square("1", 0, 0, 10, 0xFF3C44AA, 0.92, 0xFF09090B, 1.0, 1))));
    }

    @Test
    void blendingAtTheEndsGivesBackEitherColour() {
        assertEquals(0xFF102030, TerritoryOverlay.blend(0xFF102030, 0xFFFFFFFF, 0));
        assertEquals(0xFFFFFFFF, TerritoryOverlay.blend(0xFF102030, 0xFFFFFFFF, 1));
    }

    // --- the search highlight ---

    @Test
    void theHighlightWashesTheInsideAtTheAskedAlpha() {
        int[] mask = TerritoryOverlay.highlight(SIZE, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 30, 0, 0.65, 0, 1.0, 1)), 0.4, 2);
        assertEquals(Math.round(0.4 * 255), at(mask, 25, 25) >>> 24);
        assertEquals(0xFFFFFF, at(mask, 25, 25) & 0xFFFFFF, "always white; only the alpha varies");
    }

    @Test
    void theHighlightsEdgeIsOpaqueAndTheRestClear() {
        int[] mask = TerritoryOverlay.highlight(SIZE, SIZE, 0, 0, 1,
                List.of(square("1", 10, 10, 30, 0, 0.65, 0, 1.0, 1)), 0.4, 2);
        assertEquals(0xFF, Math.max(at(mask, 9, 25), at(mask, 10, 25)) >>> 24);
        assertEquals(TerritoryOverlay.CLEAR_WHITE, at(mask, 55, 55),
                "clear texels are white, so smooth sampling does not ring the edge with grey");
    }

    @Test
    void theHighlightCoversEveryTerritoryGivenAndCanBeOblong() {
        int width = 80;
        int height = 40;
        int[] mask = TerritoryOverlay.highlight(width, height, 0, 0, 1,
                List.of(square("1", 5, 5, 20, 0, 0.65, 0, 1.0, 1),
                        square("2", 50, 5, 20, 0, 0.65, 0, 1.0, 1)), 0.4, 2);
        assertTrue((mask[15 * width + 15] >>> 24) > 0);
        assertTrue((mask[15 * width + 60] >>> 24) > 0);
        assertEquals(0, mask[15 * width + 38] >>> 24, "the ground between them stays clear");
    }

    private static double brightness(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }
}
