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
}
