package com.betterloka.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * The channel to post in. Only used with {@link #botToken}.
     *
     * <p>A webhook carries its channel in the URL and cannot be pointed at another one, so this is
     * ignored entirely when {@link #webhookUrl} is set.
     */
    public String channelId = "";

    /**
     * The server, or servers, to register the commands in: one id, or several separated by commas.
     *
     * <p>Optional. With it the commands appear in those servers straight away; without it they are
     * registered globally, which works everywhere the bot is but can take Discord up to an hour to
     * publish. Right-click the server with Developer Mode on to copy the id.
     */
    public String guildId = "";

    /**
     * Whether to connect to Discord's gateway and serve the commands ({@code /check}, {@code /test},
     * {@code /verification}, {@code /suggestions}).
     *
     * <p>Needs {@link #botToken}: a webhook can only speak, and a slash command has to be listened
     * for. The fallen-town watch runs either way.
     */
    public boolean enableCommands = true;

    /**
     * The role to ping. Just the numeric id — right-click the role with Developer Mode on.
     * Leave empty to post without pinging anyone.
     */
    public String roleId = "";

    /**
     * Seconds between checks. Thirty by default: getting to a fallen town first is the whole point,
     * and a check costs about a kilobyte because it only asks whether Loka's deleted-town count has
     * moved. The megabyte-sized sweep behind it runs only when it has.
     */
    public int checkIntervalSeconds = 30;

    /**
     * Minutes between full sweeps regardless of the count, as a backstop.
     *
     * <p>The count is a reliable signal for a town being deleted, but it would hide the rare case of
     * one town being deleted while an old record is purged in the same window. This bounds how long
     * that could go unnoticed without giving up the cheap check.
     *
     * <p>An hour, because this is the expensive half: a sweep is about a megabyte and the thirty
     * second check is a kilobyte, so the backstop is what actually decides the bot's bandwidth.
     */
    public int fullSweepIntervalMinutes = 60;

    /**
     * Whether to announce the towns that had already fallen before the bot's first run.
     *
     * <p>Off by default, and deliberately: there is a standing backlog of a dozen or more fallen
     * towns at any time, and pinging a role with all of them the first time somebody starts the bot
     * would be a very bad introduction to it. The first sweep records them silently, and everything
     * after that is news.
     */
    public boolean announceBacklogOnFirstRun = false;

    /**
     * Post a test message on startup, then carry on watching.
     *
     * <p>For panels where the startup command is awkward or locked to edit: the config file is
     * reachable in any file manager, so the setup can be confirmed without needing to pass
     * {@code --test} on the command line and then take it off again.
     */
    public boolean testOnStart = false;

    /**
     * How many town members {@code /check} looks up.
     *
     * <p>The owner and sub-owners are always checked on top of this, so raising or lowering it never
     * costs you the people who actually decide whether a town survives.
     *
     * <p>Set to {@code 0} for no limit. Be deliberate about that: Loka's largest town has over
     * twelve hundred members, each one is three requests and a 30 KB page, and a report that size is
     * forty megabytes off EldritchBot and long enough that Discord's fifteen-minute reply window can
     * run out before it finishes.
     */
    public int maxMembersChecked = TownReport.DEFAULT_MAX_MEMBERS;

    /** Where the bot remembers what it has already announced. */
    public String stateFile = "betterloka-bot-state.json";

    /**
     * More places to post the fallen-town alerts, besides the main one above — another channel, or a
     * channel on another Discord server, each with the role to ping there (a role belongs to one
     * server, so the main {@link #roleId} cannot be pinged anywhere else). A channel needs
     * {@link #botToken} and the bot in that server; a webhook needs nothing else.
     */
    public List<AlertTarget> alsoPostTo = new ArrayList<>();

    /**
     * The Minecraft server the stat channels' player count comes from, as {@code host} or
     * {@code host:port}. Asked the way the game's server list asks; nothing logs in.
     */
    public String statsServerAddress = "play.lokamc.com";

    /**
     * How often the stat channels ({@code /stats setup}) are updated, in minutes. Discord lets a
     * channel be renamed only twice in ten minutes, so anything under ten is taken as ten.
     */
    public int statsIntervalMinutes = 10;

    /** The time zone "this month" is counted in, for the month's most kills and damage. */
    public String statsTimeZone = "Europe/Warsaw";

    /** Count Rivina's battles in the month's kills and damage too. Off: conquest only, as the mod. */
    public boolean statsCountRivina = false;

    /**
     * When the server does not answer the bot directly (a firewall in front of the bot's host),
     * ask the public status service {@code api.mcsrvstat.us} for the player count instead.
     */
    public boolean statsStatusFallback = true;

    /** One more place for the alerts. */
    public static final class AlertTarget {
        public String webhookUrl = "";
        public String channelId = "";
        public String roleId = "";
    }

    /**
     * Where an alert goes and whom it pings.
     *
     * @param webhookUrl the webhook, or blank to post as the bot in {@code channelId}
     */
    public record Target(String webhookUrl, String channelId, String roleId) {
        public boolean usesWebhook() {
            return webhookUrl != null && !webhookUrl.isBlank();
        }

        /**
         * A name for this place that can be written to the state file: the channel's id, or for a
         * webhook a hash of its address — the address itself is a secret.
         */
        public String key() {
            if (!usesWebhook()) {
                return "channel:" + channelId;
            }
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(webhookUrl.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return "webhook:" + java.util.HexFormat.of().formatHex(digest, 0, 8);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** As Discord shows it in a message: the channel's mention, or just "webhook". */
        public String describe() {
            return usesWebhook() ? "webhook" : "<#" + channelId + ">";
        }
    }

    /**
     * Keys found in the config file that this bot does not know.
     *
     * <p>Gson drops an unrecognised key without a word, so {@code "webhook"} where {@code
     * "webhookUrl"} was meant reads exactly like an empty setting — which is a long way to look for a
     * typo on a hosting panel with nothing but a log to go on.
     */
    private transient final List<String> unknownKeys = new ArrayList<>();

    public static BotConfig load(Path file) {
        BotConfig config = new BotConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                BotConfig loaded = GSON.fromJson(json, BotConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
                config.unknownKeys.addAll(unknownKeysIn(json));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
            }
        }
        config.applyEnvironment();
        return config;
    }

    private static List<String> unknownKeysIn(JsonObject json) {
        Set<String> known = new HashSet<>();
        for (Field field : BotConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
                known.add(field.getName());
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String key : json.keySet()) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    public List<String> unknownKeys() {
        return unknownKeys;
    }

    /**
     * What the bot actually ended up with, for when it cannot start.
     *
     * <p>Secrets are reported as set or empty and never printed: this goes in a log that gets pasted
     * into chat windows, which is how the last webhook had to be thrown away.
     */
    public List<String> describeSettings() {
        List<String> lines = new ArrayList<>();
        lines.add("  webhookUrl: " + state(webhookUrl));
        lines.add("  botToken:   " + state(botToken));
        lines.add("  channelId:  " + state(channelId));
        lines.add("  roleId:     " + state(roleId));
        lines.add("  guildId:    " + state(guildId));
        lines.add("  alsoPostTo: " + (alsoPostTo == null ? 0 : alsoPostTo.size()) + " more place(s)");
        return lines;
    }

    private static String state(String value) {
        if (value == null || value.isBlank()) {
            return "empty";
        }
        return "set (" + value.length() + " characters)";
    }

    /** Environment wins over the file, so a host's secret store beats a checked-in default. */
    private void applyEnvironment() {
        webhookUrl = envOr("BETTERLOKA_WEBHOOK_URL", webhookUrl);
        botToken = envOr("BETTERLOKA_BOT_TOKEN", botToken);
        channelId = envOr("BETTERLOKA_CHANNEL_ID", channelId);
        guildId = envOr("BETTERLOKA_GUILD_ID", guildId);
        roleId = envOr("BETTERLOKA_ROLE_ID", roleId);
        stateFile = envOr("BETTERLOKA_STATE_FILE", stateFile);

        maxMembersChecked = envInt("BETTERLOKA_MAX_MEMBERS", maxMembersChecked);
        checkIntervalSeconds = envInt("BETTERLOKA_CHECK_SECONDS", checkIntervalSeconds);
        fullSweepIntervalMinutes = envInt("BETTERLOKA_FULL_SWEEP_MINUTES", fullSweepIntervalMinutes);
        announceBacklogOnFirstRun =
                envBool("BETTERLOKA_ANNOUNCE_BACKLOG", announceBacklogOnFirstRun);
        testOnStart = envBool("BETTERLOKA_TEST_ON_START", testOnStart);
        enableCommands = envBool("BETTERLOKA_ENABLE_COMMANDS", enableCommands);
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int envInt(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // Keep the configured value rather than refusing to start over a typo.
            return fallback;
        }
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

    /**
     * Every place the alerts go: the main one first, then {@link #alsoPostTo}. An extra place with
     * neither a webhook nor (with a bot token) a channel is left out.
     */
    public List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        if (usesWebhook()) {
            targets.add(new Target(webhookUrl.trim(), "", roleId.trim()));
        } else if (usesBotToken()) {
            targets.add(new Target("", channelId.trim(), roleId.trim()));
        }
        if (alsoPostTo != null) {
            for (AlertTarget extra : alsoPostTo) {
                if (extra == null) {
                    continue;
                }
                String webhook = extra.webhookUrl == null ? "" : extra.webhookUrl.trim();
                String channel = extra.channelId == null ? "" : extra.channelId.trim();
                String role = extra.roleId == null ? "" : extra.roleId.trim();
                if (!webhook.isEmpty()) {
                    targets.add(new Target(webhook, "", role));
                } else if (!channel.isEmpty() && !botToken.isBlank()) {
                    targets.add(new Target("", channel, role));
                }
            }
        }
        return targets;
    }

    /** The servers in {@link #guildId}, in order, without blanks or repeats. */
    public List<String> guildIds() {
        List<String> ids = new ArrayList<>();
        if (guildId == null) {
            return ids;
        }
        for (String part : guildId.split("[,;\\s]+")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    public boolean usesBotToken() {
        return !botToken.isBlank() && !channelId.isBlank();
    }

    /** Slash commands need a token; the channel is not involved, since a reply goes where asked. */
    public boolean servesCommands() {
        return enableCommands && !botToken.isBlank();
    }

    /** @return what is wrong with this configuration, or {@code null} if it can run. */
    public String validate() {
        if (!usesWebhook() && !usesBotToken()) {
            return "Set either webhookUrl, or botToken together with channelId.";
        }
        if (checkIntervalSeconds < MINIMUM_CHECK_SECONDS) {
            return "checkIntervalSeconds must be at least " + MINIMUM_CHECK_SECONDS + ".";
        }
        return null;
    }

    /** A floor, so a typo cannot turn the watch into a hammer on somebody else's server. */
    static final int MINIMUM_CHECK_SECONDS = 10;

    public int intervalSeconds() {
        return Math.max(MINIMUM_CHECK_SECONDS, checkIntervalSeconds);
    }

    public long fullSweepIntervalMillis() {
        return Math.max(1, fullSweepIntervalMinutes) * 60_000L;
    }

    /** Discord's limit is two renames per channel per ten minutes. */
    static final int MINIMUM_STATS_MINUTES = 10;

    public int statsIntervalMinutes() {
        return Math.max(MINIMUM_STATS_MINUTES, statsIntervalMinutes);
    }

    /** {@link #statsTimeZone}, or the machine's own if it is not one Java knows. */
    public java.time.ZoneId statsZone() {
        try {
            return java.time.ZoneId.of(statsTimeZone == null ? "" : statsTimeZone.trim());
        } catch (RuntimeException e) {
            return java.time.ZoneId.systemDefault();
        }
    }
}
