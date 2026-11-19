package com.betterloka.bot;

import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fallen-town alert from end to end, against a stand-in Loka and a stand-in Discord: the watch
 * as it runs, turn by turn, through a town falling, Loka answering 429, Discord refusing the
 * message, and restarts in between. Every alert that should reach Discord does, once.
 */
class FallenTownAlertSimulationTest {
    private static final String ROLE = "1234567890";

    /** Loka's API as far as the watch uses it: deleted towns twenty a page, territories per world. */
    static final class FakeLoka implements AutoCloseable {
        final HttpServer server;
        final TreeMap<String, String> deleted = new TreeMap<>();
        final Map<String, List<String[]>> territories = new ConcurrentHashMap<>();
        final AtomicInteger countReads = new AtomicInteger();
        final AtomicInteger pageReads = new AtomicInteger();
        final AtomicInteger territoryReads = new AtomicInteger();
        /** How many page requests are still to be answered 429, as Loka does when it is pressed. */
        final AtomicInteger refusePages = new AtomicInteger();

        FakeLoka() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            for (String world : LokaApi.CONQUEST_WORLDS) {
                territories.put(world, Collections.synchronizedList(new ArrayList<>()));
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        /** A territory {@code world #num} claimed by {@code townId}, its beacon at x 100·num, y 64, z -200. */
        void claim(String world, String num, String townId) {
            territories.get(world).add(new String[]{num, townId});
        }

        void delete(String id, String name) {
            synchronized (deleted) {
                deleted.put(id, name);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            URI uri = exchange.getRequestURI();
            Map<String, String> query = new LinkedHashMap<>();
            if (uri.getQuery() != null) {
                for (String pair : uri.getQuery().split("&")) {
                    String[] kv = pair.split("=", 2);
                    query.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
            }
            if (uri.getPath().equals("/towns/search/findDeleted")) {
                int size = Integer.parseInt(query.getOrDefault("size", "20"));
                int page = Integer.parseInt(query.getOrDefault("page", "0"));
                if (size == 1) {
                    countReads.incrementAndGet();
                } else {
                    pageReads.incrementAndGet();
                    if (refusePages.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        send(exchange, 429, "{}");
                        return;
                    }
                    // Loka serves twenty a page, whatever is asked for.
                    size = 20;
                }
                List<Map.Entry<String, String>> all;
                synchronized (deleted) {
                    all = new ArrayList<>(deleted.entrySet());
                }
                JsonArray towns = new JsonArray();
                for (int i = page * size; i < Math.min(all.size(), (page + 1) * size); i++) {
                    JsonObject town = new JsonObject();
                    town.addProperty("id", all.get(i).getKey());
                    town.addProperty("name", all.get(i).getValue());
                    town.addProperty("deleted", true);
                    town.add("members", new JsonObject());
                    towns.add(town);
                }
                JsonObject body = new JsonObject();
                JsonObject embedded = new JsonObject();
                embedded.add("towns", towns);
                body.add("_embedded", embedded);
                JsonObject pageInfo = new JsonObject();
                pageInfo.addProperty("size", size);
                pageInfo.addProperty("totalElements", all.size());
                pageInfo.addProperty("totalPages", (all.size() + size - 1) / size);
                pageInfo.addProperty("number", page);
                body.add("page", pageInfo);
                send(exchange, 200, body.toString());
                return;
            }
            if (uri.getPath().equals("/territories/search/findByWorld")) {
                territoryReads.incrementAndGet();
                String world = query.get("world");
                JsonArray list = new JsonArray();
                for (String[] claim : new ArrayList<>(territories.getOrDefault(world, List.of()))) {
                    JsonObject territory = new JsonObject();
                    territory.addProperty("id", world + "-" + claim[0]);
                    territory.addProperty("world", world);
                    territory.addProperty("num", claim[0]);
                    JsonObject tg = new JsonObject();
                    tg.addProperty("townId", claim[1]);
                    tg.addProperty("beacon", world + "," + (100 * Integer.parseInt(claim[0])) + ",64,-200");
                    territory.add("tg", tg);
                    list.add(territory);
                }
                JsonObject body = new JsonObject();
                JsonObject embedded = new JsonObject();
                embedded.add("territories", list);
                body.add("_embedded", embedded);
                send(exchange, 200, body.toString());
                return;
            }
            send(exchange, 404, "{}");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Discord's webhooks: every message taken is kept; a path can be made to refuse. */
    static final class FakeDiscord implements AutoCloseable {
        final HttpServer server;
        final Map<String, List<JsonObject>> taken = new ConcurrentHashMap<>();
        final Map<String, Integer> answer = new ConcurrentHashMap<>();

        FakeDiscord() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                int status = answer.getOrDefault(path, 204);
                if (status >= 200 && status < 300) {
                    taken.computeIfAbsent(path, key -> Collections.synchronizedList(new ArrayList<>()))
                            .add(com.google.gson.JsonParser.parseString(body).getAsJsonObject());
                }
                send(exchange, status, status == 204 ? "" : "{\"message\":\"Missing Permissions\",\"code\":50013}");
            });
            server.start();
        }

        String webhook(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        List<JsonObject> messages(String path) {
            return taken.getOrDefault(path, List.of());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @TempDir
    Path dir;
    private FakeLoka loka;
    private FakeDiscord discord;
    private HttpTransport transport;
    private BotConfig config;
    private BotStatus status;

    @BeforeEach
    void start() throws IOException {
        loka = new FakeLoka();
        discord = new FakeDiscord();
        transport = new HttpTransport();
        config = new BotConfig();
        config.webhookUrl = discord.webhook("/alerts");
        config.roleId = ROLE;
        config.stateFile = dir.resolve("betterloka-bot-state.json").toString();
        status = new BotStatus();
        // Loka as the bot first finds it: one town long dead and still on its territory, two alive.
        loka.delete("00a", "Old Ruin");
        loka.claim("west", "1", "00a");
        loka.claim("west", "2", "10b");
        loka.claim("south", "7", "20c");
        loka.claim("north", "4", "30d");
    }

    @AfterEach
    void stop() {
        transport.close();
        loka.close();
        discord.close();
    }

    /** The bot as it runs after a (re)start: state from its file, a watcher, a poster. */
    private final class Bot {
        final BotState state = new BotState(Path.of(config.stateFile));
        final FallenTownWatcher watcher;
        final DiscordPoster poster = new DiscordPoster(config);

        Bot() {
            state.load();
            watcher = new FallenTownWatcher(new LokaApi(transport, loka.url()), state, millis -> {
            });
        }

        void turn() throws InterruptedException {
            BetterLokaBot.cycle(watcher, state, poster, config, status);
        }
    }

    @Test
    void everyFallenTownReachesDiscordOnceWhateverHappensOnTheWay() throws Exception {
        // 1. First start: the town already dead is recorded quietly, nothing is posted.
        Bot bot = new Bot();
        bot.turn();
        assertEquals(0, discord.messages("/alerts").size(), "the backlog is not announced");
        assertFalse(bot.state.isFirstRun());

        // 2. Nothing happens: one small request, nothing read, nothing posted.
        int pages = loka.pageReads.get();
        int territories = loka.territoryReads.get();
        bot.turn();
        assertEquals(pages, loka.pageReads.get());
        assertEquals(territories, loka.territoryReads.get());
        assertEquals(0, discord.messages("/alerts").size());

        // 3. A town falls: one alert, with the role, the continent, the number and the coordinates.
        loka.delete("10b", "Brickstone");
        bot.turn();
        List<JsonObject> posted = discord.messages("/alerts");
        assertEquals(1, posted.size());
        String content = posted.get(0).get("content").getAsString();
        assertTrue(content.contains("<@&" + ROLE + ">"), content);
        assertTrue(content.contains("**Brickstone** has fallen"), content);
        JsonObject embed = posted.get(0).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Brickstone", embed.get("title").getAsString());
        JsonObject field = embed.getAsJsonArray("fields").get(0).getAsJsonObject();
        assertTrue(field.get("name").getAsString().endsWith("#2"), field.toString());
        assertTrue(field.get("value").getAsString().contains("200, 64, -200"), field.toString());
        assertEquals(ROLE, posted.get(0).getAsJsonObject("allowed_mentions").getAsJsonArray("roles").get(0).getAsString(),
                "the role is allowed to be pinged, or Discord shows it without notifying anybody");
        bot.turn();
        assertEquals(1, discord.messages("/alerts").size(), "and only once");

        // 4. A restart with nothing new: the territories are looked over once (to find whatever fell
        // while the bot was off), the deleted towns are not read again, and nothing is posted again.
        bot = new Bot();
        pages = loka.pageReads.get();
        territories = loka.territoryReads.get();
        bot.turn();
        assertEquals(pages, loka.pageReads.get(), "a restart does not read the deleted towns again");
        assertEquals(territories + LokaApi.CONQUEST_WORLDS.size(), loka.territoryReads.get(), "one look per world");
        bot.turn();
        assertEquals(territories + LokaApi.CONQUEST_WORLDS.size(), loka.territoryReads.get(), "and then only the count");
        assertEquals(1, discord.messages("/alerts").size());

        // 5. A town falls while Loka is refusing: a few 429s are waited out in the same turn...
        loka.delete("20c", "Castellan");
        loka.refusePages.set(2);
        bot.turn();
        assertEquals(2, discord.messages("/alerts").size(), "two 429s, then the alert");
        assertTrue(discord.messages("/alerts").get(1).get("content").getAsString().contains("Castellan"));

        // ...and when Loka refuses for the whole turn, the next turn sweeps again — the count the
        // failed sweep saw is not taken as seen. (Before 0.59 it was, and the alert was lost.)
        loka.delete("30d", "Dunmere");
        loka.refusePages.set(1000);
        bot.turn();
        assertEquals(2, discord.messages("/alerts").size(), "nothing could be read");
        loka.refusePages.set(0);
        bot.turn();
        assertEquals(3, discord.messages("/alerts").size(), "the next turn finds it");
        assertTrue(discord.messages("/alerts").get(2).get("content").getAsString().contains("Dunmere"));

        // 6. Discord refuses the message (a missing permission, say), then the bot restarts: the
        // alert waits in the state file and goes out once Discord takes it — once.
        loka.claim("west", "9", "40e");
        bot.turn();
        loka.delete("40e", "Eastwatch");
        discord.answer.put("/alerts", 403);
        bot.turn();
        assertEquals(3, discord.messages("/alerts").size());
        assertEquals(1, bot.state.pending().size(), "kept for later, not marked as told");
        bot = new Bot();
        assertEquals(1, bot.state.pending().size(), "still there after a restart");
        discord.answer.remove("/alerts");
        BetterLokaBot.deliver(bot.state, bot.poster, config, status, System.currentTimeMillis() + 60_000);
        assertEquals(4, discord.messages("/alerts").size());
        assertTrue(discord.messages("/alerts").get(3).get("content").getAsString().contains("Eastwatch"));
        assertEquals(0, bot.state.pending().size());
        BetterLokaBot.deliver(bot.state, bot.poster, config, status, System.currentTimeMillis() + 600_000);
        bot.turn();
        assertEquals(4, discord.messages("/alerts").size(), "and only once");

        // 7. A town falls while the bot is off: the first turn after the start announces it.
        loka.claim("south", "11", "50f");
        bot.turn();
        loka.delete("50f", "Fallowmere");
        bot = new Bot();
        bot.turn();
        assertEquals(5, discord.messages("/alerts").size());
        assertTrue(discord.messages("/alerts").get(4).get("content").getAsString().contains("Fallowmere"));
    }

    @Test
    void onePlaceRefusingDoesNotCostTheOthersOrRepeatTheirs() throws Exception {
        BotConfig.AlertTarget second = new BotConfig.AlertTarget();
        second.webhookUrl = discord.webhook("/other");
        config.alsoPostTo.add(second);
        Bot bot = new Bot();
        bot.turn();
        discord.answer.put("/other", 403);
        loka.delete("10b", "Brickstone");
        bot.turn();
        assertEquals(1, discord.messages("/alerts").size());
        assertEquals(0, discord.messages("/other").size());
        assertEquals(1, bot.state.pending().size(), "only the refused place waits");

        BetterLokaBot.deliver(bot.state, bot.poster, config, status, System.currentTimeMillis() + 60_000);
        assertEquals(1, bot.state.pending().size(), "still refused: tried again later, waiting longer");
        discord.answer.remove("/other");
        BetterLokaBot.deliver(bot.state, bot.poster, config, status, System.currentTimeMillis() + 3_600_000);
        assertEquals(1, discord.messages("/other").size());
        assertEquals(1, discord.messages("/alerts").size(), "the place that took it first is not sent it again");
    }

    @Test
    void theSimulationPostsATestAlertAndChangesNothing() throws Exception {
        Bot bot = new Bot();
        bot.turn();
        int announced = bot.state.announcedCount();
        BetterLokaBot.simulate(bot.watcher, bot.state, bot.poster, config, false);
        List<JsonObject> posted = discord.messages("/alerts");
        assertEquals(1, posted.size());
        String content = posted.get(0).get("content").getAsString();
        assertTrue(content.contains("TEST") && content.contains("Old Ruin"), content);
        assertFalse(content.contains("<@&"), "no ping unless asked for");
        BetterLokaBot.simulate(bot.watcher, bot.state, bot.poster, config, true);
        assertTrue(discord.messages("/alerts").get(1).get("content").getAsString().startsWith("<@&" + ROLE + ">"));
        assertEquals(announced, bot.state.announcedCount(), "nothing recorded");
        assertEquals(0, bot.state.pending().size());
        bot.turn();
        assertEquals(2, discord.messages("/alerts").size(), "and the watch carries on as it was");
    }
}
