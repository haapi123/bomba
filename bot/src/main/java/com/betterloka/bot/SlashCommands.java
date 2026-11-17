package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** The REST half of slash commands: registering them, and answering an interaction. */
public final class SlashCommands {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");

    private static final String API = "https://discord.com/api/v10";

    /** Discord's callback types: 5 is "thinking...", which buys an answer more than three seconds. */
    private static final int DEFERRED_CHANNEL_MESSAGE = 5;

    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public SlashCommands(String token) {
        this.token = token;
    }

    /** Every command the bot serves: {@code /check}, then the server's own ({@link ServerCommands}). */
    public static JsonArray allCommands() {
        JsonObject option = new JsonObject();
        option.addProperty("type", 3);
        option.addProperty("name", "town");
        option.addProperty("description", "Name of the town to check");
        option.addProperty("required", true);

        JsonArray options = new JsonArray();
        options.add(option);

        JsonObject command = new JsonObject();
        command.addProperty("name", "check");
        command.addProperty("description", "When a town was founded, and how recently its members have been seen");
        command.addProperty("type", 1);
        command.add("options", options);

        JsonArray commands = new JsonArray();
        commands.add(command);
        commands.addAll(ServerCommands.definitions());
        commands.add(StatChannels.definition());
        return commands;
    }

    /**
     * Registers every command with Discord. A registration replaces whatever set was there before, so
     * a renamed command simply disappears under its old name.
     *
     * @param guildIds servers to register in, which takes effect immediately; or none, to register
     *                 globally — which works everywhere the bot is, but can take up to an hour to appear
     */
    public void register(String applicationId, List<String> guildIds) throws IOException, InterruptedException {
        String commands = allCommands().toString();
        if (guildIds == null || guildIds.isEmpty()) {
            put(API + "/applications/" + applicationId + "/commands", commands);
            LOG.info("Registered /check, /test, /verification, /suggestions and /stats globally "
                    + "(it can take up to an hour to appear)");
            return;
        }
        int registered = 0;
        for (String guildId : guildIds) {
            try {
                put(API + "/applications/" + applicationId + "/guilds/" + guildId + "/commands", commands);
                registered++;
                LOG.info("Registered /check, /test, /verification, /suggestions and /stats in server {}", guildId);
            } catch (IOException e) {
                // Usually a server the bot has not been invited to yet; the others still get theirs.
                LOG.error("Could not register the commands in server {} (is the bot in it, invited with "
                        + "applications.commands?): {}", guildId, e.getMessage());
            }
        }
        if (registered > 0) {
            // Per-server commands and global ones both show, so an earlier global registration would
            // put every command in the list twice.
            try {
                put(API + "/applications/" + applicationId + "/commands", "[]");
            } catch (IOException e) {
                LOG.warn("Could not clear the global commands: {}", e.getMessage());
            }
        }
    }

    /**
     * Replaces a set of commands. Discord limits how often that may be done ("Service resource is
     * being rate limited", 429) — several restarts in a row hit it — so its wait is waited out and
     * the request sent again, a few times, rather than given up on.
     */
    private void put(String url, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            response = send(HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
            if (response.statusCode() != 429 || attempt == 4) {
                break;
            }
            long wait = retryAfterMillis(response.body());
            LOG.info("Discord asks to wait {} s before registering the commands again", (wait + 999) / 1000);
            Thread.sleep(wait);
        }
        if (response.statusCode() >= 300) {
            throw new IOException("Discord refused the command registration: "
                    + response.statusCode() + " " + trimmed(response.body()));
        }
    }

    /** Discord's {@code retry_after}, in milliseconds, kept between half a second and a minute. */
    static long retryAfterMillis(String body) {
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("retry_after")) {
                return Math.min(60_000L, Math.max(500L, (long) Math.ceil(json.get("retry_after").getAsDouble() * 1000)));
            }
        } catch (RuntimeException ignored) {
            // Fall through to a safe default.
        }
        return 5000L;
    }

    /** Tells Discord the bot is working on it, so the three second deadline stops running. */
    public void defer(String interactionId, String interactionToken) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", DEFERRED_CHANNEL_MESSAGE);

        HttpResponse<String> response = send(HttpRequest
                .newBuilder(URI.create(API + "/interactions/" + interactionId + "/"
                        + interactionToken + "/callback"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)));

        if (response.statusCode() >= 300) {
            throw new IOException("Discord refused the deferral: "
                    + response.statusCode() + " " + trimmed(response.body()));
        }
    }

    /** Replaces the "thinking..." placeholder with the real answer. */
    public void reply(String applicationId, String interactionToken, JsonObject payload)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(HttpRequest
                .newBuilder(URI.create(API + "/webhooks/" + applicationId + "/"
                        + interactionToken + "/messages/@original"))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString(),
                        StandardCharsets.UTF_8)));

        if (response.statusCode() >= 300) {
            throw new IOException("Discord refused the reply: "
                    + response.statusCode() + " " + trimmed(response.body()));
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        HttpRequest request = builder
                .header("Authorization", "Bot " + token)
                .header("User-Agent", "BetterLokaBot (https://github.com/haapi123/bomba, 1.0)")
                .timeout(Duration.ofSeconds(30))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String trimmed(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
