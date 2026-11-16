package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link DiscordActions} over Discord's REST API, as the bot. Waits out a rate limit the way Discord
 * says to and retries a server error; anything else is a {@link DiscordActions.Refused} carrying the
 * status, because a 403 here is nearly always a permission or a role order to fix.
 */
public final class DiscordRest implements DiscordActions {
    private static final String API = "https://discord.com/api/v10";
    private static final int ATTEMPTS = 4;

    private final String base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public DiscordRest(String token) {
        this(API, token);
    }

    DiscordRest(String base, String token) {
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    @Override
    public void respond(String interactionId, String interactionToken, JsonObject response)
            throws IOException, InterruptedException {
        send("POST", "/interactions/" + interactionId + "/" + interactionToken + "/callback", response, false, null);
    }

    @Override
    public void editReply(String applicationId, String interactionToken, JsonObject message)
            throws IOException, InterruptedException {
        send("PATCH", "/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original", message, false, null);
    }

    @Override
    public String post(String channelId, JsonObject message) throws IOException, InterruptedException {
        JsonObject created = object(send("POST", "/channels/" + channelId + "/messages", message, true, null));
        if (!created.has("id")) {
            throw new IOException("Discord did not say which message it created");
        }
        return created.get("id").getAsString();
    }

    @Override
    public JsonObject message(String channelId, String messageId) throws IOException, InterruptedException {
        return object(send("GET", "/channels/" + channelId + "/messages/" + messageId, null, true, null));
    }

    @Override
    public void delete(String channelId, String messageId) throws IOException, InterruptedException {
        send("DELETE", "/channels/" + channelId + "/messages/" + messageId, null, true, null);
    }

    @Override
    public String startThread(String channelId, String messageId, String name) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        // A week: a suggestion is discussed for days, not minutes.
        body.addProperty("auto_archive_duration", 10080);
        JsonObject thread = object(send("POST", "/channels/" + channelId + "/messages/" + messageId + "/threads",
                body, true, null));
        if (!thread.has("id")) {
            throw new IOException("Discord did not say which thread it opened");
        }
        return thread.get("id").getAsString();
    }

    @Override
    public void react(String channelId, String messageId, String emoji) throws IOException, InterruptedException {
        send("PUT", "/channels/" + channelId + "/messages/" + messageId + "/reactions/"
                + URLEncoder.encode(emoji, StandardCharsets.UTF_8) + "/@me", null, true, null);
    }

    @Override
    public void addRole(String guildId, String userId, String roleId, String reason)
            throws IOException, InterruptedException {
        send("PUT", "/guilds/" + guildId + "/members/" + userId + "/roles/" + roleId, null, true, reason);
    }

    @Override
    public JsonArray roles(String guildId) throws IOException, InterruptedException {
        JsonElement roles = send("GET", "/guilds/" + guildId + "/roles", null, true, null);
        return roles != null && roles.isJsonArray() ? roles.getAsJsonArray() : new JsonArray();
    }

    @Override
    public JsonObject member(String guildId, String userId) throws IOException, InterruptedException {
        return object(send("GET", "/guilds/" + guildId + "/members/" + userId, null, true, null));
    }

    private JsonElement send(String method, String path, JsonObject body, boolean authorized, String reason)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "DiscordBot (https://github.com/haapi123/bomba, 1.0)");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (authorized) {
            builder.header("Authorization", "Bot " + token);
        }
        if (reason != null && !reason.isBlank()) {
            // Shown in the server's audit log next to the change.
            builder.header("X-Audit-Log-Reason", URLEncoder.encode(reason, StandardCharsets.UTF_8).replace("+", "%20"));
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
            throw new Refused(status, "Discord answered " + status + ": " + trimmed(response.body()));
        }
        throw new IOException("Discord kept rate limiting");
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static JsonElement parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long retryAfterMillis(String body) {
        JsonObject json = object(parse(body));
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
