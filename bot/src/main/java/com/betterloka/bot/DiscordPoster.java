package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Posts to Discord, either through a webhook or as a bot.
 *
 * <p>Both are a single HTTPS POST, so there is no gateway connection and no Discord library: the bot
 * only ever speaks, never listens, and a library for that would be several megabytes to save a dozen
 * lines.
 */
public final class DiscordPoster {
    private static final String API = "https://discord.com/api/v10";
    private static final int MAX_ATTEMPTS = 4;

    /** Discord's own cap. A run announcing more than this splits into several messages. */
    public static final int MAX_EMBEDS = 10;

    private final BotConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public DiscordPoster(BotConfig config) {
        this.config = config;
    }

    /**
     * Sends one message.
     *
     * @param content the line above the embeds, which is where the role ping goes
     * @param embeds  at most {@link #MAX_EMBEDS}
     */
    public void post(String content, List<JsonObject> embeds) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);

        JsonArray array = new JsonArray();
        for (JsonObject embed : embeds) {
            array.add(embed);
        }
        payload.add("embeds", array);

        // Without this a role mention renders as a ping but never notifies anybody, which is the
        // entire point of the bot. Roles are listed explicitly rather than allowing "everyone".
        JsonObject allowed = new JsonObject();
        JsonArray parse = new JsonArray();
        JsonArray roles = new JsonArray();
        if (!config.roleId.isBlank()) {
            roles.add(config.roleId);
        }
        allowed.add("parse", parse);
        allowed.add("roles", roles);
        payload.add("allowed_mentions", allowed);

        send(payload.toString());
    }

    private void send(String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(config.usesWebhook()
                        ? config.webhookUrl
                        : API + "/channels/" + config.channelId + "/messages"))
                .header("Content-Type", "application/json")
                .header("User-Agent", "BetterLokaBot (https://github.com/haapi123/bomba, 1.0)")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (!config.usesWebhook()) {
            builder.header("Authorization", "Bot " + config.botToken);
        }
        HttpRequest request = builder.build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return;
            }
            if (status == 429) {
                // Discord says exactly how long to wait; guessing would just earn another 429.
                long waitMillis = retryAfterMillis(response.body());
                Thread.sleep(waitMillis);
                continue;
            }
            if (status >= 500 && attempt < MAX_ATTEMPTS) {
                Thread.sleep(1000L * attempt);
                continue;
            }
            // 401/403/404 are configuration mistakes, and retrying a wrong token forever helps
            // nobody: say what Discord said and let the caller decide.
            throw new IOException("Discord answered " + status + ": " + trimmed(response.body()));
        }
        throw new IOException("Discord kept rate limiting the message");
    }

    private static long retryAfterMillis(String body) {
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("retry_after")) {
                return Math.max(1000L, (long) (json.get("retry_after").getAsDouble() * 1000));
            }
        } catch (RuntimeException ignored) {
            // Fall through to the default wait.
        }
        return 5000L;
    }

    private static String trimmed(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
