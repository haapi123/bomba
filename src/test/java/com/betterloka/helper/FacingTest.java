package com.betterloka.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which way the coordinates say the player faces, and where their box goes. */
class FacingTest {
    @Test
    void theFourWaysAreWhereMinecraftPutsThem() {
        assertEquals(Facing.SOUTH, Facing.of(0));
        assertEquals(Facing.WEST, Facing.of(90));
        assertEquals(Facing.NORTH, Facing.of(180));
        assertEquals(Facing.EAST, Facing.of(270));
        assertEquals("-Z", Facing.NORTH.axes());
        assertEquals("+X -Z", Facing.NORTH_EAST.axes());
    }

    @Test
    void inBetweenIsTheNearestOfEight() {
        assertEquals(Facing.SOUTH, Facing.of(22));
        assertEquals(Facing.SOUTH_WEST, Facing.of(23));
        assertEquals(Facing.NORTH_EAST, Facing.of(225));
        assertEquals(Facing.SOUTH, Facing.of(359));
    }

    @Test
    void anyYawWorksNegativeOrPastAFullTurn() {
        assertEquals(Facing.EAST, Facing.of(-90));
        assertEquals(Facing.NORTH, Facing.of(-180));
        assertEquals(Facing.WEST, Facing.of(450));
        assertEquals(Facing.NORTH_EAST, Facing.of(-135));
    }

    @Test
    void theBoxSitsWhereTheFractionsSayAndComesBackTheSame() {
        assertArrayEquals(new int[]{2, 2}, CoordsHud.place(0, 0, 400, 300, 100, 20));
        assertArrayEquals(new int[]{298, 278}, CoordsHud.place(1, 1, 400, 300, 100, 20));
        int[] middle = CoordsHud.place(0.5, 0.5, 400, 300, 100, 20);
        double[] back = CoordsHud.fractions(middle[0], middle[1], 400, 300, 100, 20);
        assertEquals(0.5, back[0], 0.01);
        assertEquals(0.5, back[1], 0.01);
        assertArrayEquals(new int[]{298, 278}, CoordsHud.place(1, 1, 400, 300, 100, 20),
                "the far corner stays on screen");
        assertArrayEquals(CoordsHud.place(0.5, 0.5, 800, 600, 100, 20),
                CoordsHud.place(back[0], back[1], 800, 600, 100, 20), "a bigger screen keeps it in the middle");
    }
}
