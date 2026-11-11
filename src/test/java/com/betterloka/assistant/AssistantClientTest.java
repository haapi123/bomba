package com.betterloka.assistant;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantClientTest {
    private HttpServer server;
    private final List<String> joined = new ArrayList<>();
    private final List<JsonObject> asks = new ArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile String validTicket = "ticket-1";
    private volatile String askError;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request = body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String reply;
        switch (path) {
            case "/v1/login/start" -> reply = "{\"ok\":true,\"serverId\":\"abc123\"}";
            case "/v1/login/finish" -> {
                logins.incrementAndGet();
                reply = "{\"ok\":true,\"token\":\"" + validTicket + "\",\"expiresAt\":" + (System.currentTimeMillis() + 86_400_000L)
                        + ",\"name\":\"" + request.get("name").getAsString() + "\",\"admin\":true,\"questionsLeft\":30}";
            }
            case "/v1/ask" -> {
                if (!("Bearer " + validTicket).equals(auth)) {
                    reply = "{\"ok\":false,\"error\":\"login\"}";
                } else if (askError != null) {
                    reply = "{\"ok\":false,\"error\":\"" + askError + "\",\"message\":\"no\"}";
                } else {
                    asks.add(request);
                    reply = "{\"ok\":true,\"id\":\"a1\",\"answer\":\"Na Balaku.\",\"status\":\"answered\",\"questionsLeft\":29}";
                }
            }
            case "/v1/open" -> reply = "{\"ok\":true,\"questions\":[{\"id\":\"q1\",\"question\":\"Ile?\",\"kind\":\"disputed\","
                    + "\"botAnswer\":\"Sto.\",\"count\":3}]}";
            default -> reply = "{\"ok\":false,\"error\":\"not_found\"}";
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private AssistantClient client(String address) {
        return new AssistantClient(() -> address, () -> "Steve", serverId -> joined.add(serverId));
    }

    @Test
    void everyCopyTalksToTheBetterLokaServerAndOnlyATestRunCanPointElsewhere() {
        assertEquals("http://ent-8.icehost.pl:40076", AssistantClient.server(java.util.Map.of()));
        assertEquals(AssistantClient.SERVER, AssistantClient.server(java.util.Map.of("BETTERLOKA_ASSISTANT", "127.0.0.1:8787")),
                "an address alone changes nothing");
        assertEquals("http://127.0.0.1:8787", AssistantClient.server(
                java.util.Map.of("BETTERLOKA_DIAG", "v43", "BETTERLOKA_ASSISTANT", "127.0.0.1:8787")));
    }

    @Test
    void normalizesAddresses() {
        assertEquals("http://1.2.3.4:8787", AssistantClient.normalize(" 1.2.3.4:8787/ "));
        assertEquals("https://example.com", AssistantClient.normalize("https://example.com//"));
        assertEquals("", AssistantClient.normalize("  "));
    }

    @Test
    void logsInOnceAsksAndLogsInAgainWhenTheTicketIsRefused() throws Exception {
        AssistantClient client = client("127.0.0.1:" + server.getAddress().getPort());
        AssistantClient.Answer answer = client.ask("Gdzie jest Warden?", "pl_pl",
                List.of(new AssistantClient.Turn("Hej", "Cześć"))).get(10, TimeUnit.SECONDS);
        assertEquals("Na Balaku.", answer.text());
        assertEquals(29, client.questionsLeft());
        assertEquals("Steve", client.loggedInAs());
        assertTrue(client.admin());
        assertEquals(List.of("abc123"), joined, "the game was asked to join the server's one-time id");
        JsonObject sent = asks.get(0);
        assertEquals("pl_pl", sent.get("lang").getAsString());
        assertEquals("Hej", sent.getAsJsonArray("history").get(0).getAsJsonObject().get("q").getAsString());

        client.ask("Drugie", "pl_pl", List.of()).get(10, TimeUnit.SECONDS);
        assertEquals(1, logins.get(), "the ticket is kept");

        validTicket = "ticket-2";
        client.ask("Trzecie", "pl_pl", List.of()).get(10, TimeUnit.SECONDS);
        assertEquals(2, logins.get(), "a refused ticket means one fresh login");
        assertEquals(3, asks.size());

        List<AssistantClient.OpenQuestion> open = client.openQuestions().get(10, TimeUnit.SECONDS);
        assertEquals(1, open.size());
        assertTrue(open.get(0).disputed());
        assertEquals("Sto.", open.get(0).botAnswer());
    }

    @Test
    void errorsCarryTheServersCode() {
        askError = "player_limit";
        AssistantClient client = client("127.0.0.1:" + server.getAddress().getPort());
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> client.ask("x", "en_us", List.of()).get(10, TimeUnit.SECONDS));
        AssistantClient.Failure failure = assertInstanceOf(AssistantClient.Failure.class, error.getCause());
        assertEquals("player_limit", failure.code());

        ExecutionException none = assertThrows(ExecutionException.class,
                () -> client("").ask("x", "en_us", List.of()).get(10, TimeUnit.SECONDS));
        assertEquals("no_server", ((AssistantClient.Failure) none.getCause()).code());

        ExecutionException offline = assertThrows(ExecutionException.class,
                () -> client("127.0.0.1:1").ask("x", "en_us", List.of()).get(15, TimeUnit.SECONDS));
        assertEquals("offline", ((AssistantClient.Failure) offline.getCause()).code());
    }
}
