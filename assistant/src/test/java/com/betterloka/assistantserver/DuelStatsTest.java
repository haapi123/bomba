package com.betterloka.assistantserver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The duel statistics: reports kept as two sides, a player's numbers worked out, bad reports refused. */
class DuelStatsTest {
    private static final long NOW = 1_763_000_000_000L;
    private static final String STEVE = new UUID(1, 1).toString();
    private static final String KNUCIOR = "7c5484d8-d44b-47c8-aa35-d86c6070680b";

    /** A fresh database in memory, as MySQL would take it. */
    private static DuelStore store() throws Exception {
        String url = "jdbc:h2:mem:duels" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DuelStore store = new DuelStore(() -> DriverManager.getConnection(url));
        store.createTables();
        store.createTables();
        return store;
    }

    static JsonObject report(long duelAt, String result, int theirHits, int theirSwings, int theirCrits, Integer ping) {
        String pingJson = ping == null ? "null" : "{\"avg\":" + ping + ",\"max\":" + (ping + 30) + ",\"spikes\":1,\"readings\":3}";
        return JsonParser.parseString("""
                {"mod":"0.58.0","duelAt":%d,"millis":42000,"result":"%s","ladder":"pot",
                 "opponent":{"uuid":"%s","name":"Knucior"},
                 "you":{"swings":20,"hits":10,"crits":4,"sweeps":1,"combo":3,"pots":5,"ping":{"avg":40,"max":45,"spikes":0,"readings":2}},
                 "them":{"swings":%d,"hits":%d,"crits":%d,"sweeps":0,"combo":%d,"pots":null,"ping":%s}}
                """.formatted(duelAt, result, KNUCIOR, theirSwings, theirHits, theirCrits, Math.min(4, theirHits), pingJson)).getAsJsonObject();
    }

    @Test
    void aReportIsKeptAsBothSidesAndOnlyOnce() throws Exception {
        DuelStore store = store();
        Sessions.Session steve = new Sessions.Session(STEVE.replace("-", ""), "Steve");
        assertTrue(store.save(DuelService.parse(steve, report(NOW - 60_000, "WON", 12, 16, 6, 80), NOW)));
        assertFalse(store.save(DuelService.parse(steve, report(NOW - 60_000, "WON", 12, 16, 6, 80), NOW)), "sent twice");
        assertTrue(store.save(DuelService.parse(steve, report(NOW - 30_000, "LOST", 8, 4, 2, 100), NOW)));

        DuelStore.Stats knucior = store.stats(KNUCIOR);
        assertEquals(2, knucior.observations());
        assertEquals(1, knucior.reporters());
        assertEquals(90, knucior.pingAvg());
        assertEquals(75, knucior.accuracy(), "12 of 16; the duel with fewer swings seen than hits is left out");
        assertEquals(40, knucior.critShare(), "8 crits of 20 hits");
        assertEquals(4.0, knucior.combo());
        assertEquals(0, knucior.potDuels());

        DuelStore.Stats self = store.stats(STEVE);
        assertEquals(2, self.observations());
        assertEquals(50, self.accuracy());
        assertEquals(5.0, self.pots());
        assertEquals(2, store.reportedSince(STEVE, NOW - 3_600_000L));
        assertEquals(0, store.reportedSince(KNUCIOR, NOW - 3_600_000L), "the opponent reported nothing");
        assertEquals(0, store.stats(new UUID(9, 9).toString()).observations());
        assertEquals(-1, store.stats(new UUID(9, 9).toString()).pingAvg());
    }

    @Test
    void reportsThatCannotBeADuelAreRefused() {
        Sessions.Session steve = new Sessions.Session(STEVE, "Steve");
        assertThrows(IllegalArgumentException.class, () -> DuelService.parse(steve, report(NOW - 5 * 86_400_000L, "WON", 1, 1, 0, 50), NOW),
                "days old");
        assertThrows(IllegalArgumentException.class, () -> DuelService.parse(steve, report(NOW, "MAYBE", 1, 1, 0, 50), NOW));
        assertThrows(IllegalArgumentException.class, () -> DuelService.parse(steve, report(NOW, "WON", 5, 9, 6, 50), NOW),
                "more crits than hits");
        assertThrows(IllegalArgumentException.class, () -> DuelService.parse(steve, report(NOW, "WON", 5000, 9000, 0, 50), NOW));
        assertThrows(IllegalArgumentException.class, () -> DuelService.parse(
                new Sessions.Session(KNUCIOR, "Knucior"), report(NOW, "WON", 1, 1, 0, 50), NOW), "against yourself");
        JsonObject badName = report(NOW, "WON", 1, 1, 0, 50);
        badName.getAsJsonObject("opponent").addProperty("name", "<script>");
        assertThrows(IllegalArgumentException.class, () -> DuelService.parse(steve, badName, NOW));
        JsonObject noPing = report(NOW, "UNKNOWN", 1, 1, 0, null);
        assertEquals(null, DuelService.parse(steve, noPing, NOW).them().pingAvg());
    }

    @Test
    void overHttpWithALogin(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:mem:http" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        DuelStore store = new DuelStore(() -> DriverManager.getConnection(url));
        store.createTables();
        Path kb = dir.resolve("kb");
        Files.createDirectories(kb);
        try (var in = AssistantServer.class.getResourceAsStream("/seed/betterloka.md")) {
            Files.write(kb.resolve("betterloka.md"), in.readAllBytes());
        }
        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        ServerConfig config = new ServerConfig();
        Path data = dir.resolve("data");
        AssistantService service = new AssistantService(config, new KnowledgeBase(kb), null,
                new Ledger(data.resolve("ledger.json"), config, Clock.systemUTC()),
                new Sessions(Sessions.loadSecret(data.resolve("secret.key")), Clock.systemUTC()), MojangCheck.trusting(),
                new Community(data.resolve("community.json"), kb.resolve(KnowledgeBase.COMMUNITY), Clock.systemUTC()),
                new Discord(""), data.resolve("questions.jsonl"), Clock.systemUTC());
        HttpApi api = new HttpApi(service, new DuelService(store, clock), false);
        api.start("127.0.0.1", 0);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + api.port();
        try {
            assertTrue(call(http, base, "GET", "/v1/status", null, null).get("duels").getAsBoolean());
            assertEquals("login", call(http, base, "POST", "/v1/duels/report", null, report(NOW, "WON", 1, 1, 0, 50)).get("error").getAsString());

            JsonObject start = call(http, base, "POST", "/v1/login/start", null, JsonParser.parseString("{\"name\":\"Steve\"}").getAsJsonObject());
            JsonObject finish = call(http, base, "POST", "/v1/login/finish", null, JsonParser.parseString(
                    "{\"name\":\"Steve\",\"serverId\":\"" + start.get("serverId").getAsString() + "\"}").getAsJsonObject());
            String token = finish.get("token").getAsString();

            JsonObject kept = call(http, base, "POST", "/v1/duels/report", token, report(NOW - 1000, "WON", 6, 10, 3, 70));
            assertTrue(kept.get("ok").getAsBoolean(), kept.toString());
            assertTrue(kept.get("kept").getAsBoolean());
            assertFalse(call(http, base, "POST", "/v1/duels/report", token, report(NOW - 1000, "WON", 6, 10, 3, 70)).get("kept").getAsBoolean());
            assertEquals("bad_report", call(http, base, "POST", "/v1/duels/report", token, report(NOW, "WON", 6, 10, 9, 70)).get("error").getAsString());

            JsonObject stats = call(http, base, "POST", "/v1/duels/player", token,
                    JsonParser.parseString("{\"uuid\":\"" + KNUCIOR.replace("-", "") + "\"}").getAsJsonObject());
            assertEquals(1, stats.get("observations").getAsInt());
            assertEquals(70, stats.get("ping").getAsInt());
            assertEquals(60, stats.get("accuracy").getAsInt());
            assertEquals(50, stats.get("critShare").getAsInt());
            assertEquals("bad_uuid", call(http, base, "POST", "/v1/duels/player", token,
                    JsonParser.parseString("{\"uuid\":\"nope\"}").getAsJsonObject()).get("error").getAsString());

            for (int i = 0; i < DuelService.PER_HOUR - 1; i++) {
                assertTrue(call(http, base, "POST", "/v1/duels/report", token, report(NOW - 2000 - i, "LOST", 1, 1, 0, 50)).get("ok").getAsBoolean());
            }
            assertEquals("slow_down", call(http, base, "POST", "/v1/duels/report", token, report(NOW - 9000, "LOST", 1, 1, 0, 50)).get("error").getAsString());
        } finally {
            api.stop();
        }
    }

    @Test
    void withoutADatabaseItSaysSo(@TempDir Path dir) {
        DuelService off = new DuelService(null, Clock.systemUTC());
        assertFalse(off.enabled());
        assertEquals("duels_off", off.player(new JsonObject()).body().get("error").getAsString());
        DuelDatabase.Settings settings = DuelDatabase.load(dir);
        assertFalse(settings.configured());
        assertTrue(Files.exists(dir.resolve(DuelDatabase.FILE)), "an empty file to fill in");
        settings.host = "db.example.net:3307";
        settings.name = "s1_betterloka";
        settings.user = "u1_abc";
        assertTrue(settings.configured());
        assertTrue(settings.url().startsWith("jdbc:mariadb://db.example.net:3307/s1_betterloka?"), settings.url());
        assertFalse(settings.url().contains("u1_abc"), "the user is not in the address");
    }

    private static JsonObject call(HttpClient http, String base, String method, String path, String token, JsonObject body) throws Exception {
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
