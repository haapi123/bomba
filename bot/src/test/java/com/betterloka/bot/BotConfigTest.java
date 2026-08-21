package com.betterloka.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bot that starts with nowhere to post is worse than one that refuses to start. */
class BotConfigTest {

    @Test
    void refusesToRunWithNoDestination() {
        assertNotNull(new BotConfig().validate(), "no webhook and no token is not runnable");
    }

    @Test
    void acceptsAWebhook() {
        BotConfig config = new BotConfig();
        config.webhookUrl = "https://discord.com/api/webhooks/1/abc";
        assertNull(config.validate());
        assertTrue(config.usesWebhook());
    }

    @Test
    void acceptsATokenOnlyWithAChannel() {
        BotConfig config = new BotConfig();
        config.botToken = "token";
        assertNotNull(config.validate(), "a token with nowhere to post it is a mistake worth naming");

        config.channelId = "123";
        assertNull(config.validate());
        assertTrue(config.usesBotToken());
    }

    @Test
    void writesAndReadsBackAStarterFile(@TempDir Path dir) {
        Path file = dir.resolve("betterloka-bot.json");
        BotConfig template = new BotConfig();
        template.roleId = "999";
        template.checkIntervalMinutes = 7;
        template.writeTemplate(file);

        BotConfig loaded = BotConfig.load(file);
        assertEquals("999", loaded.roleId);
        assertEquals(7, loaded.intervalMinutes());
        assertTrue(loaded.validate() != null, "a template still has no destination filled in");
    }

    @Test
    void neverSweepsFasterThanOnceAMinute() {
        BotConfig config = new BotConfig();
        config.checkIntervalMinutes = 0;
        assertEquals(1, config.intervalMinutes(), "zero would hammer Loka in a tight loop");
        config.checkIntervalMinutes = -5;
        assertEquals(1, config.intervalMinutes());
    }
}
