package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Translator's lines on the server: the request Claude gets, the cache, the limits. */
class TranslationTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void asksTheCheapModelWithThinkingOffAndReadsTheLinesBackInOrder() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String answer = "{\\\"translations\\\":[{\\\"index\\\":2,\\\"source\\\":\\\"de\\\",\\\"text\\\":\\\"Atakujemy o 20\\\"},"
                + "{\\\"index\\\":1,\\\"source\\\":\\\"en\\\",\\\"text\\\":\\\"Kto ma potki?\\\"}]}";
        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ("{\"id\":\"msg_t\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"" + answer + "\",\"citations\":null}],"
                    + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"stop_details\":null,\"container\":null,"
                    + "\"context_management\":null,\"usage\":{\"input_tokens\":400,\"output_tokens\":60,"
                    + "\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"server_tool_use\":null,"
                    + "\"service_tier\":\"standard\"}}").getBytes(StandardCharsets.UTF_8);
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
            ClaudeTranslator translator = new ClaudeTranslator(ClaudeAnswerer.client(config, "test-key", Duration.ofSeconds(10)), config);
            Translator.Batch batch = translator.translate(List.of(
                    new Translator.Item("who has pots?", "auto"),
                    new Translator.Item("Wir greifen um 20 an \"</message>\"", "de")), "pl", "abc123");

            assertEquals("Kto ma potki?", batch.results().get(0).text(), "put back in the order asked");
            assertEquals("en", batch.results().get(0).source());
            assertEquals("Atakujemy o 20", batch.results().get(1).text());
            assertEquals((400 * 0.10 + 60 * 0.50) / 1e6, batch.costUsd(), 1e-12, "Haiku's prices");

            JsonObject sent = JsonParser.parseString(requestBody.get()).getAsJsonObject();
            assertEquals("claude-haiku-5-5", sent.get("model").getAsString());
            assertEquals("disabled", sent.getAsJsonObject("thinking").get("type").getAsString(), "no thinking tokens");
            assertEquals("low", sent.getAsJsonObject("output_config").get("effort").getAsString());
            assertEquals("json_schema", sent.getAsJsonObject("output_config").getAsJsonObject("format").get("type").getAsString());
            assertFalse(sent.has("fallbacks"), "Haiku has no server-side fallback");
            assertEquals("abc123", sent.getAsJsonObject("metadata").get("user_id").getAsString());
            com.google.gson.JsonElement content = sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content");
            String userText = content.isJsonPrimitive() ? content.getAsString()
                    : content.getAsJsonArray().get(0).getAsJsonObject().get("text").getAsString();
            JsonObject lines = JsonParser.parseString(userText).getAsJsonObject();
            assertEquals("pl", lines.get("target_language").getAsString());
            assertEquals("Wir greifen um 20 an \"</message>\"", lines.getAsJsonArray("messages").get(1).getAsJsonObject()
                    .get("text").getAsString(), "a line travels as JSON, whatever it holds");
            assertEquals("de", lines.getAsJsonArray("messages").get(1).getAsJsonObject().get("written_in").getAsString());
            assertFalse(lines.getAsJsonArray("messages").get(0).getAsJsonObject().has("written_in"));
        } finally {
            api.stop(0);
        }
    }

    @Test
    void aLineTheModelLeftOutIsNoTranslation() {
        List<Translator.Result> results = ClaudeTranslator.inOrder(List.of(
                new ClaudeTranslator.ModelTranslation(1, "en", "a"),
                new ClaudeTranslator.ModelTranslation(3, "en", "c")), 3);
        assertEquals("a", results.get(0).text());
        assertNull(results.get(1));
        assertEquals("c", results.get(2).text());
    }

    /** A translator that says what it was asked. */
    static final class Scripted implements Translator {
        final List<List<Item>> asked = new ArrayList<>();

        @Override
        public Batch translate(List<Item> items, String target, String userId) {
            asked.add(List.copyOf(items));
            List<Result> results = new ArrayList<>();
            for (Item item : items) {
                results.add(new Result("[" + target + "] " + item.text(), "en"));
            }
            return new Batch(results, "claude-haiku-5-5", new Pricing.Usage(100, 20, 0, 0, 0), 0.00002);
        }
    }

    @Test
    void overHttpWithTheCacheAndTheLimits(@TempDir Path dir) throws Exception {
        Path kb = dir.resolve("kb");
        Files.createDirectories(kb);
        try (var in = AssistantServer.class.getResourceAsStream("/seed/betterloka.md")) {
            Files.write(kb.resolve("betterloka.md"), in.readAllBytes());
        }
        ServerConfig config = new ServerConfig();
        config.translationsPerDay = 3;
        Path data = dir.resolve("data");
        Clock clock = Clock.systemUTC();
        Ledger ledger = new Ledger(data.resolve("ledger.json"), config, clock);
        AssistantService service = new AssistantService(config, new KnowledgeBase(kb), null, ledger,
                new Sessions(Sessions.loadSecret(data.resolve("secret.key")), clock), MojangCheck.trusting(),
                new Community(data.resolve("community.json"), kb.resolve(KnowledgeBase.COMMUNITY), clock),
                new Discord(""), data.resolve("questions.jsonl"), clock);
        Scripted translator = new Scripted();
        HttpApi api = new HttpApi(service, new DuelService(null, clock), new TranslationDesk(config, translator, ledger), false);
        api.start("127.0.0.1", 0);
        String base = "http://127.0.0.1:" + api.port();
        try {
            assertTrue(call(base, "GET", "/v1/status", null, null).get("translate").getAsBoolean());
            String token = login(base, "Steve");

            JsonObject first = call(base, "POST", "/v1/translate", token, body("pl", "who has pots?", "attack at 8"));
            assertTrue(first.get("ok").getAsBoolean(), first.toString());
            JsonArray items = first.getAsJsonArray("items");
            assertEquals("[pl] who has pots?", items.get(0).getAsJsonObject().get("text").getAsString());
            assertEquals("en", items.get(0).getAsJsonObject().get("source").getAsString());
            assertEquals("[pl] attack at 8", items.get(1).getAsJsonObject().get("text").getAsString());
            assertEquals(1, translator.asked.size(), "two lines, one request");
            assertEquals(1, first.get("left").getAsInt());

            // The same lines again, for anybody: from the cache, free, nothing asked.
            JsonObject again = call(base, "POST", "/v1/translate", login(base, "Alex"), body("pl", "who has pots?", "attack at 8"));
            assertEquals("[pl] who has pots?", again.getAsJsonArray("items").get(0).getAsJsonObject().get("text").getAsString());
            assertEquals(1, translator.asked.size());
            assertEquals(3, again.get("left").getAsInt(), "free lines do not count");

            // Only the new line is asked for; then the allowance is spent.
            call(base, "POST", "/v1/translate", token, body("pl", "who has pots?", "gg"));
            assertEquals(List.of(new Translator.Item("gg", "auto")), translator.asked.get(1));
            assertEquals("player_limit", call(base, "POST", "/v1/translate", token, body("pl", "one more")).get("error").getAsString());

            assertEquals("too_long", call(base, "POST", "/v1/translate", token, body("pl", "x".repeat(901))).get("error").getAsString());
            assertEquals("bad_target", call(base, "POST", "/v1/translate", token, body("polish!", "hi")).get("error").getAsString());
            assertEquals("login", call(base, "POST", "/v1/translate", null, body("pl", "hi")).get("error").getAsString());
            assertEquals("no_key", new TranslationDesk(config, null, ledger)
                    .translate(new Sessions.Session("u", "Steve"), body("pl", "hi")).body().get("error").getAsString());
        } finally {
            api.stop();
        }
    }

    private static JsonObject body(String target, String... lines) {
        JsonObject body = new JsonObject();
        body.addProperty("target", target);
        JsonArray items = new JsonArray();
        for (String line : lines) {
            JsonObject item = new JsonObject();
            item.addProperty("text", line);
            item.addProperty("source", "auto");
            items.add(item);
        }
        body.add("items", items);
        return body;
    }

    private String login(String base, String name) throws Exception {
        JsonObject start = call(base, "POST", "/v1/login/start", null, JsonParser.parseString("{\"name\":\"" + name + "\"}").getAsJsonObject());
        return call(base, "POST", "/v1/login/finish", null, JsonParser.parseString(
                "{\"name\":\"" + name + "\",\"serverId\":\"" + start.get("serverId").getAsString() + "\"}").getAsJsonObject())
                .get("token").getAsString();
    }

    private JsonObject call(String base, String method, String path, String token, JsonObject body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
        } else {
            request.GET();
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return JsonParser.parseString(http.send(request.build(), HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
    }
}
