package com.betterloka.bot;

import com.betterloka.api.ApiException;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.Territory;
import com.betterloka.towns.FallenTowns;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Watches Loka for towns that have fallen and pings a Discord role when one does.
 *
 * <p>A town with no owner left still holds its territories until the server clears them, which is
 * the window this exists to announce: the message carries the continent, the territory number and
 * the beacon coordinates, so anybody reading it can get there.
 *
 * <p>Runs on its own, not inside the mod. The mod only runs while somebody's game is open, and if
 * every player's client posted, the channel would get one ping per player per town.
 */
public final class BetterLokaBot {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");

    private static final Path CONFIG_FILE = Path.of("betterloka-bot.json");

    /** Red, for a town that is gone. */
    private static final int EMBED_COLOR = 0xE0605C;

    public static void main(String[] args) throws Exception {
        boolean once = List.of(args).contains("--once");
        boolean list = List.of(args).contains("--list");
        boolean test = List.of(args).contains("--test");
        String check = valueAfter(args, "--check");

        // Runs the /check report on the console. No Discord involved, which makes it the way to
        // see what the command would answer without a bot application — and the way to check the
        // report itself when something looks wrong.
        if (check != null) {
            int limit = Files.exists(CONFIG_FILE)
                    ? BotConfig.load(CONFIG_FILE).maxMembersChecked
                    : TownReport.DEFAULT_MAX_MEMBERS;
            try (HttpTransport transport = new HttpTransport()) {
                printReport(new TownReport(new LokaApi(transport), new EldritchApi(transport), limit),
                        check);
            }
            return;
        }

        if (!Files.exists(CONFIG_FILE)) {
            new BotConfig().writeTemplate(CONFIG_FILE);
            LOG.info("Wrote a starter config to {} — fill in webhookUrl (or botToken and channelId)",
                    CONFIG_FILE.toAbsolutePath());
            LOG.info("Then set roleId to the role you want pinged, and start the bot again.");
            return;
        }

        BotConfig config = BotConfig.load(CONFIG_FILE);

        // A key Gson did not recognise is silently dropped, so a typo looks exactly like an empty
        // setting. Said before validation, because it is usually the reason validation fails.
        if (!config.unknownKeys().isEmpty()) {
            LOG.warn("{} has setting(s) this bot does not know, and they were ignored: {}",
                    CONFIG_FILE.toAbsolutePath(), String.join(", ", config.unknownKeys()));
            LOG.warn("Check the spelling: webhookUrl, botToken, channelId, roleId, guildId.");
        }

        String problem = config.validate();
        if (problem != null && !list) {
            LOG.error("{}", problem);
            LOG.error("Read from {}:", CONFIG_FILE.toAbsolutePath());
            for (String line : config.describeSettings()) {
                LOG.error("{}", line);
            }
            LOG.error("Fill one of those in, or set the matching BETTERLOKA_* environment variable.");
            System.exit(1);
            return;
        }

        // A webhook is bound to the channel it was created on, so channelId next to one does
        // nothing — and looks exactly like it should, which is worth saying out loud.
        if (config.usesWebhook() && !config.channelId.isBlank()) {
            LOG.warn("channelId is ignored: a webhook always posts to the channel it was created on. "
                    + "To post somewhere else, make a new webhook on that channel.");
        }

        try (HttpTransport transport = new HttpTransport()) {
            LokaApi api = new LokaApi(transport);
            BotState state = new BotState(Path.of(config.stateFile));
            state.load();
            FallenTownWatcher watcher = new FallenTownWatcher(api, state);
            DiscordPoster poster = new DiscordPoster(config);

            if (list) {
                for (FallenTowns.Fallen fallen : watcher.current()) {
                    LOG.info("{} — {} territories: {}", fallen.town().name(),
                            fallen.territories().size(), fallen.numbers());
                }
                return;
            }

            if (test) {
                postTestMessage(poster, config);
                return;
            }
            if (config.testOnStart) {
                // Set in the config rather than on the command line, for panels where the startup
                // command is awkward to edit. It does not stop the watch.
                postTestMessage(poster, config);
            }

            BotStatus status = new BotStatus();
            DiscordGateway gateway = startCommands(config, api, transport, null, poster, status);
            watch(watcher, state, poster, config, once, status);
            if (gateway != null) {
                gateway.stop();
            }
        }
    }

    /**
     * The bot, running inside another program: the Loka Assistant server starts it this way, so a
     * game panel that runs one program per server can host both. Same config file, same state file,
     * same behaviour, with two differences: a missing or half-filled config turns the town watch off
     * with a line saying why instead of stopping the whole process, and the host can answer
     * interactions of its own over the same Discord connection (Discord delivers each interaction to
     * one connection, so two programs with the same token would steal each other's clicks).
     */
    public static final class Embedded {
        private final BotConfig config;
        private final HttpTransport transport;
        private final DiscordGateway gateway;
        private final Thread watch;

        private Embedded(BotConfig config, HttpTransport transport, DiscordGateway gateway, Thread watch) {
            this.config = config;
            this.transport = transport;
            this.gateway = gateway;
            this.watch = watch;
        }

        public BotConfig config() {
            return config;
        }

        public boolean watching() {
            return watch != null;
        }

        public boolean connected() {
            return gateway != null;
        }

        public void stop() {
            if (watch != null) {
                watch.interrupt();
            }
            if (gateway != null) {
                gateway.stop();
            }
            if (transport != null) {
                transport.close();
            }
        }
    }

    /** The config file's name, in the folder the bot runs in. */
    public static final String CONFIG_NAME = "betterloka-bot.json";

    /**
     * Reads {@code betterloka-bot.json} from {@code dir}, writing a template first if there is none,
     * so the owner of a combined server has the file to fill in.
     */
    public static BotConfig loadEmbeddedConfig(Path dir) {
        Path file = dir.resolve(CONFIG_NAME);
        if (!Files.exists(file)) {
            new BotConfig().writeTemplate(file);
            LOG.info("Wrote {}: fill in webhookUrl (or botToken and channelId) and roleId to turn on "
                    + "fallen-town alerts", file.toAbsolutePath());
        }
        BotConfig config = BotConfig.load(file);
        if (!config.unknownKeys().isEmpty()) {
            LOG.warn("{} has setting(s) this bot does not know, and they were ignored: {}",
                    file.toAbsolutePath(), String.join(", ", config.unknownKeys()));
        }
        Path state = Path.of(config.stateFile);
        if (!state.isAbsolute()) {
            config.stateFile = dir.resolve(state).toString();
        }
        return config;
    }

    /**
     * Starts the town watch (when the config says where to post) and the Discord connection (when
     * it has a bot token) on background threads, and returns at once.
     *
     * @param extra interactions the host answers itself, returning true for each one it took; or null
     */
    public static Embedded startEmbedded(BotConfig config, Predicate<JsonObject> extra) {
        String problem = config.validate();
        boolean wantsGateway = !config.botToken.isBlank() && (config.enableCommands || extra != null);
        if (problem != null && !wantsGateway) {
            LOG.info("Fallen-town alerts are off: {} (in {})", problem, CONFIG_NAME);
            return new Embedded(config, null, null, null);
        }
        HttpTransport transport = new HttpTransport();
        LokaApi api = new LokaApi(transport);
        BotStatus status = new BotStatus();
        DiscordPoster poster = problem == null ? new DiscordPoster(config) : null;
        DiscordGateway gateway = wantsGateway ? startCommands(config, api, transport, extra, poster, status) : null;

        Thread watchThread = null;
        if (problem == null) {
            BotState state = new BotState(Path.of(config.stateFile));
            state.load();
            FallenTownWatcher watcher = new FallenTownWatcher(api, state);
            watchThread = new Thread(() -> {
                if (config.testOnStart) {
                    postTestMessage(poster, config);
                }
                try {
                    watch(watcher, state, poster, config, false, status);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "betterloka-watch");
            watchThread.setDaemon(true);
            watchThread.start();
        } else {
            LOG.info("Fallen-town alerts are off: {} (in {})", problem, CONFIG_NAME);
        }
        return new Embedded(config, transport, gateway, watchThread);
    }

    /** Checks Loka for fallen towns until interrupted, or once. */
    private static void watch(FallenTownWatcher watcher, BotState state, DiscordPoster poster,
                              BotConfig config, boolean once, BotStatus status) throws InterruptedException {
        status.watchStarted();
        LOG.info("Watching Loka every {}s, full sweep at least every {} min; "
                        + "{} territory record(s) already announced",
                config.intervalSeconds(), config.fullSweepIntervalMinutes, state.announcedCount());
        if (state.isFirstRun() && !config.announceBacklogOnFirstRun) {
            LOG.info("First run: the towns that have already fallen will be recorded quietly, "
                    + "and only new ones announced from here.");
        }
        while (true) {
            try {
                FallenTownWatcher.Result result = watcher.check(config.announceBacklogOnFirstRun,
                        config.fullSweepIntervalMillis());
                status.checked(result.sweptFully(), result.deletedCount());
                if (result.sweptFully()) {
                    LOG.info("Swept: {} town(s) deleted on Loka, {} to announce",
                            result.deletedCount(), result.announce().size());
                }
                announce(result.announce(), poster, config, status);
            } catch (ApiException e) {
                status.checkFailed(e.getMessage());
                LOG.warn("Could not check Loka: {}", e.getMessage());
            } catch (RuntimeException e) {
                status.checkFailed(describe(e));
                LOG.warn("Check failed", e);
            }
            if (once) {
                return;
            }
            TimeUnit.SECONDS.sleep(config.intervalSeconds());
        }
    }

    private static String valueAfter(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    /** The console form of {@code /check}. */
    private static void printReport(TownReport reports, String townName) throws Exception {
        TownReport.Report report = reports.build(townName);
        if (report == null) {
            LOG.info("Loka has no town called \"{}\"", townName);
            return;
        }
        LOG.info("Checked {} of {} member(s); {} had no player record",
                report.selected(), report.rosterSize(), report.unresolved());
        LOG.info("{} - {}{}", report.town().name(),
                report.town().foundedIsImport() ? "on record since " : "founded ",
                report.town().founded());
        LOG.info("Continent {}, level {}, {} members; town last seen active {}",
                report.town().continentName(), (long) report.town().townLevel(),
                report.town().memberCount(),
                report.lastActive() == null ? "never" : TownCheckCommand.ago(report.lastActive()));
        for (TownReport.Member member : report.members()) {
            LOG.info("  {}{} - last seen {}{}",
                    member.owner() ? "[owner] " : (member.subOwner() ? "[sub] " : ""),
                    member.name(),
                    member.lastSeen() == null ? "no record" : TownCheckCommand.ago(member.lastSeen()),
                    member.lastSeenSource() == null ? "" : " (" + member.lastSeenSource() + ")");
        }
        if (report.sampled()) {
            LOG.info("  ... {} member(s) not checked - raise maxMembersChecked, or set it to 0",
                    report.skipped());
        }
    }

    /**
     * Connects to the gateway and registers {@code /check}, if a bot token is configured.
     *
     * <p>Optional on purpose: the fallen-town watch is the bot's job and works over a webhook alone.
     * Somebody who has not made a bot application gets a line saying what {@code /check} would
     * need, not a bot that refuses to start.
     *
     * @return the connection, or {@code null} if commands are not being served
     */
    private static DiscordGateway startCommands(BotConfig config, LokaApi api, HttpTransport transport,
                                                Predicate<JsonObject> extra, DiscordPoster poster,
                                                BotStatus status) {
        boolean commandsOn = config.servesCommands();
        if (!commandsOn && (extra == null || config.botToken.isBlank())) {
            if (config.enableCommands) {
                LOG.info("/check is off: it needs botToken (a webhook can only post, not listen). "
                        + "The fallen-town watch does not need one.");
            }
            return null;
        }

        SlashCommands commands = new SlashCommands(config.botToken);
        TownCheckCommand check = null;
        if (commandsOn) {
            if (config.maxMembersChecked <= 0) {
                LOG.warn("maxMembersChecked is 0, so /check will check every member of a town. "
                        + "Loka's largest has over 1200, which is a long report and a lot of traffic.");
            }
            check = new TownCheckCommand(
                    new TownReport(api, new EldritchApi(transport), config.maxMembersChecked), commands);
        }
        TownCheckCommand townCheck = check;

        java.util.concurrent.atomic.AtomicReference<DiscordGateway> connection =
                new java.util.concurrent.atomic.AtomicReference<>();
        // One store for both: the suggestion count and the stat channels share its file.
        CommunityStore community = new CommunityStore(
                Path.of(config.stateFile).toAbsolutePath().resolveSibling(COMMUNITY_FILE));
        java.util.concurrent.ExecutorService work = java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "betterloka-server");
            thread.setDaemon(true);
            return thread;
        });
        ServerCommands server = new ServerCommands(new DiscordRest(config.botToken), status, community,
                alerts(config, api, poster),
                () -> connection.get() == null ? null : connection.get().userId(),
                work,
                System::currentTimeMillis);
        StatChannels stats = startStats(config, api, transport, community, work);

        // The application ID arrives with READY, so registration waits for the connection rather
        // than guessing it: it is not the same number as the bot token's prefix on every account.
        DiscordGateway gateway = new DiscordGateway(config.botToken, interaction -> {
            if (extra != null && extra.test(interaction)) {
                return;
            }
            if (server.handle(interaction) || stats.handle(interaction)) {
                return;
            }
            String applicationId = interactionApplicationId(interaction);
            if (townCheck != null && applicationId != null) {
                townCheck.handle(interaction, applicationId);
            }
        });
        connection.set(gateway);
        gateway.start();

        if (commandsOn) {
            Thread registrar = new Thread(() -> registerWhenReady(gateway, commands, config),
                    "betterloka-register");
            registrar.setDaemon(true);
            registrar.start();
        }
        return gateway;
    }

    /**
     * The stat channels: their {@code /stats} command, and a thread that refreshes Loka's numbers and
     * renames the channels every {@link BotConfig#statsIntervalMinutes()} — only while some server has
     * them, and at once after one sets them up.
     */
    private static StatChannels startStats(BotConfig config, LokaApi api, HttpTransport transport,
                                           CommunityStore community, java.util.concurrent.Executor work) {
        LiveStats live = new LiveStats(config.statsServerAddress, config.statsStatusFallback, config.statsZone(),
                config.statsCountRivina, api, new com.betterloka.api.ArenaApi(transport));
        java.util.concurrent.atomic.AtomicReference<LiveStats.Snapshot> latest = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.BlockingQueue<Boolean> wake = new java.util.concurrent.LinkedBlockingQueue<>();
        StatChannels stats = new StatChannels(new DiscordRest(config.botToken), community, () -> {
            // A server setting them up gets real numbers in the new channels straight away.
            LiveStats.Snapshot known = latest.get();
            if (known == null) {
                known = live.refresh(System.currentTimeMillis());
                latest.set(known);
            }
            wake.offer(Boolean.TRUE);
            return known;
        }, config.statsIntervalMinutes(), work);
        Thread loop = new Thread(() -> {
            try {
                while (true) {
                    if (stats.anySetUp()) {
                        try {
                            LiveStats.Snapshot now = live.refresh(System.currentTimeMillis());
                            latest.set(now);
                            stats.update(now);
                        } catch (RuntimeException e) {
                            LOG.warn("Stat channels update failed", e);
                        }
                    }
                    wake.poll(config.statsIntervalMinutes(), TimeUnit.MINUTES);
                    wake.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "betterloka-stats");
        loop.setDaemon(true);
        loop.start();
        return stats;
    }

    /** Every interaction carries the application it was sent to, which is who has to answer it. */
    private static String interactionApplicationId(JsonObject interaction) {
        return interaction.has("application_id") && !interaction.get("application_id").isJsonNull()
                ? interaction.get("application_id").getAsString()
                : null;
    }

    private static void registerWhenReady(DiscordGateway gateway, SlashCommands commands,
                                          BotConfig config) {
        for (int attempt = 0; attempt < 60; attempt++) {
            String applicationId = gateway.applicationId();
            if (applicationId != null) {
                try {
                    commands.register(applicationId, config.guildIds());
                } catch (Exception e) {
                    LOG.error("Could not register the commands: {}", describe(e));
                }
                return;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warn("The gateway never reported ready, so the commands were not registered.");
    }

    /** Where the server's suggestion count is kept, next to the state file. */
    static final String COMMUNITY_FILE = "betterloka-community.json";

    /** What {@code /test} reports on and does: the same poster and the same Loka the watch uses. */
    private static ServerCommands.Alerts alerts(BotConfig config, LokaApi api, DiscordPoster poster) {
        return new ServerCommands.Alerts() {
            @Override
            public String offReason() {
                return poster == null ? config.validate() : null;
            }

            @Override
            public int intervalSeconds() {
                return config.intervalSeconds();
            }

            @Override
            public int probeLoka() throws Exception {
                return api.countDeletedTowns();
            }

            @Override
            public List<ServerCommands.Delivery> postSample(String requestedBy, boolean ping) {
                List<ServerCommands.Delivery> deliveries = new ArrayList<>();
                String by = requestedBy == null ? "" : " by <@" + requestedBy + ">";
                for (BotConfig.Target target : config.targets()) {
                    String role = ping && !target.roleId().isBlank() ? "<@&" + target.roleId() + "> " : "";
                    String error = null;
                    try {
                        poster.post(target, role + "🧪 **TEST** — this is what a fallen-town alert looks like "
                                + "(sent with `/test`" + by + "). No town has fallen.", List.of(sampleEmbed()));
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        error = describe(e);
                    }
                    deliveries.add(new ServerCommands.Delivery(target.describe(), target.roleId(), error));
                }
                return deliveries;
            }
        };
    }

    /** A made-up fallen town, in exactly the form a real one is announced, marked as a test. */
    static JsonObject sampleEmbed() {
        FallenTowns.Fallen sample = new FallenTowns.Fallen(
                com.betterloka.api.model.LokaTown.deleted("test", "Example Town", "north"),
                List.of(new Territory("test", "north", "12", "Example Ridge", "test", 3727, 80, 4205, 0)));
        JsonObject embed = embedFor(sample);
        embed.addProperty("title", "Example Town (TEST)");
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "BetterLoka · /test");
        embed.add("footer", footer);
        return embed;
    }

    /**
     * Posts one obviously-labelled test message.
     *
     * <p>So somebody setting the bot up can confirm the webhook works and the role actually gets
     * notified, without waiting for a town to fall or announcing the backlog to find out. It says
     * plainly that it is a test rather than inventing a town, and it touches no saved state.
     */
    private static void postTestMessage(DiscordPoster poster, BotConfig config) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Test message");
        embed.addProperty("color", 0x5FD37A);
        embed.addProperty("description",
                "No town has fallen — this is BetterLoka checking that it can post here.\n\n"
                        + "If the role above was notified, the setup is finished. If it appears as "
                        + "plain text instead, make the role mentionable in Server Settings → Roles.");
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "BetterLoka · --test");
        embed.add("footer", footer);

        for (BotConfig.Target target : config.targets()) {
            String ping = target.roleId().isBlank() ? "" : "<@&" + target.roleId() + "> ";
            try {
                poster.post(target, ping + "**BetterLoka is connected.**", List.of(embed));
                LOG.info("Test message posted to {}. Check the channel — and whether the role was notified.",
                        target.usesWebhook() ? "the webhook" : "channel " + target.channelId());
            } catch (Exception e) {
                LOG.error("Could not post the test message to {}: {}",
                        target.usesWebhook() ? "the webhook" : "channel " + target.channelId(), describe(e), e);
                LOG.error("Check webhookUrl (or botToken and channelId) in the config.");
            }
        }
    }

    /** Posts one message per batch, split when there are more towns than Discord allows embeds. */
    private static void announce(List<FallenTowns.Fallen> fallen, DiscordPoster poster, BotConfig config,
                                 BotStatus status) {
        if (fallen.isEmpty()) {
            return;
        }
        LOG.info("Announcing {} fallen town(s)", fallen.size());

        for (int from = 0; from < fallen.size(); from += DiscordPoster.MAX_EMBEDS) {
            List<FallenTowns.Fallen> batch =
                    fallen.subList(from, Math.min(fallen.size(), from + DiscordPoster.MAX_EMBEDS));
            List<JsonObject> embeds = new ArrayList<>();
            for (FallenTowns.Fallen town : batch) {
                embeds.add(embedFor(town));
            }
            List<String> names = new ArrayList<>();
            for (FallenTowns.Fallen town : batch) {
                names.add(town.town().name());
            }
            // Each place on its own: one channel the bot cannot reach must not cost the others.
            for (BotConfig.Target target : config.targets()) {
                try {
                    poster.post(target, headline(batch, target.roleId()), embeds);
                    status.announced(String.join(", ", names));
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    String where = target.usesWebhook() ? "webhook" : "channel " + target.channelId();
                    status.postFailed(where + ": " + describe(e));
                    // The message alone is often null on a connection failure, and "could not post: null"
                    // tells somebody debugging their webhook nothing at all.
                    LOG.error("Could not post to Discord ({}): {}", where, describe(e), e);
                    // Not rethrown: the state already records these as announced, and a Discord outage
                    // should not stop the watch. The next genuine fall still gets through.
                }
            }
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        String detail = message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
        Throwable cause = error.getCause();
        return cause == null ? detail : detail + " (caused by " + describe(cause) + ")";
    }

    private static String headline(List<FallenTowns.Fallen> batch, String roleId) {
        String ping = roleId == null || roleId.isBlank() ? "" : "<@&" + roleId + "> ";
        if (batch.size() == 1) {
            return ping + "**" + batch.get(0).town().name() + "** has fallen — its territory is open.";
        }
        return ping + "**" + batch.size() + " towns** have fallen — their territory is open.";
    }

    static JsonObject embedFor(FallenTowns.Fallen fallen) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", fallen.town().name());
        embed.addProperty("color", EMBED_COLOR);
        embed.addProperty("description", fallen.territories().size() == 1
                ? "No longer exists, and still holds this territory."
                : "No longer exists, and still holds these " + fallen.territories().size() + " territories.");

        JsonArray fields = new JsonArray();
        for (Territory territory : fallen.territories()) {
            JsonObject field = new JsonObject();
            field.addProperty("name", territory.continent() + " · #" + territory.num());
            // The coordinates are the whole point: somebody has to be able to fly there.
            field.addProperty("value", "`" + territory.coordinates() + "`"
                    + (territory.areaName() == null ? "" : "\n" + territory.areaName()));
            field.addProperty("inline", true);
            fields.add(field);
        }
        embed.add("fields", fields);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", "BetterLoka");
        embed.add("footer", footer);
        embed.addProperty("timestamp", java.time.Instant.now().toString());
        return embed;
    }
}
