package com.betterloka.bot;

import com.betterloka.api.model.LokaTown;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles {@code /sprawdz <town>}: answers with when the town was founded and how recently each of
 * its members has been seen doing anything on the server.
 *
 * <p>The work runs off the gateway thread — a forty-member town is a minute of HTTP — so the
 * interaction is deferred first and the answer patched in when it is ready.
 */
public final class TownCheckCommand {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    /** Blue for a live town, grey when nothing about it is known. */
    private static final int COLOR = 0x5B8DEF;
    private static final int COLOR_UNKNOWN = 0x8A8F98;

    /** Discord's own limit on an embed description, minus room for the header. */
    private static final int MAX_DESCRIPTION = 3800;

    private final TownReport reports;
    private final SlashCommands api;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "betterloka-command");
        thread.setDaemon(true);
        return thread;
    });

    public TownCheckCommand(TownReport reports, SlashCommands api) {
        this.reports = reports;
        this.api = api;
    }

    /** @return true if this interaction was ours to handle */
    public boolean handle(JsonObject interaction, String applicationId) {
        JsonObject data = interaction.has("data") ? interaction.getAsJsonObject("data") : null;
        if (data == null || !"sprawdz".equals(string(data, "name"))) {
            return false;
        }

        String interactionId = string(interaction, "id");
        String interactionToken = string(interaction, "token");
        String townName = optionValue(data, "town");
        if (interactionId == null || interactionToken == null || townName == null) {
            return false;
        }

        try {
            api.defer(interactionId, interactionToken);
        } catch (Exception e) {
            LOG.warn("Could not defer the interaction: {}", e.getMessage());
            return true;
        }

        workers.submit(() -> answer(applicationId, interactionToken, townName));
        return true;
    }

    private void answer(String applicationId, String interactionToken, String townName) {
        JsonObject payload = new JsonObject();
        try {
            TownReport.Report report = reports.build(townName);
            payload.add("embeds", one(report == null ? notFound(townName) : embed(report)));
        } catch (Exception e) {
            LOG.warn("Could not build the report for {}", townName, e);
            payload.addProperty("content",
                    "Could not reach Loka just now. Try again in a moment.");
        }
        try {
            api.reply(applicationId, interactionToken, payload);
        } catch (Exception e) {
            LOG.warn("Could not answer /sprawdz {}: {}", townName, e.getMessage());
        }
    }

    private static JsonArray one(JsonObject embed) {
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        return embeds;
    }

    private static JsonObject notFound(String townName) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", townName);
        embed.addProperty("color", COLOR_UNKNOWN);
        embed.addProperty("description",
                "Loka has no town by that name. Names are matched exactly, so check the spelling — "
                        + "and a town that has already been deleted no longer answers to one.");
        return embed;
    }

    private static JsonObject embed(TownReport.Report report) {
        LokaTown town = report.town();

        JsonObject embed = new JsonObject();
        embed.addProperty("title", town.name());
        embed.addProperty("color", COLOR);

        StringBuilder description = new StringBuilder();
        if (town.foundedIsImport()) {
            // Sixteen towns share this instant to the second. Calling that a founding date would be
            // reporting the day Loka's database was built as the day these towns were made.
            description.append("**On record since** ").append(STAMP.format(town.founded()))
                    .append(" UTC\n")
                    .append("_One of Loka's oldest towns: its record was created when the database "
                            + "was built, so it was founded some time before this._\n");
        } else {
            description.append("**Founded** ").append(STAMP.format(town.founded()))
                    .append(" UTC  ·  ").append(ago(town.founded())).append('\n');
        }

        description.append("**Continent** ").append(town.continentName())
                .append("  ·  **Level** ").append((long) town.townLevel())
                .append("  ·  **Members** ").append(town.memberCount()).append('\n');

        Instant lastActive = report.lastActive();
        description.append("**Town last seen active** ")
                .append(lastActive == null ? "no record" : ago(lastActive)).append('\n');

        long quiet = report.quietFor(30);
        description.append("**Quiet 30+ days** ").append(quiet).append('/')
                .append(report.members().size()).append(" members\n\n");

        appendMembers(description, report.members());

        if (report.skipped() > 0) {
            description.append("\n_").append(report.skipped())
                    .append(" more member(s) not checked._");
        }

        embed.addProperty("description", trim(description.toString()));

        JsonObject footer = new JsonObject();
        // Said plainly, every time: Loka publishes no last-login, and a date here that somebody
        // read as one would be the whole report lying about what it knows.
        footer.addProperty("text", "BetterLoka · \"last seen\" is the newest Conquest fight or market "
                + "listing on record — Loka publishes no login times, so a player who only builds "
                + "leaves no trace here.");
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());
        return embed;
    }

    private static void appendMembers(StringBuilder out, List<TownReport.Member> members) {
        if (members.isEmpty()) {
            out.append("_No members could be looked up._");
            return;
        }
        for (TownReport.Member member : members) {
            String role = member.owner() ? "👑 " : (member.subOwner() ? "🛡 " : "• ");
            out.append(role).append("**").append(member.name()).append("** — ");
            Instant seen = member.lastSeen();
            if (seen == null) {
                out.append("no fight or listing on record");
            } else {
                out.append(ago(seen)).append(" (").append(member.lastSeenSource()).append(')');
            }
            if (member.joinedLoka() != null) {
                out.append(", joined ").append(STAMP.format(member.joinedLoka()).substring(0, 10));
            }
            out.append('\n');
        }
    }

    private static String trim(String text) {
        return text.length() <= MAX_DESCRIPTION
                ? text
                : text.substring(0, MAX_DESCRIPTION) + "\n_…list truncated._";
    }

    /** Compact age, e.g. {@code 12d ago}. */
    static String ago(Instant instant) {
        if (instant == null) {
            return "—";
        }
        Duration since = Duration.between(instant, Instant.now());
        if (since.isNegative()) {
            return "just now";
        }
        long days = since.toDays();
        if (days >= 365) {
            return (days / 365) + "y ago";
        }
        if (days >= 30) {
            return (days / 30) + "mo ago";
        }
        if (days >= 1) {
            return days + "d ago";
        }
        long hours = since.toHours();
        return hours >= 1 ? hours + "h ago" : "today";
    }

    private static String optionValue(JsonObject data, String name) {
        if (!data.has("options")) {
            return null;
        }
        for (var element : data.getAsJsonArray("options")) {
            JsonObject option = element.getAsJsonObject();
            if (name.equals(string(option, "name"))) {
                return string(option, "value");
            }
        }
        return null;
    }

    private static String string(JsonObject json, String key) {
        return json != null && json.has(key) && !json.get(key).isJsonNull()
                ? json.get(key).getAsString()
                : null;
    }
}
