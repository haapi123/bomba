package com.betterloka.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSearchTest {
    private static MapTerritory hex(String number, String area, String owner, boolean seat, double x, double z) {
        return new MapTerritory(number, area, owner, null, null,
                new double[] {x, x + 100, x + 100, x},
                new double[] {z, z, z + 100, z + 100},
                x + 50, z + 50, 0x3C44AA, 0x09090B, "territory_owned", -1, seat, null,
                0.65, 1.0, 1);
    }

    private static final MapTerritory FOREST_6 = hex("6", "Forest", null, false, 0, 0);
    private static final MapTerritory FOREST_65 = hex("65", "Forest", "Proxima", false, 100, 0);
    private static final MapTerritory FOREST_12 = hex("12", "Forest", "Proxima", true, 200, 0);
    private static final MapTerritory BIRCH_90 = hex("90", "Birch Forest", "Matrix", false, 300, 0);
    private static final MapTerritory MANGROVE_93 = hex("93", "Mangrove Forest", null, false, 400, 0);
    private static final MapTerritory NAMELESS_140 = hex("140", null, "Matrix", false, 500, 0);

    private static final MapTown PROXIMA = new MapTown("Proxima", null, null, 90, 20, 2, 250, 50);
    private static final MapTown FEARLESS = new MapTown("Fearless", null, null, 100, 3, 0, 6302, 3326);

    private static MapSearch search() {
        return MapSearch.of(List.of(FOREST_6, FOREST_65, FOREST_12, BIRCH_90, MANGROVE_93, NAMELESS_140),
                List.of(PROXIMA, FEARLESS));
    }

    @Test
    void theLabelAsLokaWritesItFindsThatHex() {
        MapSearch.Hit hit = search().find("Forest 65");
        assertEquals(MapSearch.Kind.TERRITORY, hit.kind());
        assertEquals(FOREST_65, hit.focus());
        assertEquals(List.of(FOREST_65), hit.territories());
        assertEquals(FOREST_65.centerX(), hit.x());
        assertEquals(FOREST_65.centerZ(), hit.z());
    }

    @Test
    void caseSpacingAndPunctuationDoNotMatter() {
        for (String query : List.of("forest 65", "FOREST65", "  forest   65 ", "Forest-65", "forest #65")) {
            assertEquals(FOREST_65, search().find(query).focus(), query);
        }
    }

    @Test
    void theNumberAloneFindsTheHex() {
        assertEquals(FOREST_65, search().find("65").focus());
        assertEquals(FOREST_65, search().find("#65").focus());
        assertEquals(NAMELESS_140, search().find("140").focus(), "a hex with no area name is #140");
    }

    @Test
    void aNumberGoesToThatNumberBeforeOneThatMerelyStartsWithIt() {
        assertEquals(FOREST_6, search().find("forest 6").focus());
        MapSearch withoutSix = MapSearch.of(List.of(FOREST_65, FOREST_12), List.of());
        assertEquals(FOREST_65, withoutSix.find("forest 6").focus(),
                "with no Forest 6, the start of a number is still a match");
    }

    @Test
    void anAreaAloneGoesToItsOwnHexesBeforeLongerNames() {
        // Forest 6, 12 and 65 all match; Birch Forest and Mangrove Forest have a word left over.
        assertEquals(FOREST_6, search().find("forest").focus(), "the lowest number of the closest name");
        List<MapSearch.Hit> hits = search().find("forest", 10);
        assertEquals(List.of("Forest 6", "Forest 12", "Forest 65"),
                hits.subList(0, 3).stream().map(MapSearch.Hit::name).toList());
    }

    @Test
    void wordsCanBeShortenedAndOtherWordsSkipped() {
        assertEquals(MANGROVE_93, search().find("mangrove 93").focus());
        assertEquals(MANGROVE_93, search().find("mang 93").focus());
        assertEquals(BIRCH_90, search().find("birch").focus());
    }

    @Test
    void aTownLightsUpEverythingItHoldsAndCentresOnItsSeat() {
        MapSearch.Hit hit = search().find("proxima");
        assertEquals(MapSearch.Kind.TOWN, hit.kind());
        assertEquals("Proxima", hit.name());
        assertEquals(FOREST_12, hit.focus(), "Forest 12 is marked as the seat");
        assertEquals(2, hit.territories().size());
        assertTrue(hit.territories().containsAll(List.of(FOREST_65, FOREST_12)));
    }

    @Test
    void theStartOfATownsNameIsEnough() {
        assertEquals("Proxima", search().find("prox").name());
    }

    @Test
    void aTownKnownOnlyFromWhatItHoldsIsStillFound() {
        MapSearch.Hit hit = search().find("Matrix");
        assertEquals(MapSearch.Kind.TOWN, hit.kind());
        assertEquals(2, hit.territories().size());
        assertNotNull(hit.focus());
    }

    @Test
    void aTownWithNoGroundIsFoundAtItsMarker() {
        MapSearch.Hit hit = search().find("Fearless");
        assertEquals(MapSearch.Kind.TOWN, hit.kind());
        assertNull(hit.focus());
        assertTrue(hit.territories().isEmpty());
        assertEquals(6302, hit.x());
        assertEquals(3326, hit.z());
    }

    @Test
    void theSeatFallsBackToTheHexUnderTheTownsMarker() {
        MapTerritory a = hex("1", "Plains", "Estra", false, 0, 0);
        MapTerritory b = hex("2", "Plains", "Estra", false, 1000, 0);
        MapTown estra = new MapTown("Estra", null, null, 50, 5, 2, 1050, 50);
        assertEquals(b, MapSearch.of(List.of(a, b), List.of(estra)).find("estra").focus());
    }

    @Test
    void aPieceFromInsideAWordStillMatches() {
        assertEquals(MANGROVE_93, search().find("grove").focus());
    }

    @Test
    void nothingAndNonsenseFindNothing() {
        assertNull(search().find(""));
        assertNull(search().find("   "));
        assertNull(search().find("#"));
        assertNull(search().find(null));
        assertNull(search().find("Desert 999"));
        assertNull(search().find("forest 999"), "a number that matches no hex is not ignored");
    }

    @Test
    void accentsAreIgnored() {
        assertEquals("Proxima", search().find("Próxima").name());
        assertEquals(MapSearch.key("Łódź"), "lodz");
    }
}
