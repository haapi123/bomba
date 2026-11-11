package com.betterloka.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot as the assistant server starts it: never fatal, files in the server's folder. */
class EmbeddedBotTest {
    @Test
    void aMissingConfigIsWrittenAndTheWatchStaysOffWithoutStoppingAnything(@TempDir Path dir) {
        BotConfig config = BetterLokaBot.loadEmbeddedConfig(dir);
        assertTrue(Files.exists(dir.resolve(BetterLokaBot.CONFIG_NAME)), "there is a file to fill in");
        assertEquals(dir.resolve("betterloka-bot-state.json").toString(), config.stateFile,
                "the state file lives next to the config, wherever the process was started");

        BetterLokaBot.Embedded bot = BetterLokaBot.startEmbedded(config, interaction -> false);
        try {
            assertFalse(bot.watching(), "nowhere to post: the watch is off, and the process carries on");
            assertFalse(bot.connected(), "no bot token: no gateway");
        } finally {
            bot.stop();
        }
    }
}
