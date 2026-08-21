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
        template.checkIntervalSeconds = 45;
        template.writeTemplate(file);

        BotConfig loaded = BotConfig.load(file);
        assertEquals("999", loaded.roleId);
        assertEquals(45, loaded.intervalSeconds());
        assertTrue(loaded.validate() != null, "a template still has no destination filled in");
    }

    @Test
    void watchesEveryThirtySecondsByDefault() {
        assertEquals(30, new BotConfig().intervalSeconds(),
                "getting to a fallen town first is the point, and a check costs about a kilobyte");
    }

    @Test
    void refusesToPollFasterThanTheFloor() {
        BotConfig config = new BotConfig();
        config.checkIntervalSeconds = 0;
        assertEquals(BotConfig.MINIMUM_CHECK_SECONDS, config.intervalSeconds(),
                "zero would hammer somebody else's server in a tight loop");
        config.checkIntervalSeconds = -5;
        assertEquals(BotConfig.MINIMUM_CHECK_SECONDS, config.intervalSeconds());

        config.webhookUrl = "https://discord.com/api/webhooks/1/abc";
        config.checkIntervalSeconds = 1;
        assertNotNull(config.validate(), "and it says so rather than silently correcting the file");
    }
}
