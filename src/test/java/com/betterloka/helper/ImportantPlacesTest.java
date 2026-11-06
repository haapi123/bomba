package com.betterloka.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImportantPlacesTest {
    @Test
    void aContinentIsKnownByItsWorldName() {
        assertEquals(ImportantPlaces.Region.KALROS, ImportantPlaces.detect("north", false, false, 0, 0));
        assertEquals(ImportantPlaces.Region.BALAK, ImportantPlaces.detect("bigboi", false, false, 0, 0));
        assertEquals(ImportantPlaces.Region.RIVINA, ImportantPlaces.detect("lilboi", false, false, 0, 0));
        assertEquals(ImportantPlaces.Region.GARAMA, ImportantPlaces.detect("Garama", false, false, 0, 0));
    }

    @Test
    void otherwiseByWhereItsWorldSpawnIs() {
        // Each continent's spawn is its docks; a server calling every world "overworld" still tells
        // them apart this way.
        assertEquals(ImportantPlaces.Region.KALROS, ImportantPlaces.detect("overworld", false, true, 3727, 4205));
        assertEquals(ImportantPlaces.Region.ASCALON, ImportantPlaces.detect("overworld", false, true, 5720, 5253));
        assertEquals(ImportantPlaces.Region.GARAMA, ImportantPlaces.detect("overworld", false, true, 4386, 2061));
        assertEquals(ImportantPlaces.Region.BALAK, ImportantPlaces.detect("overworld", false, true, 2300, 3012));
        assertEquals(ImportantPlaces.Region.RIVINA, ImportantPlaces.detect("overworld", false, true, 2385, 1041));
        assertEquals(ImportantPlaces.Region.ALADRA, ImportantPlaces.detect("overworld", false, true, -150, 20));
    }

    @Test
    void nowhereKnownIsNoContinent() {
        assertNull(ImportantPlaces.detect("overworld", false, true, 12000, -9000));
        assertNull(ImportantPlaces.detect("overworld", false, false, 0, 0));
        assertNull(ImportantPlaces.detect("north", true, true, 3727, 4205), "never in the Nether");
    }

    @Test
    void everyPlaceIsOnItsOwnContinent() {
        for (ImportantPlaces.Place place : ImportantPlaces.ALL) {
            if (place.region() == ImportantPlaces.Region.ALADRA) {
                assertEquals(ImportantPlaces.Region.ALADRA,
                        ImportantPlaces.detect("overworld", false, true, place.x(), place.z()), place.id());
            }
        }
        for (ImportantPlaces.Region region : ImportantPlaces.Region.values()) {
            assertFalse(ImportantPlaces.on(region).isEmpty(), region + " has places");
        }
    }
}
