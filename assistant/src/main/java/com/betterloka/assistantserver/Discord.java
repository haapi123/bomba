package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tells the owner's Discord channel what needs a look: questions the assistant could not answer and
 * answers waiting for approval. Optional, fire and forget; a full queue drops rather than piles up.
 */
final class Discord {
    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), runnable -> {
                Thread thread = new Thread(runnable, "assistant-discord");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    Discord(String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.strip();
    }

    boolean enabled() {
        return webhookUrl.startsWith("https://");
    }

    void post(String text) {
        if (!enabled()) {
            return;
        }
        executor.execute(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) + "…" : text);
            JsonObject mentions = new JsonObject();
            mentions.add("parse", new JsonArray());
            body.add("allowed_mentions", mentions);
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.COMPACT.toJson(body)))
                    .build();
            try {
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 300) {
                    Log.warn("Discord webhook answered " + response.statusCode());
                }
                Thread.sleep(1500);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Log.warn("Discord webhook failed: " + e.getMessage());
            }
        });
    }

    /** Text a player wrote, made inert for Discord: no mentions, no markdown links, no code fences. */
    static String quote(String text) {
        String flat = text.replaceAll("\\s+", " ").strip()
                .replace("@", "@​")
                .replace("`", "'");
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }
}
