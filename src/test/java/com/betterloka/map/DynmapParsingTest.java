package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The geometry, against shapes whose answers can be worked out by hand. */
class DynmapParsingTest {
    private static MapTerritory square(double x, double z, double size) {
        return new MapTerritory("1", "Test", null, null, null,
                new double[] {x, x + size, x + size, x},
                new double[] {z, z, z + size, z + size},
                x + size / 2, z + size / 2, 0x3AB3DA, 0x09090B, "territory_neutral");
    }

    @Test
    void aPointInsideASquareIsInside() {
        MapTerritory territory = square(100, 200, 50);

        assertTrue(territory.contains(125, 225));
        assertTrue(territory.contains(101, 201));
        assertFalse(territory.contains(99, 225));
        assertFalse(territory.contains(125, 199));
        assertFalse(territory.contains(151, 225));
        assertFalse(territory.contains(125, 251));
    }

    @Test
    void anLShapeExcludesTheNotch() {
        // The point of testing a concave shape: Loka's territories run to twenty-six vertices, and a
        // bounding box would hand this corner to the wrong territory.
        MapTerritory shape = new MapTerritory("2", "L", null, null, null,
                new double[] {0, 100, 100, 50, 50, 0},
                new double[] {0, 0, 50, 50, 100, 100},
                25, 25, 0x000000, 0x09090B, "territory_neutral");

        assertTrue(shape.contains(25, 25), "inside the thick part");
        assertTrue(shape.contains(25, 75), "inside the tall part");
        assertFalse(shape.contains(75, 75), "the notch is not part of the shape");
    }

    @Test
    void reportsItsOwnExtent() {
        MapTerritory territory = square(-300, 40, 20);

        assertEquals(-300, territory.minX());
        assertEquals(-280, territory.maxX());
        assertEquals(40, territory.minZ());
        assertEquals(60, territory.maxZ());
    }

    @Test
    void labelsFallBackToTheNumberWhenTheAreaHasNoName() {
        MapTerritory named = new MapTerritory("129", "Cherry Grove", "Corvus", "Falcon Fury", null,
                new double[] {0, 1, 1}, new double[] {0, 0, 1}, 0, 0, 0, 0, "territory_owned");
        assertEquals("Cherry Grove 129", named.label());
        assertFalse(named.neutral());

        MapTerritory nameless = new MapTerritory("7", null, null, null, null,
                new double[] {0, 1, 1}, new double[] {0, 0, 1}, 0, 0, 0, 0, "territory_neutral");
        assertEquals("#7", nameless.label());
        assertTrue(nameless.neutral());
    }

    @Test
    void readsATownCardOffItsMarker() {
        MapTown town = DynmapApi.parseTown(
                "<h2>Vanguard<br/><small>ChickenCurry_0 Alliance - 183 strength<br/>"
                        + "97 members | 2 territories</small></h2>", 1200, -340);

        assertEquals("Vanguard", town.name());
        assertEquals("ChickenCurry_0 Alliance", town.alliance());
        assertEquals(183.0, town.strength());
        assertEquals(97, town.members());
        assertEquals(2, town.territories());
    }

    @Test
    void aTownWithNoAllianceSaysSo() {
        MapTown town = DynmapApi.parseTown(
                "<h2>Aqronso's Town<br/><small>100.0 strength<br/>1 members | 0 territories</small></h2>",
                0, 0);

        assertEquals("Aqronso's Town", town.name());
        assertFalse(town.hasAlliance());
        assertEquals(100.0, town.strength());
        assertEquals(1, town.members());
    }

    /**
     * The card a held territory actually publishes, byte for byte from Kalros.
     *
     * <p>This is the shape that broke: the pattern for the region's name used to reach from the
     * first line break through the owner's markup to the number, so the name came back as
     * {@code <small>Owner: Corvus</small><small><br/>Cherry Grove} and the player read the tags.
     */
    private static final String HELD_TERRITORY =
            "<h2>Falcon Fury Territory<br/><small>Owner: Corvus</small><small><br/>"
                    + "Cherry Grove 129<br/><br/><h3><b>Mutator: Lone Wolf</b></h3>"
                    + "You gain Strength III if you have no friendlies near you.<br/><br/>"
                    + "Falcon Fury owns the Cherry Grove</small></h2>";

    @Test
    void aHeldTerritoryNamesItsRegionAndNotTheMarkupAroundIt() {
        assertEquals("Cherry Grove", DynmapApi.areaName(HELD_TERRITORY));
    }

    @Test
    void neutralGroundStillNamesItsRegion() {
        assertEquals("Ice Wastes", DynmapApi.areaName(
                "<h2>Ice Wastes - Neutral<small><br/>Ice Wastes 119<br/><br/></small></h2>"));
    }

    @Test
    void aTownCardWithEscapedTextComesOutReadable() {
        MapTown town = DynmapApi.parseTown(
                "<h2>Aqronso&#39;s Town<br/><small>Bell &amp; Sons - 12 strength<br/>"
                        + "3 members | 1 territories</small></h2>", 10, 20);

        assertEquals("Aqronso's Town", town.name());
        assertEquals("Bell & Sons", town.alliance());
        assertEquals(10.0, town.x());
        assertEquals(20.0, town.z());
    }

    @Test
    void everyContinentMapsToTheWorldTheGameApiUses() {
        assertEquals(Continent.KALROS, Continent.ofWorld("north"));
        assertEquals(Continent.ASCALON, Continent.ofWorld("west"));
        assertEquals(Continent.GARAMA, Continent.ofWorld("south"));
        // The two that were once written off as event maps.
        assertEquals(Continent.RIVINA, Continent.ofWorld("lilboi"));
        assertEquals(Continent.BALAK, Continent.ofWorld("bigboi"));
        assertEquals(null, Continent.ofWorld("ctw"));
    }
}
