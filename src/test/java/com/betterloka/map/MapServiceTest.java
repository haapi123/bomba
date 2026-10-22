package com.betterloka.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapServiceTest {
    private static Waypoint at(String label, String world, double x, double z) {
        return new Waypoint(label, world, x, 64, z, 0x3AB3DA, null);
    }

    @Test
    void addingKeepsTheWaypointAndSavesIt(@TempDir Path dir) {
        MapService map = new MapService(dir.resolve("waypoints.json"));
        Waypoint stored = map.add(at("Forest 65", "west", 2049, 4415));
        assertEquals("Forest 65", stored.label());
        assertEquals(List.of(stored), map.waypoints());
        assertEquals(List.of(stored), new MapService(dir.resolve("waypoints.json")).waypoints(),
                "a waypoint must survive a restart");
    }

    @Test
    void aSecondMarkUnderTheSameNameIsNumberedRatherThanReplacingTheFirst(@TempDir Path dir) {
        MapService map = new MapService(dir.resolve("waypoints.json"));
        map.add(at("Forest 65", "west", 2049, 4415));
        Waypoint second = map.add(at("Forest 65", "west", 2100, 4400));
        Waypoint third = map.add(at("Forest 65", "west", 2000, 4400));
        assertEquals("Forest 65 (2)", second.label());
        assertEquals("Forest 65 (3)", third.label());
        assertEquals(3, map.waypoints().size());
    }

    @Test
    void theSameNameOnAnotherContinentIsNotAClash(@TempDir Path dir) {
        MapService map = new MapService(dir.resolve("waypoints.json"));
        map.add(at("Plains 3", "west", 0, 0));
        assertEquals("Plains 3", map.add(at("Plains 3", "north", 0, 0)).label());
    }

    @Test
    void deletingRemovesOnlyThatOne(@TempDir Path dir) {
        MapService map = new MapService(dir.resolve("waypoints.json"));
        Waypoint a = map.add(at("Forest 65", "west", 2049, 4415));
        Waypoint b = map.add(at("Forest 65", "west", 2100, 4400));
        map.remove(a);
        assertEquals(List.of(b), map.waypoints());
        assertFalse(map.isSet("west", "Forest 65"));
        assertTrue(map.isSet("west", "Forest 65 (2)"));
        assertEquals(List.of(b), new MapService(dir.resolve("waypoints.json")).waypoints());
    }

    @Test
    void theListGoesContinentByContinentInTheMapsOrder() {
        List<Waypoint> sorted = MapService.forListing(List.of(
                at("Somewhere", "elsewhere", 0, 0),
                at("Balak hex", "bigboi", 0, 0),
                at("Ascalon hex", "west", 0, 0),
                at("Kalros hex", "north", 0, 0)));
        assertEquals(List.of("Kalros hex", "Ascalon hex", "Balak hex", "Somewhere"),
                sorted.stream().map(Waypoint::label).toList());
    }

    @Test
    void namesSortWithNumbersCountedAsNumbers() {
        List<Waypoint> sorted = MapService.forListing(List.of(
                at("Forest 65", "west", 0, 0),
                at("forest 9", "west", 0, 0),
                at("Forest 100", "west", 0, 0),
                at("Birch Forest 90", "west", 0, 0)));
        assertEquals(List.of("Birch Forest 90", "forest 9", "Forest 65", "Forest 100"),
                sorted.stream().map(Waypoint::label).toList());
    }
}
