package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server end to end over HTTP, and the Claude request it sends, against a stand-in API. */
class ServerFlowTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** An answerer the test scripts. */
    static final class ScriptedAnswerer implements Answerer {
        final List<Question> asked = new ArrayList<>();
        Status next = Status.ANSWERED;

        @Override
        public Reply answer(Question question) {
            asked.add(question);
            Pricing.Usage usage = new Pricing.Usage(100, 50, 2000, 0, 0);
            return new Reply(next == Status.UNKNOWN ? "Nie wiem tego jeszcze." : "Warden jest na Balaku.", next,
                    "claude-opus-5-5", usage, 0.01);
        }
    }

    @Test
    void playerLogsInAsksTeachesAndAnAdminApproves(@TempDir Path dir) throws Exception {
        ServerFlowTestHarness harness = new ServerFlowTestHarness(dir);
        harness.config.admins = List.of("Admin");
        harness.config.questionsPerDay = 3;
        ScriptedAnswerer answerer = new ScriptedAnswerer();
        harness.start(answerer, (name, serverId) -> name.equals("Liar") ? Optional.empty()
                : MojangCheck.trusting().hasJoined(name, serverId));
        try {
            assertEquals(true, get(harness, "/v1/status").get("ok").getAsBoolean());

            // Mojang does not vouch for this one.
            JsonObject start = post(harness, "/v1/login/start", null, "{\"name\":\"Liar\"}");
            JsonObject refused = post(harness, "/v1/login/finish", null,
                    "{\"name\":\"Liar\",\"serverId\":\"" + start.get("serverId").getAsString() + "\"}");
            assertEquals("not_verified", refused.get("error").getAsString());

            String player = login(harness, "Steve");
            assertEquals("login", post(harness, "/v1/ask", "garbage", "{\"question\":\"x\"}").get("error").getAsString());

            JsonObject answer = post(harness, "/v1/ask", player,
                    "{\"question\":\"Gdzie jest Warden?\",\"lang\":\"pl_pl\",\"history\":[{\"q\":\"Hej\",\"a\":\"Cześć\"}]}");
            assertTrue(answer.get("ok").getAsBoolean(), answer.toString());
            assertEquals("answered", answer.get("status").getAsString());
            assertEquals(2, answer.get("questionsLeft").getAsInt());
            Answerer.Question sent = answerer.asked.get(0);
            assertEquals("pl_pl", sent.language());
            assertEquals(1, sent.history().size());
            assertTrue(sent.knowledge().contains("Warden boss"), "the seed knowledge base travels with the question");
            assertFalse(sent.userId().contains("Steve"), "the API gets an opaque id, not the name");

            // A thumbs-down opens the question for players to answer; one rating per answer.
            String answerId = answer.get("id").getAsString();
            assertTrue(post(harness, "/v1/feedback", player, "{\"id\":\"" + answerId + "\",\"good\":false}").get("ok").getAsBoolean());
            assertEquals("unknown_answer", post(harness, "/v1/feedback", player, "{\"id\":\"" + answerId + "\",\"good\":false}")
                    .get("error").getAsString());

            answerer.next = Answerer.Status.UNKNOWN;
            post(harness, "/v1/ask", player, "{\"question\":\"Ile kosztuje town?\"}");
            JsonArray open = post(harness, "/v1/open", player, "{}").getAsJsonArray("questions");
            assertEquals(2, open.size());

            String tooLong = "x".repeat(harness.config.maxQuestionChars + 1);
            assertEquals("too_long", post(harness, "/v1/ask", player, "{\"question\":\"" + tooLong + "\"}").get("error").getAsString());
            post(harness, "/v1/ask", player, "{\"question\":\"Trzecie\"}");
            assertEquals("player_limit", post(harness, "/v1/ask", player, "{\"question\":\"Czwarte\"}").get("error").getAsString());

            // A player proposes an answer; only an admin sees and approves it.
            String openId = null;
            for (int i = 0; i < open.size(); i++) {
                if (open.get(i).getAsJsonObject().get("question").getAsString().startsWith("Ile")) {
                    openId = open.get(i).getAsJsonObject().get("id").getAsString();
                }
            }
            assertNotNull(openId);
            JsonObject proposal = post(harness, "/v1/propose", player,
                    "{\"openId\":\"" + openId + "\",\"answer\":\"Założenie townu kosztuje 2000 diamentów.\"}");
            assertTrue(proposal.get("ok").getAsBoolean(), proposal.toString());
            assertEquals("not_admin", post(harness, "/v1/admin/pending", player, "{}").get("error").getAsString());

            String admin = login(harness, "Admin");
            JsonArray pending = post(harness, "/v1/admin/pending", admin, "{}").getAsJsonArray("proposals");
            assertEquals(1, pending.size());
            assertEquals("Steve", pending.get(0).getAsJsonObject().get("by").getAsString());
            JsonObject approved = post(harness, "/v1/admin/review", admin,
                    "{\"id\":\"" + proposal.get("id").getAsString() + "\",\"approve\":true}");
            assertTrue(approved.get("ok").getAsBoolean());
            String community = Files.readString(dir.resolve("kb").resolve(KnowledgeBase.COMMUNITY));
            assertTrue(community.contains("Ile kosztuje town?") && community.contains("2000 diamentów"), community);
            // "Trzecie" was not known either; the answered one is gone.
            assertEquals(2, post(harness, "/v1/open", player, "{}").getAsJsonArray("questions").size());

            // Admins are not held to the daily question limit.
            answerer.next = Answerer.Status.ANSWERED;
            for (int i = 0; i < 4; i++) {
                assertTrue(post(harness, "/v1/ask", admin, "{\"question\":\"Pytanie " + i + "\"}").get("ok").getAsBoolean());
            }
            // The newest question sees the approved answer.
            assertTrue(answerer.asked.get(answerer.asked.size() - 1).knowledge().contains("2000 diamentów"));
            List<String> log = Files.readAllLines(dir.resolve("data").resolve("questions.jsonl"));
            assertTrue(log.size() >= 7, "every question and rating is logged for the owner");
        } finally {
            harness.stop();
        }
    }

    @Test
    void stopsBeforeTheBudgetRunsOut(@TempDir Path dir) throws Exception {
        ServerFlowTestHarness harness = new ServerFlowTestHarness(dir);
        harness.config.monthlyBudgetUsd = 0.015;
        // No share per day here: this is about the month's ceiling.
        harness.config.dailyBurst = 0;
        harness.start(new ScriptedAnswerer(), MojangCheck.trusting());
        try {
            String player = login(harness, "Steve");
            assertTrue(post(harness, "/v1/ask", player, "{\"question\":\"Pierwsze\"}").get("ok").getAsBoolean());
            assertTrue(post(harness, "/v1/ask", player, "{\"question\":\"Drugie\"}").get("ok").getAsBoolean());
            assertEquals("budget_month", post(harness, "/v1/ask", player, "{\"question\":\"Trzecie\"}").get("error").getAsString());
        } finally {
            harness.stop();
        }
    }

    @Test
    void sendsCachedKnowledgeAndReadsTheStructuredAnswer(@TempDir Path dir) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> betaHeader = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>(message("end_turn",
                "{\\\"answer\\\":\\\"Warden jest na Balaku, 3870 100 2482.\\\",\\\"status\\\":\\\"ANSWERED\\\"}"));
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            betaHeader.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        api.start();
        try {
            ServerConfig config = new ServerConfig();
            config.apiBaseUrl = "http://127.0.0.1:" + api.getAddress().getPort();
            ClaudeAnswerer answerer = new ClaudeAnswerer(ClaudeAnswerer.client(config, "test-key", Duration.ofSeconds(10)), config);
            Answerer.Reply answer = answerer.answer(new Answerer.Question("Warden boss: 3870 100 2482", List.of(
                    new Answerer.Turn("Hej", "Cześć!")), "Gdzie jest Warden?", "pl_pl", "abc123"));

            assertEquals("Warden jest na Balaku, 3870 100 2482.", answer.text());
            assertEquals(Answerer.Status.ANSWERED, answer.status());
            // 1000 fresh, 20000 read from the cache, 300 written, 200 out, on the default model.
            assertEquals((1000 * 4.0 + 20_000 * 0.20 + 300 * 4.0 * 1.25 + 200 * 20.0) / 1e6, answer.costUsd(), 1e-12);

            JsonObject sent = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals("claude-opus-5-5", sent.get("model").getAsString());
            assertEquals("default", sent.get("fallbacks").getAsString(), "a declined question is retried on another model");
            assertTrue(betaHeader.get().contains("server-side-fallback-2026-07-01"), betaHeader.get());
            JsonArray system = sent.getAsJsonArray("system");
            assertEquals(2, system.size());
            assertFalse(system.get(0).getAsJsonObject().has("cache_control"));
            JsonObject knowledge = system.get(1).getAsJsonObject();
            assertTrue(knowledge.get("text").getAsString().contains("<knowledge_base>"));
            assertEquals("ephemeral", knowledge.getAsJsonObject("cache_control").get("type").getAsString(),
                    "the knowledge base is cached");
            assertEquals("low", sent.getAsJsonObject("output_config").get("effort").getAsString());
            assertEquals("json_schema", sent.getAsJsonObject("output_config").getAsJsonObject("format").get("type").getAsString());
            JsonArray messages = sent.getAsJsonArray("messages");
            assertEquals(3, messages.size(), "one earlier turn, then the question");
            assertTrue(messages.get(2).toString().contains("Game language: pl_pl"));
            assertEquals("abc123", sent.getAsJsonObject("metadata").get("user_id").getAsString());

            // A refusal is not an answer.
            reply.set(message("refusal", ""));
            assertEquals(Answerer.Status.REFUSED, answerer.answer(new Answerer.Question("", List.of(), "x", "en_us", "u")).status());
            // Neither is a cut-off one.
            reply.set(message("max_tokens", "{\\\"answer\\\":\\\"Half"));
            assertThrows(Answerer.Failure.class, () -> answerer.answer(new Answerer.Question("", List.of(), "x", "en_us", "u")));
        } finally {
            api.stop(0);
        }
    }

    private static String message(String stopReason, String text) {
        String content = text.isEmpty() ? "[]" : "[{\"type\":\"text\",\"text\":\"" + text + "\",\"citations\":null}]";
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":" + content + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"stop_details\":null,\"container\":null,\"context_management\":null,"
                + "\"usage\":{\"input_tokens\":1000,\"output_tokens\":200,\"cache_read_input_tokens\":20000,"
                + "\"cache_creation_input_tokens\":300,\"cache_creation\":{\"ephemeral_5m_input_tokens\":300,"
                + "\"ephemeral_1h_input_tokens\":0},\"server_tool_use\":null,\"service_tier\":\"standard\"}}";
    }

    private String login(ServerFlowTestHarness harness, String name) throws Exception {
        JsonObject start = post(harness, "/v1/login/start", null, "{\"name\":\"" + name + "\"}");
        JsonObject finish = post(harness, "/v1/login/finish", null,
                "{\"name\":\"" + name + "\",\"serverId\":\"" + start.get("serverId").getAsString() + "\"}");
        assertTrue(finish.get("ok").getAsBoolean(), finish.toString());
        return finish.get("token").getAsString();
    }

    private JsonObject get(ServerFlowTestHarness harness, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(harness.base() + path)).GET().build();
        return JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
    }

    private JsonObject post(ServerFlowTestHarness harness, String path, String token, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(harness.base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return JsonParser.parseString(http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
    }

    /** A whole server in a temp folder, on a free port. */
    static final class ServerFlowTestHarness {
        final Path dir;
        final ServerConfig config = new ServerConfig();
        private HttpApi api;

        ServerFlowTestHarness(Path dir) {
            this.dir = dir;
            config.bind = "127.0.0.1";
        }

        void start(Answerer answerer, MojangCheck mojang) throws Exception {
            Path kb = dir.resolve("kb");
            Files.createDirectories(kb);
            try (var in = AssistantServer.class.getResourceAsStream("/seed/betterloka.md")) {
                Files.write(kb.resolve("betterloka.md"), in.readAllBytes());
            }
            java.time.Clock clock = java.time.Clock.systemUTC();
            Path data = dir.resolve("data");
            AssistantService service = new AssistantService(config, new KnowledgeBase(kb), answerer,
                    new Ledger(data.resolve("ledger.json"), config, clock),
                    new Sessions(Sessions.loadSecret(data.resolve("secret.key")), clock), mojang,
                    new Community(data.resolve("community.json"), kb.resolve(KnowledgeBase.COMMUNITY), clock),
                    new Discord(""), data.resolve("questions.jsonl"), clock);
            api = new HttpApi(service, false);
            api.start("127.0.0.1", 0);
        }

        String base() {
            return "http://127.0.0.1:" + api.port();
        }

        void stop() {
            api.stop();
        }
    }
}
