package com.betterloka.bot;

import com.betterloka.api.model.BattleZone;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live stat channels: their names, their setup and removal, keeping them current, and their numbers. */
class StatChannelsTest {
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final UUID ACE = UUID.fromString("7c5484d8-d44b-47c8-aa35-d86c6070680b");
    private static final UUID BRUISER = UUID.fromString("6eb04a24-592f-4490-ba9d-8d98fb40610e");

    private static LiveStats.Snapshot snapshot(int online) {
        return new LiveStats.Snapshot(new LiveStats.Players(online, 1000, false), new LiveStats.Fights(2, 3),
                new LiveStats.Named("Knucior", 57), new LiveStats.Named("Bruiser", 45_230.5),
                new LiveStats.Ranked("Knucior", "Diamond III"), YearMonth.of(2026, 11));
    }

    @Test
    void namesSayTheNumbers() {
        LiveStats.Snapshot stats = snapshot(49);
        assertEquals("🟢 Online: 49", StatChannels.name(StatChannels.Tile.ONLINE, stats));
        assertEquals("⚔️ Fights now: 2 · 3 declared", StatChannels.name(StatChannels.Tile.FIGHTS, stats));
        assertEquals("🗡️ Most kills (Nov): Knucior · 57", StatChannels.name(StatChannels.Tile.KILLS, stats));
        assertEquals("💥 Most damage (Nov): Bruiser · 45.2k", StatChannels.name(StatChannels.Tile.DAMAGE, stats));
        assertEquals("🏆 #1 Potion: Knucior · Diamond III", StatChannels.name(StatChannels.Tile.POTION, stats));
    }

    @Test
    void unknownKeepsTheOldNameAndEmptyIsADash() {
        LiveStats.Snapshot first = new LiveStats.Snapshot(null, new LiveStats.Fights(0, 0), new LiveStats.Named("", 0),
                null, null, YearMonth.of(2026, 12));
        assertNull(StatChannels.name(StatChannels.Tile.ONLINE, first), "not read yet: no rename");
        assertEquals("⚔️ Fights now: 0", StatChannels.name(StatChannels.Tile.FIGHTS, first));
        assertEquals("🗡️ Most kills (Dec): —", StatChannels.name(StatChannels.Tile.KILLS, first), "a month just begun");
        assertNull(StatChannels.name(StatChannels.Tile.DAMAGE, first));
        LiveStats.Snapshot down = new LiveStats.Snapshot(new LiveStats.Players(0, 0, true), null, null, null, null, null);
        assertEquals("🔴 Loka offline", StatChannels.name(StatChannels.Tile.ONLINE, down));
    }

    @Test
    void bigNumbersAreShort() {
        assertEquals("950", StatChannels.compact(950.4));
        assertEquals("45.2k", StatChannels.compact(45_230));
        assertEquals("12k", StatChannels.compact(12_000));
        assertEquals("176k", StatChannels.compact(176_127));
        assertEquals("1.3M", StatChannels.compact(1_310_000));
    }

    private static JsonObject statsCommand(String sub, String category) {
        JsonObject option = new JsonObject();
        option.addProperty("type", 1);
        option.addProperty("name", sub);
        if (category != null) {
            JsonObject value = new JsonObject();
            value.addProperty("name", "category");
            value.addProperty("value", category);
            JsonArray values = new JsonArray();
            values.add(value);
            option.add("options", values);
        }
        JsonArray options = new JsonArray();
        options.add(option);
        JsonObject data = new JsonObject();
        data.addProperty("name", "stats");
        data.add("options", options);
        JsonObject interaction = new JsonObject();
        interaction.addProperty("type", 2);
        interaction.addProperty("id", "i1");
        interaction.addProperty("token", "t1");
        interaction.addProperty("application_id", "app");
        interaction.addProperty("guild_id", "g1");
        interaction.add("data", data);
        return interaction;
    }

    @Test
    void setupMakesALockedCategoryOfFiveAndRemoveDeletesThem(@TempDir Path dir) throws Exception {
        ServerCommandsTest.FakeDiscord discord = new ServerCommandsTest.FakeDiscord();
        CommunityStore store = new CommunityStore(dir.resolve("community.json"));
        StatChannels channels = new StatChannels(discord, store, () -> snapshot(49), 10, Runnable::run);

        assertTrue(channels.handle(statsCommand("setup", null)));
        assertEquals(6, discord.createdChannels.size(), "a category and five channels");
        JsonObject category = discord.createdChannels.get("c1");
        assertEquals(4, category.get("type").getAsInt());
        assertEquals(StatChannels.DEFAULT_CATEGORY, category.get("name").getAsString());
        JsonObject online = discord.createdChannels.get("c2");
        assertEquals(2, online.get("type").getAsInt(), "a voice channel");
        assertEquals("c1", online.get("parent_id").getAsString());
        assertEquals("🟢 Online: 49", online.get("name").getAsString(), "made with today's numbers");
        JsonObject lock = online.getAsJsonArray("permission_overwrites").get(0).getAsJsonObject();
        assertEquals("g1", lock.get("id").getAsString(), "@everyone");
        assertEquals(String.valueOf(StatChannels.CONNECT), lock.get("deny").getAsString(), "nobody can join");
        assertTrue(discord.edits.get(0).get("content").getAsString().startsWith("✅"));

        // Kept across a restart.
        CommunityStore reopened = new CommunityStore(dir.resolve("community.json"));
        assertEquals(5, reopened.statChannels().get("g1").channels.size());
        assertEquals("c1", reopened.statChannels().get("g1").category);

        // A second setup does not make another set.
        channels.handle(statsCommand("setup", null));
        assertEquals(6, discord.createdChannels.size());
        assertTrue(discord.edits.get(1).get("content").getAsString().contains("/stats remove"));

        channels.handle(statsCommand("remove", null));
        assertEquals(6, discord.deletedChannels.size());
        assertEquals("c1", discord.deletedChannels.get(5), "the category last, once it is empty");
        assertTrue(store.statChannels().isEmpty());
        assertFalse(channels.anySetUp());
    }

    @Test
    void theAnswerGoesOutBeforeAnyQueuedWorkRuns(@TempDir Path dir) {
        ServerCommandsTest.FakeDiscord discord = new ServerCommandsTest.FakeDiscord();
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        StatChannels channels = new StatChannels(discord, new CommunityStore(dir.resolve("c.json")), () -> null, 10,
                queued::add);
        assertTrue(channels.handle(statsCommand("setup", null)));
        assertEquals(1, discord.responses.size(), "\"thinking…\" at once, inside Discord's three seconds");
        assertEquals(5, discord.responses.get(0).get("type").getAsInt());
        assertEquals(0, discord.createdChannels.size(), "the work itself waits its turn");
        queued.forEach(Runnable::run);
        assertEquals(6, discord.createdChannels.size());
        assertEquals(1, discord.responses.size(), "answered once");
    }

    @Test
    void registrationWaitsAsLongAsDiscordSays() {
        assertEquals(3000, SlashCommands.retryAfterMillis(
                "{\"message\": \"Service resource is being rate limited.\", \"retry_after\": 3, \"global\": false, \"code\": 40062}"));
        assertEquals(500, SlashCommands.retryAfterMillis("{\"retry_after\": 0.01}"));
        assertEquals(5000, SlashCommands.retryAfterMillis("not json"));
    }

    @Test
    void aNamedCategory(@TempDir Path dir) {
        ServerCommandsTest.FakeDiscord discord = new ServerCommandsTest.FakeDiscord();
        StatChannels channels = new StatChannels(discord, new CommunityStore(dir.resolve("c.json")), () -> null, 10, Runnable::run);
        channels.handle(statsCommand("setup", "📈 Loka"));
        assertEquals("📈 Loka", discord.createdChannels.get("c1").get("name").getAsString());
        assertEquals("⏳ Online", discord.createdChannels.get("c2").get("name").getAsString(), "no numbers yet");
    }

    @Test
    void onlyChangedNamesAreRenamedAndAHeldBackOneIsTriedAgain(@TempDir Path dir) throws Exception {
        ServerCommandsTest.FakeDiscord discord = new ServerCommandsTest.FakeDiscord();
        CommunityStore store = new CommunityStore(dir.resolve("c.json"));
        StatChannels channels = new StatChannels(discord, store, () -> snapshot(49), 10, Runnable::run);
        channels.handle(statsCommand("setup", null));

        channels.update(snapshot(49));
        assertEquals(0, discord.renamed.size(), "nothing changed since they were made");

        discord.refuseRename.put("c2", 429);
        channels.update(snapshot(52));
        assertEquals(0, discord.renamed.size(), "Discord held it back");
        discord.refuseRename.clear();
        channels.update(snapshot(52));
        assertEquals(1, discord.renamed.size());
        assertEquals("🟢 Online: 52", discord.renamed.get(0)[1]);

        // Deleted by hand: forgotten.
        discord.refuseRename.put("c2", 404);
        channels.update(snapshot(60));
        assertEquals(4, store.statChannels().get("g1").channels.size());
    }

    // ---- the month's kills and damage ----

    private static BattleZone battle(String id, String territory, long ended, boolean active, Object[]... players) {
        JsonObject json = JsonParser.parseString("{\"_links\":{\"self\":{\"href\":\"https://api.lokamc.com/battlezones/"
                + id + "\"}}}").getAsJsonObject();
        json.addProperty("name", territory);
        json.addProperty("timeEnded", ended);
        json.addProperty("active", active);
        JsonObject attacking = new JsonObject();
        for (Object[] player : players) {
            JsonObject line = new JsonObject();
            line.addProperty("kills", (Integer) player[1]);
            line.addProperty("deaths", 0);
            line.addProperty("participationState", "PARTICIPATED");
            line.addProperty("damageToPlayers", (Double) player[2]);
            attacking.add(player[0].toString(), line);
        }
        json.add("attackingPlayers", attacking);
        json.add("defendingPlayers", new JsonObject());
        return BattleZone.fromJson(json);
    }

    private static long at(int year, int month, int day) {
        return LocalDate.of(year, month, day).atTime(20, 0).atZone(WARSAW).toInstant().toEpochMilli();
    }

    @Test
    void theMonthCountsConquestOnceAndStartsOverNextMonth() {
        MonthTally tally = new MonthTally(WARSAW, false);
        long now = at(2026, 11, 17);
        assertTrue(tally.add(battle("a", "west-19", at(2026, 11, 14), false,
                new Object[]{ACE, 5, 900.0}, new Object[]{BRUISER, 2, 4000.0}), now));
        assertTrue(tally.add(battle("b", "bigboi-5", at(2026, 11, 15), false, new Object[]{ACE, 3, 100.0}), now));
        assertFalse(tally.add(battle("b", "bigboi-5", at(2026, 11, 15), false, new Object[]{ACE, 3, 100.0}), now), "once");
        assertFalse(tally.add(battle("r", "lilboi-4", at(2026, 11, 15), false, new Object[]{BRUISER, 40, 90000.0}), now),
                "Rivina is not conquest");
        assertFalse(tally.add(battle("live", "south-9", 0, true, new Object[]{BRUISER, 9, 9000.0}), now), "still on");
        assertFalse(tally.add(battle("old", "south-9", at(2026, 10, 31), false, new Object[]{BRUISER, 9, 9000.0}), now),
                "last month");

        assertEquals(ACE, tally.topKills().uuid());
        assertEquals(8, tally.topKills().value());
        assertEquals(BRUISER, tally.topDamage().uuid());
        assertEquals(4000.0, tally.topDamage().value());
        assertTrue(tally.has(battle("r", "lilboi-4", at(2026, 11, 15), false)), "seen, so a sweep can stop at it");

        tally.month(at(2026, 12, 1));
        assertNull(tally.topKills(), "December starts from nothing");
        assertTrue(new MonthTally(WARSAW, true).add(battle("r", "lilboi-4", at(2026, 11, 15), false,
                new Object[]{BRUISER, 40, 90000.0}), now), "unless Rivina is asked for");
    }

    // ---- the server list ping ----

    @Test
    void readsThePlayerCountTheWayTheServerListDoes() throws Exception {
        String status = "{\"version\":{\"name\":\"Paper 26.3\",\"protocol\":775},\"players\":{\"max\":1000,\"online\":49},"
                + "\"description\":{\"text\":\"Loka\"}}";
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = new Thread(() -> {
                try (Socket client = server.accept()) {
                    DataInputStream in = new DataInputStream(client.getInputStream());
                    int handshakeLength = ServerPing.readVarInt(in);
                    in.readFully(new byte[handshakeLength]);
                    int requestLength = ServerPing.readVarInt(in);
                    in.readFully(new byte[requestLength]);
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    DataOutputStream data = new DataOutputStream(body);
                    ServerPing.writeVarInt(data, 0);
                    byte[] text = status.getBytes(StandardCharsets.UTF_8);
                    ServerPing.writeVarInt(data, text.length);
                    data.write(text);
                    ByteArrayOutputStream framed = new ByteArrayOutputStream();
                    ServerPing.writeVarInt(new DataOutputStream(framed), body.size());
                    framed.write(body.toByteArray());
                    client.getOutputStream().write(framed.toByteArray());
                } catch (Exception ignored) {
                    // The test fails on the client side.
                }
            });
            fake.start();
            ServerPing.Status answer = ServerPing.ping("127.0.0.1:" + server.getLocalPort(), 3000);
            fake.join(3000);
            assertEquals(49, answer.online());
            assertEquals(1000, answer.max());
            assertEquals("Paper 26.3", answer.version());
        }
    }

    @Test
    void varIntsRoundTrip() throws Exception {
        for (int value : List.of(0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ServerPing.writeVarInt(new DataOutputStream(out), value);
            assertEquals(value, ServerPing.readVarInt(new java.io.ByteArrayInputStream(out.toByteArray())));
        }
    }
}
