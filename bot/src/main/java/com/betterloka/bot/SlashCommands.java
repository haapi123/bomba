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

    /**
     * Registers {@code /sprawdz} with Discord.
     *
     * @param guildId a server to register in, which takes effect immediately, or blank to register
     *                globally — which is what makes the command work everywhere the bot is, but can
     *                take up to an hour to appear
     */
    public void register(String applicationId, String guildId) throws IOException, InterruptedException {
        JsonObject option = new JsonObject();
        option.addProperty("type", 3);
        option.addProperty("name", "town");
        option.addProperty("description", "Name of the town to check");
        option.addProperty("required", true);

        JsonArray options = new JsonArray();
        options.add(option);

        JsonObject command = new JsonObject();
        command.addProperty("name", "sprawdz");
        command.addProperty("description", "When a town was founded, and how recently its members have been seen");
        command.addProperty("type", 1);
        command.add("options", options);

        JsonArray commands = new JsonArray();
        commands.add(command);

        String url = guildId == null || guildId.isBlank()
                ? API + "/applications/" + applicationId + "/commands"
                : API + "/applications/" + applicationId + "/guilds/" + guildId + "/commands";

        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(commands.toString(), StandardCharsets.UTF_8)));

        if (response.statusCode() >= 300) {
            throw new IOException("Discord refused the command registration: "
                    + response.statusCode() + " " + trimmed(response.body()));
        }
        LOG.info("Registered /sprawdz {}", guildId == null || guildId.isBlank()
                ? "globally (it can take up to an hour to appear)"
                : "in server " + guildId);
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
