package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Conquest continents publish a different card, and reading only the regular one made the whole
 * of Rivina and Balak look unclaimed.
 *
 * <p>Every label below is verbatim from {@code marker_lilboi.json} or {@code marker_bigboi.json}.
 */
class ConquestParsingTest {
    private static final String HELD =
            "<h2>Abuju Brotherhood Territory<small><br/>The Stony Atoll 1<br/></small></h2>"
                    + "<br/><h1>80 CP</h1><h3>Worth 80 Conquest Points per day</h3>";

    private static final String NEUTRAL =
            "<h2>The Jade Highlands - Neutral<small><br/>The Jade Highlands 14<br/></small></h2>"
                    + "<br/><h1>36 CP</h1><h3>Worth 36 Conquest Points per day</h3>";

    /** Balak's cards have the same shape but publish no point value at all. */
    private static final String BALAK_NEUTRAL =
            "<h2>Desolate Coast - Neutral<small><br/>Desolate Coast 16<br/></small></h2><br/>";

    @Test
    void aHeldConquestTerritoryNamesItsHolder() {
        MapTerritory territory = DynmapApi.parseForTest("1", HELD, "territory_owned");

        assertFalse(territory.neutral(), "Rivina and Balak both read as unclaimed before this");
        assertEquals("Abuju Brotherhood", territory.owner());
        assertEquals("The Stony Atoll", territory.areaName());
    }

    /**
     * A neutral card's heading is the region's name, not a holder.
     *
     * <p>Which is why the heading is only trusted when Dynmap's own icon says the ground is held:
     * taking it at face value would report "The Jade Highlands" as owning itself.
     */
    @Test
    void neutralConquestGroundStaysUnclaimed() {
        MapTerritory territory = DynmapApi.parseForTest("14", NEUTRAL, "territory_neutral");

        assertTrue(territory.neutral());
        assertNull(territory.owner());
        assertEquals("The Jade Highlands", territory.areaName());
    }

    @Test
    void conquestPointsComeOffTheCard() {
        assertEquals(80, DynmapApi.parseForTest("1", HELD, "territory_owned").conquestPoints());
        assertEquals(36, DynmapApi.parseForTest("14", NEUTRAL, "territory_neutral")
                .conquestPoints());
    }

    /** Absent, not zero: only Rivina publishes this, and the screen has to tell the difference. */
    @Test
    void aContinentWithoutPointsReportsThemMissing() {
        MapTerritory balak = DynmapApi.parseForTest("16", BALAK_NEUTRAL, "territory_neutral");
        assertFalse(balak.hasConquestPoints());
        assertEquals(-1, balak.conquestPoints());

        MapTerritory kalros = DynmapApi.parseForTest("129",
                "<h2>Falcon Fury Territory<br/><small>Owner: Corvus</small><small><br/>"
                        + "Cherry Grove 129<br/><br/></small></h2>", "territory_owned");
        assertFalse(kalros.hasConquestPoints());
    }

    /** The regular continents still name the town, not the alliance in the heading. */
    @Test
    void theRegularFormatStillPrefersTheOwnerLine() {
        MapTerritory territory = DynmapApi.parseForTest("129",
                "<h2>Falcon Fury Territory<br/><small>Owner: Corvus</small><small><br/>"
                        + "Cherry Grove 129<br/><br/></small></h2>", "territory_owned");

        assertEquals("Corvus", territory.owner());
        assertEquals("Falcon Fury", territory.alliance());
    }
}
