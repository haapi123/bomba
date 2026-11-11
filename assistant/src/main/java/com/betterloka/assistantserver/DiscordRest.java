package com.betterloka.assistantserver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The few Discord REST calls the questions channel needs: post a message, edit it, and answer a
 * button click or a form. Waits out rate limits the way Discord says to, gives up on anything else.
 */
final class DiscordRest {
    static final String API = "https://discord.com/api/v10";
    private static final int ATTEMPTS = 4;

    private final String base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    DiscordRest(String base, String token) {
        this.base = base == null || base.isBlank() ? API : base.replaceAll("/+$", "");
        this.token = token;
    }

    /** Posts a message and returns its id. */
    String post(String channelId, JsonObject message) throws IOException, InterruptedException {
        JsonObject created = send("POST", "/channels/" + channelId + "/messages", message, true);
        if (!created.has("id")) {
            throw new IOException("Discord did not say which message it created");
        }
        return created.get("id").getAsString();
    }

    void edit(String channelId, String messageId, JsonObject message) throws IOException, InterruptedException {
        send("PATCH", "/channels/" + channelId + "/messages/" + messageId, message, true);
    }

    /** Answers an interaction; Discord allows three seconds, and this endpoint takes no token. */
    void respond(String interactionId, String interactionToken, JsonObject response)
            throws IOException, InterruptedException {
        send("POST", "/interactions/" + interactionId + "/" + interactionToken + "/callback", response, false);
    }

    private JsonObject send(String method, String path, JsonObject body, boolean authorized)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("User-Agent", "DiscordBot (https://github.com/haapi123/bomba, 1.0)")
                .method(method, HttpRequest.BodyPublishers.ofString(Json.COMPACT.toJson(body), StandardCharsets.UTF_8));
        if (authorized) {
            builder.header("Authorization", "Bot " + token);
        }
        HttpRequest request = builder.build();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return parse(response.body());
            }
            if (status == 429 && attempt < ATTEMPTS) {
                Thread.sleep(retryAfterMillis(response.body()));
                continue;
            }
            if (status >= 500 && attempt < ATTEMPTS) {
                Thread.sleep(1000L * attempt);
                continue;
            }
            throw new IOException("Discord answered " + status + ": " + trimmed(response.body()));
        }
        throw new IOException("Discord kept rate limiting");
    }

    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement json = JsonParser.parseString(body);
            return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static long retryAfterMillis(String body) {
        JsonObject json = parse(body);
        if (json.has("retry_after")) {
            try {
                return Math.min(60_000L, Math.max(500L, (long) (json.get("retry_after").getAsDouble() * 1000)));
            } catch (RuntimeException ignored) {
                // Fall through.
            }
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
