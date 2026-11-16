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
    void severalServersCanBeListed() {
        BotConfig config = new BotConfig();
        assertEquals(java.util.List.of(), config.guildIds(), "none: register globally");
        config.guildId = " 111, 222 ;333  111 ";
        assertEquals(java.util.List.of("111", "222", "333"), config.guildIds());
    }

    @Test
    void alertsGoToTheMainPlaceAndEveryExtraOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bot.json");
        java.nio.file.Files.writeString(file, """
                {"botToken": "token", "channelId": "10", "roleId": "1",
                 "alsoPostTo": [
                   {"channelId": "20", "roleId": "2"},
                   {"webhookUrl": "https://discord.com/api/webhooks/3/x"},
                   {"roleId": "4"}
                 ]}
                """);
        BotConfig config = BotConfig.load(file);
        assertTrue(config.unknownKeys().isEmpty(), "alsoPostTo is a known setting");
        java.util.List<BotConfig.Target> targets = config.targets();
        assertEquals(3, targets.size(), "an entry with nowhere to post is left out");
        assertEquals(new BotConfig.Target("", "10", "1"), targets.get(0));
        assertEquals(new BotConfig.Target("", "20", "2"), targets.get(1));
        assertTrue(targets.get(2).usesWebhook());
        assertEquals("", targets.get(2).roleId());
        assertEquals("<#20>", targets.get(1).describe());
        assertEquals("webhook", targets.get(2).describe());
    }

    @Test
    void anExtraChannelNeedsTheBotToken() {
        BotConfig config = new BotConfig();
        config.webhookUrl = "https://discord.com/api/webhooks/1/abc";
        BotConfig.AlertTarget extra = new BotConfig.AlertTarget();
        extra.channelId = "20";
        config.alsoPostTo.add(extra);
        assertEquals(1, config.targets().size(), "a channel cannot be posted to through a webhook");
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
