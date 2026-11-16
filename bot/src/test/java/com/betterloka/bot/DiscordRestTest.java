package com.betterloka.bot;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The REST client against a local stand-in for Discord: what it sends, and what it makes of answers. */
class DiscordRestTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> reasons = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final AtomicInteger rateLimited = new AtomicInteger();
    private DiscordRest rest;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            requests.add(exchange.getRequestMethod() + " " + path);
            reasons.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Audit-Log-Reason")));
            auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.getRequestBody().readAllBytes();
            int status;
            String body;
            if (path.contains("/roles/403")) {
                status = 403;
                body = "{\"message\": \"Missing Permissions\", \"code\": 50013}";
            } else if (path.endsWith("/messages") && rateLimited.getAndIncrement() == 0) {
                status = 429;
                body = "{\"retry_after\": 0.01}";
            } else if (path.endsWith("/messages")) {
                status = 200;
                body = "{\"id\": \"42\"}";
            } else if (path.endsWith("/threads")) {
                status = 201;
                body = "{\"id\": \"43\"}";
            } else {
                status = 204;
                body = "";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        rest = new DiscordRest("http://127.0.0.1:" + server.getAddress().getPort() + "/", "secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void postsWaitOutARateLimitAndReturnTheNewId() throws Exception {
        assertEquals("42", rest.post("200", new JsonObject()));
        assertEquals(List.of("POST /channels/200/messages", "POST /channels/200/messages"), requests);
        assertEquals("Bot secret", auth.get(0));
    }

    @Test
    void anEmojiGoesInThePathEncoded() throws Exception {
        rest.react("200", "42", "👍");
        assertEquals("PUT /channels/200/messages/42/reactions/%F0%9F%91%8D/@me", requests.get(0));
    }

    @Test
    void aRoleIsGivenWithAReasonForTheAuditLog() throws Exception {
        rest.addRole("100", "300", "400", "Weryfikacja przyciskiem");
        assertEquals("PUT /guilds/100/members/300/roles/400", requests.get(0));
        assertEquals("Weryfikacja%20przyciskiem", reasons.get(0), "a space as %20, not a plus");
    }

    @Test
    void aRefusalCarriesDiscordsStatus() {
        DiscordActions.Refused refused = assertThrows(DiscordActions.Refused.class,
                () -> rest.addRole("100", "300", "403", null));
        assertEquals(403, refused.status());
        assertTrue(refused.getMessage().contains("Missing Permissions"));
    }

    @Test
    void threadsAndInteractionAnswers() throws Exception {
        assertEquals("43", rest.startThread("200", "42", "#1 Pomysł"));
        rest.respond("1", "tok", new JsonObject());
        rest.editReply("app", "tok", new JsonObject());
        rest.delete("200", "41");
        assertEquals(List.of("POST /channels/200/messages/42/threads", "POST /interactions/1/tok/callback",
                "PATCH /webhooks/app/tok/messages/@original", "DELETE /channels/200/messages/41"), requests);
        assertEquals("null", auth.get(1), "an interaction answer is authorised by its own token, not the bot's");
    }
}
