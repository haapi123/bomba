package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Discord questions channel against a stand-in Discord that records every call. */
class DiscordQuestionsTest {
    private static final String CHANNEL = "111";
    private static final String ANSWERER = "222";
    private static final String APPROVER = "333";

    record Call(String method, String path, JsonObject body) {
    }

    private HttpServer discord;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextMessage = new AtomicInteger(1000);

    @TempDir
    Path dir;
    private ServerConfig config;
    private Community community;
    private DiscordQuestions questions;

    @BeforeEach
    void start() throws IOException {
        discord = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        discord.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    body.isBlank() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject()));
            boolean create = "POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/messages");
            byte[] reply = (create ? "{\"id\":\"m" + nextMessage.getAndIncrement() + "\"}" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        discord.start();

        config = new ServerConfig();
        config.discordQuestions.channelId = CHANNEL;
        config.discordQuestions.answerRoleIds = List.of(ANSWERER);
        config.discordQuestions.approveRoleIds = List.of(APPROVER);
        config.discordQuestions.pingRoleId = "444";
        Clock clock = Clock.systemUTC();
        community = new Community(dir.resolve("community.json"), dir.resolve("kb").resolve("community.md"), clock);
        questions = new DiscordQuestions(config, community, new Ledger(dir.resolve("ledger.json"), config, clock),
                new KnowledgeBase(dir.resolve("kb")),
                new DiscordRest("http://127.0.0.1:" + discord.getAddress().getPort(), "token"),
                dir.resolve("discord-questions.json"));
    }

    @AfterEach
    void stop() {
        questions.stop();
        discord.stop(0);
    }

    @Test
    void aQuestionIsAnsweredOnDiscordAndApprovedThere() throws Exception {
        questions.start();
        community.noteOpen("Gdzie jest `Warden`? @everyone", "unknown", null);
        questions.flush();

        Call posted = calls.get(0);
        assertEquals("POST", posted.method());
        assertEquals("/channels/" + CHANNEL + "/messages", posted.path());
        String description = embed(posted.body()).get("description").getAsString();
        assertTrue(description.startsWith("```") && description.contains("'Warden'"),
                "the player's text sits inert in a code block, backticks defused: " + description);
        assertEquals("<@&444>", posted.body().get("content").getAsString(), "the new question pings the chosen role");
        assertEquals(0, posted.body().getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size(),
                "and nothing else: @everyone in a question pings nobody");
        assertEquals(List.of("bla:answer:q1", "bla:dismiss:q1"), buttons(posted.body()));

        // Asked again: the same message is updated, not a new one posted, and nobody is pinged twice.
        community.noteOpen("gdzie jest warden @everyone", "unknown", null);
        questions.flush();
        Call edited = calls.get(1);
        assertEquals("PATCH", edited.method());
        assertEquals("/channels/" + CHANNEL + "/messages/m1000", edited.path());
        assertFalse(edited.body().has("content"));

        // Somebody without a role cannot even open the form.
        assertEquals("Nie masz rangi, która może tu odpowiadać.", content(questions.decide(click("bla:answer:q1", "9"), "bla:answer:q1")));
        // The same click from another channel counts for nothing.
        JsonObject elsewhere = click("bla:answer:q1", ANSWERER);
        elsewhere.addProperty("channel_id", "999");
        assertEquals("Ten przycisk działa tylko na kanale asystenta.", content(questions.decide(elsewhere, "bla:answer:q1")));

        JsonObject form = questions.decide(click("bla:answer:q1", ANSWERER), "bla:answer:q1");
        assertEquals(9, form.get("type").getAsInt(), "an answerer gets the form");
        assertEquals("bla:answer-form:q1", form.getAsJsonObject("data").get("custom_id").getAsString());
        assertEquals("Gdzie jest `Warden`? @everyone", DiscordQuestions.formValues(form.getAsJsonObject("data")).get("question"),
                "with the question filled in");

        // The answerer's answer waits for approval.
        JsonObject sent = submit("bla:answer-form:q1", ANSWERER, "Gdzie jest Warden?", "W Deep Dark, Dark Reaches na Balaku.");
        assertEquals("📝 Dzięki! Twoja odpowiedź czeka na zatwierdzenie.", content(questions.decide(sent, "bla:answer-form:q1")));
        assertEquals(1, community.pending().size());
        assertFalse(Files.exists(dir.resolve("kb").resolve("community.md")), "nothing in the knowledge base yet");
        questions.flush();
        Call proposal = calls.get(calls.size() - 1);
        assertEquals("POST", proposal.method());
        assertEquals(List.of("bla:approve:p2", "bla:edit:p2", "bla:reject:p2"), buttons(proposal.body()));

        // An answerer cannot approve, an approver can.
        assertEquals("To mogą zrobić tylko osoby z rangą od zatwierdzania.",
                content(questions.decide(click("bla:approve:p2", ANSWERER), "bla:approve:p2")));
        int before = calls.size();
        assertEquals("✅ Zatwierdzone. Asystent zna to od teraz.",
                content(questions.decide(click("bla:approve:p2", APPROVER), "bla:approve:p2")));
        String knowledge = Files.readString(dir.resolve("kb").resolve("community.md"));
        assertTrue(knowledge.contains("W Deep Dark, Dark Reaches na Balaku."), knowledge);
        assertTrue(knowledge.contains("written by Ann (Discord)"), knowledge);
        assertTrue(community.open(10).isEmpty(), "the open question is closed");

        questions.flush();
        List<Call> after = calls.subList(before, calls.size());
        assertEquals(2, after.size(), "the proposal and the question are both updated: " + after);
        assertEquals("/channels/" + CHANNEL + "/messages/m1001", after.get(0).path());
        assertEquals(0, after.get(0).body().getAsJsonArray("components").size(), "the buttons come off");
        assertTrue(embed(after.get(0).body()).get("title").getAsString().contains("zatwierdzona przez: Bob (Discord)"));
        assertEquals("/channels/" + CHANNEL + "/messages/m1000", after.get(1).path());
        assertTrue(embed(after.get(1).body()).get("title").getAsString().startsWith("✅ Odpowiedziano"));

        // The proposal is gone now; clicking again says so.
        assertEquals("Ta propozycja została już rozpatrzona.",
                content(questions.decide(click("bla:reject:p2", APPROVER), "bla:reject:p2")));
    }

    @Test
    void anApproversAnswerGoesStraightInAndAdministratorsCountAsApprovers() throws Exception {
        questions.start();
        community.noteOpen("Ile kosztuje town?", "unknown", null);
        community.noteOpen("Co to jest Zyre?", "unknown", null);
        questions.flush();

        assertEquals("✅ Dodane do bazy wiedzy. Asystent zna to od teraz.", content(questions.decide(
                submit("bla:answer-form:q1", APPROVER, "", "520 shardów dziennie za 1 terytorium."), "bla:answer-form:q1")));
        assertTrue(Files.readString(dir.resolve("kb").resolve("community.md")).contains("## Ile kosztuje town?"),
                "an empty question field keeps the player's question");

        // A server administrator has no configured role but may still dismiss.
        JsonObject admin = click("bla:dismiss:q2", "9");
        admin.getAsJsonObject("member").addProperty("permissions", "8");
        assertEquals("🗑️ Pytanie odrzucone.", content(questions.decide(admin, "bla:dismiss:q2")));
        assertEquals("To pytanie jest już zamknięte.", content(questions.decide(click("bla:answer:q2", ANSWERER), "bla:answer:q2")));
        questions.flush();
        Call closed = calls.get(calls.size() - 1);
        assertTrue(embed(closed.body()).get("title").getAsString().contains("odrzucone przez: Ann (Discord)"), closed.toString());
    }

    @Test
    void whatHappensInTheGameShowsOnDiscordAndOldQuestionsArePostedOnce() throws Exception {
        // Before the channel was set up: one open question and one proposal from the game.
        community.noteOpen("Jak zrobić Recall Stone?", "unknown", null);
        Community.Proposal fromGame = community.propose("q1", "Jak zrobić Recall Stone?", "Przepis w /rb.", "uuid", "Steve");
        questions.start();
        questions.flush();
        assertEquals(2, calls.size(), "the backlog is posted: " + calls);
        assertFalse(calls.get(0).body().has("content"), "without pinging anyone");

        community.review(fromGame.id, true, null, null, "Admin");
        questions.flush();
        assertEquals("PATCH", calls.get(2).method());
        assertTrue(embed(calls.get(2).body()).get("title").getAsString().contains("Steve"));

        // A restart does not post them again.
        DiscordQuestions again = new DiscordQuestions(config, community,
                new Ledger(dir.resolve("ledger.json"), config, Clock.systemUTC()), new KnowledgeBase(dir.resolve("kb")),
                new DiscordRest("http://127.0.0.1:" + discord.getAddress().getPort(), "token"), dir.resolve("discord-questions.json"));
        int before = calls.size();
        again.start();
        again.flush();
        again.stop();
        assertEquals(before, calls.size());
    }

    @Test
    void clicksArriveThroughHandleAndAreAnsweredWithTheirCallback() throws Exception {
        questions.start();
        community.noteOpen("Gdzie jest market?", "unknown", null);
        questions.flush();
        JsonObject interaction = click("bla:answer:q1", ANSWERER);
        assertTrue(questions.handle(interaction));
        JsonObject slash = new JsonObject();
        slash.addProperty("type", 2);
        assertFalse(questions.handle(slash), "slash commands are the town bot's");
        questions.flush();
        Call callback = calls.get(calls.size() - 1);
        assertEquals("/interactions/i1/t1/callback", callback.path());
        assertEquals(9, callback.body().get("type").getAsInt());
    }

    @Test
    void formValuesAreFoundWhereverDiscordPutsThem() {
        JsonObject data = JsonParser.parseString("""
                {"components":[
                  {"type":1,"components":[{"type":4,"custom_id":"question","value":"Q"}]},
                  {"type":18,"component":{"type":4,"custom_id":"answer","value":"A"}}]}""").getAsJsonObject();
        assertEquals(Map.of("question", "Q", "answer", "A"), DiscordQuestions.formValues(data));
    }

    private static JsonObject click(String customId, String role) {
        JsonObject interaction = JsonParser.parseString("""
                {"id":"i1","token":"t1","type":3,"channel_id":"%s",
                 "member":{"roles":["%s"],"permissions":"0","user":{"id":"u-%s","username":"%s"}},
                 "data":{"custom_id":"%s"}}""".formatted(CHANNEL, role, role,
                role.equals(APPROVER) ? "Bob" : "Ann", customId)).getAsJsonObject();
        return interaction;
    }

    private static JsonObject submit(String customId, String role, String question, String answer) {
        JsonObject interaction = click(customId, role);
        interaction.addProperty("type", 5);
        JsonObject data = interaction.getAsJsonObject("data");
        JsonArray rows = new JsonArray();
        rows.add(row("question", question));
        rows.add(row("answer", answer));
        data.add("components", rows);
        return interaction;
    }

    private static JsonObject row(String id, String value) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", id);
        input.addProperty("value", value);
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        row.add("components", inputs);
        return row;
    }

    private static String content(JsonObject response) {
        assertEquals(4, response.get("type").getAsInt(), response.toString());
        assertEquals(64, response.getAsJsonObject("data").get("flags").getAsInt(), "only the clicker sees it");
        return response.getAsJsonObject("data").get("content").getAsString();
    }

    private static JsonObject embed(JsonObject message) {
        return message.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static List<String> buttons(JsonObject message) {
        return message.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("components").asList().stream()
                .map(button -> button.getAsJsonObject().get("custom_id").getAsString())
                .toList();
    }
}
