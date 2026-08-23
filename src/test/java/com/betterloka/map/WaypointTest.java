package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaypointTest {
    private static Waypoint at(double x, double z) {
        return new Waypoint("Ice Wastes 119", "north", x, 64, z, 0x3AB3DA, "territory_owned");
    }

    @Test
    void measuresDistanceOnTheFlatRatherThanThroughTheGround() {
        // Height is not part of it: a territory two hundred blocks away and thirty blocks up is a
        // two-hundred-block walk, and the number is there to be walked by.
        assertEquals(300, at(300, 0).distanceTo(0, 0), 0.001);
        assertEquals(500, at(300, 400).distanceTo(0, 0), 0.001);
    }

    @Test
    void countsChunksTheWayAConquestPlayerDoes() {
        assertEquals(10, at(160, 0).chunksTo(0, 0));
        assertEquals(1, at(16, 0).chunksTo(0, 0));
        assertEquals(0, at(4, 0).chunksTo(0, 0));
    }

    @Test
    void writesCoordinatesInTheFormACommandTakes() {
        assertEquals("1234 64 -560",
                new Waypoint("x", "north", 1234.4, 64, -560.2, 0, null).coordinates());
    }

    @Test
    void switchesToKilometresWhenMetresStopBeingUseful() {
        assertEquals("412m", WaypointWorldRenderer.range(412));
        assertEquals("999m", WaypointWorldRenderer.range(999));
        assertEquals("2.4km", WaypointWorldRenderer.range(2400));
        assertEquals("1.0km", WaypointWorldRenderer.range(1000));
    }
}
