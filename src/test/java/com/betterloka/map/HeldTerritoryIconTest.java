package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hex's icon says what is happening on it, not who owns it.
 *
 * <p>Holders used to be read only off the {@code territory_owned} icon, which hid every held hex
 * that had something else to show. On Balak that was five of the seven claimed hexes — four
 * carrying a buff and one under attack — so most of the continent's claimed ground drew as neutral,
 * with five more mutator hexes on Ascalon.
 *
 * <p>Every label here is verbatim from {@code marker_bigboi.json} or {@code marker_west.json}.
 */
class HeldTerritoryIconTest {
    /** Loka's own opacity for held ground; a town's own hex is 0.92 and neutral 0.5 or less. */
    private static final double HELD = 0.65;
    private static final double NEUTRAL = 0.0;

    private static final String BUFF =
            "<h2>JachowskiMC Alliance Territory<small><br/>Ashlands 7<br/></small></h2><br/>"
                    + "<he2>Any Armor, Weapon, or Tool that breaks, rather than being lost,<br/>"
                    + " will instead be sent to your Escrow with 1 durability.</h2><br/>";

    private static final String UNDER_ATTACK =
            "<h2>Bamboo Legacy Territory<small><br/>Badaladaka 1<br/>"
                    + "<h5>🗡 Under attack by JachowskiMC Alliance</h5><br/></small></h2><br/>";

    private static final String NEUTRAL_WITH_BUFF =
            "<h2>Desolate Coast - Neutral<small><br/>Desolate Coast 14<br/></small></h2><br/>"
                    + "<he2>Your industries will now generate <br/>Nether Loot</h2><br/>";

    @Test
    void aBuffedTerritoryIsStillHeld() {
        MapTerritory territory = DynmapApi.parseForTest("7", BUFF, "territory_buff", HELD);

        assertFalse(territory.neutral(), "four of Balak's seven held hexes carry this icon");
        assertEquals("JachowskiMC Alliance", territory.owner());
        assertEquals("Ashlands", territory.areaName());
    }

    @Test
    void aTerritoryUnderAttackIsStillHeldByItsDefender() {
        MapTerritory territory = DynmapApi.parseForTest("1", UNDER_ATTACK, "territory_attack", HELD);

        assertFalse(territory.neutral());
        assertEquals("Bamboo Legacy", territory.owner(),
                "the side being attacked is the side that holds it");
    }

    @Test
    void aMutatedTerritoryIsStillHeld() {
        String mutator = "<h2>Helian League Territory<small><br/>Ashlands 42<br/></small></h2><br/>";
        MapTerritory territory = DynmapApi.parseForTest("42", mutator, "territory_mutator", HELD);

        assertFalse(territory.neutral(), "five of Ascalon's held hexes carry this icon");
        assertEquals("Helian League", territory.owner());
    }

    /**
     * The other half of the rule: neutral ground carries buff icons too, and its heading is the
     * region's name. Reading the heading without checking how Loka draws the hex would turn
     * "Desolate Coast" into an alliance.
     */
    @Test
    void neutralGroundWithABuffStaysNeutral() {
        MapTerritory territory =
                DynmapApi.parseForTest("14", NEUTRAL_WITH_BUFF, "territory_buff", NEUTRAL);

        assertTrue(territory.neutral(), "a neutral hex with a buff is still nobody's");
    }

    /** An icon nobody has seen before must not hide a hex that Loka draws as held. */
    @Test
    void anUnknownIconDoesNotHideAHolder() {
        MapTerritory territory = DynmapApi.parseForTest("7", BUFF, "territory_something_new", HELD);

        assertFalse(territory.neutral());
        assertEquals("JachowskiMC Alliance", territory.owner());
    }
}
