package com.betterloka.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the bot posts and how often it looks.
 *
 * <p>Read from a JSON file next to the jar, with every field overridable by an environment variable
 * so the bot can be deployed somewhere that keeps its secrets out of files.
 */
public final class BotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * A Discord webhook URL. The simplest way to post: made in Channel Settings → Integrations, no
     * bot application and no permissions to grant.
     */
    public String webhookUrl = "";

    /**
     * A bot token, as an alternative to the webhook. Needs {@link #channelId} as well, and the bot
     * has to be in the server with permission to post in that channel.
     */
    public String botToken = "";

    /** The channel to post in. Only used with {@link #botToken}. */
    public String channelId = "";

    /**
     * The role to ping. Just the numeric id — right-click the role with Developer Mode on.
     * Leave empty to post without pinging anyone.
     */
    public String roleId = "";

    /** Minutes between sweeps. A fallen town is worth knowing about quickly, so this is short. */
    public int checkIntervalMinutes = 5;

    /**
     * Whether to announce the towns that had already fallen before the bot's first run.
     *
     * <p>Off by default, and deliberately: there is a standing backlog of a dozen or more fallen
     * towns at any time, and pinging a role with all of them the first time somebody starts the bot
     * would be a very bad introduction to it. The first sweep records them silently, and everything
     * after that is news.
     */
    public boolean announceBacklogOnFirstRun = false;

    /** Where the bot remembers what it has already announced. */
    public String stateFile = "betterloka-bot-state.json";

    public static BotConfig load(Path file) {
        BotConfig config = new BotConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                BotConfig loaded = GSON.fromJson(reader, BotConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
            }
        }
        config.applyEnvironment();
        return config;
    }

    /** Environment wins over the file, so a host's secret store beats a checked-in default. */
    private void applyEnvironment() {
        webhookUrl = envOr("BETTERLOKA_WEBHOOK_URL", webhookUrl);
        botToken = envOr("BETTERLOKA_BOT_TOKEN", botToken);
        channelId = envOr("BETTERLOKA_CHANNEL_ID", channelId);
        roleId = envOr("BETTERLOKA_ROLE_ID", roleId);
        stateFile = envOr("BETTERLOKA_STATE_FILE", stateFile);

        String interval = System.getenv("BETTERLOKA_CHECK_MINUTES");
        if (interval != null && !interval.isBlank()) {
            try {
                checkIntervalMinutes = Integer.parseInt(interval.trim());
            } catch (NumberFormatException ignored) {
                // Leave the configured value rather than failing to start over a typo.
            }
        }
        String backlog = System.getenv("BETTERLOKA_ANNOUNCE_BACKLOG");
        if (backlog != null && !backlog.isBlank()) {
            announceBacklogOnFirstRun = Boolean.parseBoolean(backlog.trim());
        }
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Writes a commented starter file so a first run leaves something to fill in. */
    public void writeTemplate(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }

    public boolean usesWebhook() {
        return !webhookUrl.isBlank();
    }

    public boolean usesBotToken() {
        return !botToken.isBlank() && !channelId.isBlank();
    }

    /** @return what is wrong with this configuration, or {@code null} if it can run. */
    public String validate() {
        if (!usesWebhook() && !usesBotToken()) {
            return "Set either webhookUrl, or botToken together with channelId.";
        }
        if (checkIntervalMinutes < 1) {
            return "checkIntervalMinutes must be at least 1.";
        }
        return null;
    }

    public int intervalMinutes() {
        return Math.max(1, checkIntervalMinutes);
    }
}
