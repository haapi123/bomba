package com.betterloka.bot;

import com.betterloka.api.ApiException;
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

        if (!Files.exists(CONFIG_FILE)) {
            new BotConfig().writeTemplate(CONFIG_FILE);
            LOG.info("Wrote a starter config to {} — fill in webhookUrl (or botToken and channelId)",
                    CONFIG_FILE.toAbsolutePath());
            LOG.info("Then set roleId to the role you want pinged, and start the bot again.");
            return;
        }

        BotConfig config = BotConfig.load(CONFIG_FILE);
        String problem = config.validate();
        if (problem != null && !list) {
            LOG.error("{}", problem);
            LOG.error("Edit {} or set the matching BETTERLOKA_* environment variables.",
                    CONFIG_FILE.toAbsolutePath());
            System.exit(1);
            return;
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
                    if (result.sweptFully()) {
                        LOG.info("Swept: {} town(s) deleted on Loka, {} to announce",
                                result.deletedCount(), result.announce().size());
                    }
                    announce(result.announce(), poster, config);
                } catch (ApiException e) {
                    LOG.warn("Could not check Loka: {}", e.getMessage());
                } catch (RuntimeException e) {
                    LOG.warn("Check failed", e);
                }
                if (once) {
                    return;
                }
                TimeUnit.SECONDS.sleep(config.intervalSeconds());
            }
        }
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

        String ping = config.roleId.isBlank() ? "" : "<@&" + config.roleId + "> ";
        try {
            poster.post(ping + "**BetterLoka is connected.**", List.of(embed));
            LOG.info("Test message posted. Check the channel — and whether the role was notified.");
        } catch (Exception e) {
            LOG.error("Could not post the test message: {}", describe(e), e);
            LOG.error("Check webhookUrl (or botToken and channelId) in the config.");
        }
    }

    /** Posts one message per batch, split when there are more towns than Discord allows embeds. */
    private static void announce(List<FallenTowns.Fallen> fallen, DiscordPoster poster, BotConfig config) {
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
            try {
                poster.post(headline(batch, config), embeds);
            } catch (Exception e) {
                // The message alone is often null on a connection failure, and "could not post: null"
                // tells somebody debugging their webhook nothing at all.
                LOG.error("Could not post to Discord: {}", describe(e), e);
                // Not rethrown: the state already records these as announced, and a Discord outage
                // should not stop the watch. The next genuine fall still gets through.
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

    private static String headline(List<FallenTowns.Fallen> batch, BotConfig config) {
        String ping = config.roleId.isBlank() ? "" : "<@&" + config.roleId + "> ";
        if (batch.size() == 1) {
            return ping + "**" + batch.get(0).town().name() + "** has fallen — its territory is open.";
        }
        return ping + "**" + batch.size() + " towns** have fallen — their territory is open.";
    }

    private static JsonObject embedFor(FallenTowns.Fallen fallen) {
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
