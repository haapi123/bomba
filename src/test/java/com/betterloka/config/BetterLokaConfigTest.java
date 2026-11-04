package com.betterloka.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BetterLokaConfigTest {
    @Test
    void aFreshConfigStartsTheShrineTimerAtTwentyMinutes(@TempDir Path dir) {
        assertEquals(20, BetterLokaConfig.load(dir.resolve("config.json")).shrineCooldownMinutes());
    }

    @Test
    void theOldFifteenMinuteDefaultIsMovedToTwentyOnce(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        // What 0.35 wrote: the old default, and no record of having been moved.
        Files.writeString(file, "{\"shrineCooldownMinutes\": 15}");
        assertEquals(20, BetterLokaConfig.load(file).shrineCooldownMinutes());
        assertEquals(20, BetterLokaConfig.load(file).shrineCooldownMinutes(), "and the move is saved");

        // Set back to fifteen afterwards, it is the player's own choice and stays.
        BetterLokaConfig config = BetterLokaConfig.load(file);
        config.setShrineCooldownMinutes(15);
        assertEquals(15, BetterLokaConfig.load(file).shrineCooldownMinutes());
    }

    @Test
    void aTimerThePlayerChoseIsLeftAlone(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"shrineCooldownMinutes\": 25}");
        assertEquals(25, BetterLokaConfig.load(file).shrineCooldownMinutes());
    }

    @Test
    void coordinatesStartOff(@TempDir Path dir) {
        BetterLokaConfig config = BetterLokaConfig.load(dir.resolve("config.json"));
        assertEquals(false, config.coordsHudEnabled());
        assertEquals(100, config.coordsHudScale());
    }

    @Test
    void theOldCoordinatesOnDefaultIsTurnedOffOnce(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        // What 0.39 wrote: coordinates on, as they started, and no size.
        Files.writeString(file, "{\"coordsHudEnabled\": true, \"shrineCooldownMigrated\": true}");
        BetterLokaConfig config = BetterLokaConfig.load(file);
        assertEquals(false, config.coordsHudEnabled());
        assertEquals(100, config.coordsHudScale(), "a config without a size reads as normal size");

        // Turned on afterwards, it is the player's own choice and stays on.
        config.setCoordsHudEnabled(true);
        assertEquals(true, BetterLokaConfig.load(file).coordsHudEnabled());
    }

    @Test
    void theCoordinatesSizeStaysInRange(@TempDir Path dir) {
        BetterLokaConfig config = BetterLokaConfig.load(dir.resolve("config.json"));
        config.setCoordsHudScale(20);
        assertEquals(BetterLokaConfig.COORDS_SCALE_MIN, config.coordsHudScale());
        config.setCoordsHudScale(400);
        assertEquals(BetterLokaConfig.COORDS_SCALE_MAX, config.coordsHudScale());
        config.setCoordsHudScale(70);
        assertEquals(70, BetterLokaConfig.load(dir.resolve("config.json")).coordsHudScale());
    }
}
