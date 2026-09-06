package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hex a town is built on.
 *
 * <p>Loka puts the town's own card on that hex in place of a territory card, so it carries no
 * "Owner:" line — and reading its absence as "nobody holds this" drew every town's seat on the
 * server as neutral. Every label here is verbatim from Loka's marker files.
 */
class TownSeatTest {
    /** Tyralnia's seat on Ascalon, the one this was reported on. */
    private static final String CAPITAL =
            "<h2><strong>Tyralnia - Capital of Ascalon</strong><br/><small>JachowskiMC Alliance"
                    + " - 460 strength<br/>131 members | 9 territories</small></h2>";

    /** An ordinary town's seat, on Kalros. */
    private static final String ORDINARY =
            "<h2>Vanguard<br/><small>JachowskiMC Alliance - 460 strength<br/>"
                    + "107 members | 0 territories</small></h2>";

    /** Garama's, which Loka draws with yet another icon. */
    private static final String WORLD_CAPITAL =
            "<h2><strong>New Lothlaan - World Capital of Loka</strong><br/><small>Bamboo Legacy"
                    + " - 416 strength<br/>281 members | 4 territories</small></h2>";

    /** One of Tyralnia's ordinary claims, for contrast. */
    private static final String CLAIM =
            "<h2>JachowskiMC Alliance Territory<br/><small>Owner: Tyralnia</small><small><br/>"
                    + "Forest 68<br/><br/><br/>JachowskiMC Alliance owns the Forest</small></h2>";

    @Test
    void aCapitalsHexIsHeldByThatTown() {
        MapTerritory seat = DynmapApi.parseForTest("146", CAPITAL, "cap");

        assertFalse(seat.neutral(), "Tyralnia's own hex was being drawn as unclaimed");
        assertEquals("Tyralnia", seat.owner());
        assertEquals("JachowskiMC Alliance", seat.alliance());
        assertTrue(seat.seat());
    }

    /** The name is wrapped in <strong>, which the old heading pattern could not get past. */
    @Test
    void aCapitalsTitleIsSeparatedFromItsName() {
        MapTown town = DynmapApi.parseTown(CAPITAL, 976, 4788);

        assertEquals("Tyralnia", town.name());
        assertEquals("Capital of Ascalon", town.title());
        assertTrue(town.hasTitle());

        MapTown world = DynmapApi.parseTown(WORLD_CAPITAL, 0, 0);
        assertEquals("New Lothlaan", world.name());
        assertEquals("World Capital of Loka", world.title());
    }

    @Test
    void anOrdinaryTownsSeatIsHeldTooAndHasNoTitle() {
        MapTerritory seat = DynmapApi.parseForTest("19", ORDINARY, "town3");

        assertEquals("Vanguard", seat.owner());
        assertTrue(seat.seat());
        assertFalse(DynmapApi.parseTown(ORDINARY, 0, 0).hasTitle());
    }

    /** A claim is held by the same town but is not the seat, and keeps its region's name. */
    @Test
    void anOrdinaryClaimIsNotTheSeat() {
        MapTerritory claim = DynmapApi.parseForTest("68", CLAIM, "territory_owned");

        assertEquals("Tyralnia", claim.owner());
        assertEquals("Forest", claim.areaName());
        assertFalse(claim.seat());
    }

    /**
     * Towns are recognised by their card, not by their icon.
     *
     * <p>Filtering on an icon name beginning "town" is what hid the capitals: Loka draws them with
     * "cap" and "worldcap", so all three on the server were invisible to the mod.
     */
    @Test
    void everyTownCardIsRecognisedWhateverItsIconIsCalled() {
        assertTrue(DynmapApi.looksLikeTownCard(CAPITAL));
        assertTrue(DynmapApi.looksLikeTownCard(ORDINARY));
        assertTrue(DynmapApi.looksLikeTownCard(WORLD_CAPITAL));
    }

    @Test
    void aTerritoryCardIsNotMistakenForATown() {
        assertFalse(DynmapApi.looksLikeTownCard(CLAIM));
        assertFalse(DynmapApi.looksLikeTownCard(
                "<h2>Ice Wastes - Neutral<small><br/>Ice Wastes 119<br/><br/></small></h2>"));
        assertNull(DynmapApi.parseForTest("119",
                "<h2>Ice Wastes - Neutral<small><br/>Ice Wastes 119<br/><br/></small></h2>",
                "territory_neutral").owner());
    }
}
