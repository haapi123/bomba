package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * The Discord calls the server's own commands make — verification, suggestions and {@code /test}.
 *
 * <p>An interface so the flows can be tested against a fake that records what was asked of it,
 * rather than against Discord.
 */
public interface DiscordActions {
    /** Answers an interaction: a message, a form, or "thinking…". Discord allows three seconds. */
    void respond(String interactionId, String interactionToken, JsonObject response)
            throws IOException, InterruptedException;

    /** Replaces the deferred "thinking…" answer with the real one. */
    void editReply(String applicationId, String interactionToken, JsonObject message)
            throws IOException, InterruptedException;

    /** Posts a message and returns its id. */
    String post(String channelId, JsonObject message) throws IOException, InterruptedException;

    JsonObject message(String channelId, String messageId) throws IOException, InterruptedException;

    void delete(String channelId, String messageId) throws IOException, InterruptedException;

    /** Opens a public thread on a message and returns the thread's id. */
    String startThread(String channelId, String messageId, String name) throws IOException, InterruptedException;

    void react(String channelId, String messageId, String emoji) throws IOException, InterruptedException;

    void addRole(String guildId, String userId, String roleId, String reason) throws IOException, InterruptedException;

    JsonArray roles(String guildId) throws IOException, InterruptedException;

    JsonObject member(String guildId, String userId) throws IOException, InterruptedException;

    /** What Discord answered when it refused, so a reply can say which setting is wrong. */
    final class Refused extends IOException {
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
