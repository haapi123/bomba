package com.betterloka.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which edges are seams inside one town's land, and which are the edge of it. */
class TerritoryBordersTest {
    /** Two squares side by side, sharing the edge x=10 between z=0 and z=10. */
    private static MapTerritory square(String number, String owner, double x) {
        return new MapTerritory(number, "Test", owner, null, null,
                new double[] {x, x + 10, x + 10, x},
                new double[] {0, 0, 10, 10},
                x + 5, 5, 0x3AB3DA, 0x09090B, "territory_owned", -1, false, null);
    }

    @Test
    void aSeamBetweenTwoHexesOfOneTownIsInternal() {
        TerritoryBorders borders = TerritoryBorders.of(
                List.of(square("1", "Corvus", 0), square("2", "Corvus", 10)));

        assertTrue(borders.isInternal(10, 0, 10, 10));
        // Given either way round: both territories walk the shared edge in opposite directions.
        assertTrue(borders.isInternal(10, 10, 10, 0));
        // The outside of the pair is still a boundary.
        assertFalse(borders.isInternal(0, 0, 0, 10));
        assertFalse(borders.isInternal(20, 0, 20, 10));
    }

    @Test
    void aBorderBetweenTwoTownsIsNotInternal() {
        TerritoryBorders borders = TerritoryBorders.of(
                List.of(square("1", "Corvus", 0), square("2", "Invicta", 10)));

        assertFalse(borders.isInternal(10, 0, 10, 10));
    }

    @Test
    void neutralGroundHasNoInternalEdges() {
        TerritoryBorders borders = TerritoryBorders.of(
                List.of(square("1", null, 0), square("2", null, 10)));

        assertFalse(borders.isInternal(10, 0, 10, 10));
    }

    @Test
    void anEmptyContinentIsHarmless() {
        TerritoryBorders borders = TerritoryBorders.of(List.of());
        assertFalse(borders.isInternal(0, 0, 1, 1));
    }
}
