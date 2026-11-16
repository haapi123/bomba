package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCommandsTest {
    private static final String GUILD = "100";
    private static final String CHANNEL = "200";
    private static final String USER = "300";
    private static final String ROLE = "400";
    private static final String BOT = "500";
    private static final String BOT_ROLE = "600";

    /** Discord as far as these commands see it: everything asked of it is written down. */
    static final class FakeDiscord implements DiscordActions {
        final List<JsonObject> responses = new ArrayList<>();
        final List<JsonObject> edits = new ArrayList<>();
        final List<String[]> postedTo = new ArrayList<>();
        final List<JsonObject> posted = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String[]> threads = new ArrayList<>();
        final List<String> reactions = new ArrayList<>();
        final List<String[]> rolesGiven = new ArrayList<>();
        JsonArray guildRoles = new JsonArray();
        JsonObject botMember = new JsonObject();
        JsonObject storedMessage = new JsonObject();
        DiscordActions.Refused addRoleFailure;
        DiscordActions.Refused postFailure;
        int nextId = 900;

        @Override
        public void respond(String interactionId, String interactionToken, JsonObject response) {
            responses.add(response);
        }

        @Override
        public void editReply(String applicationId, String interactionToken, JsonObject message) {
            edits.add(message);
        }

        @Override
        public String post(String channelId, JsonObject message) throws Refused {
            if (postFailure != null) {
                throw postFailure;
            }
            String id = String.valueOf(nextId++);
            postedTo.add(new String[]{channelId, id});
            posted.add(message);
            return id;
        }

        @Override
        public JsonObject message(String channelId, String messageId) {
            return storedMessage;
        }

        @Override
        public void delete(String channelId, String messageId) {
            deleted.add(messageId);
        }

        @Override
        public String startThread(String channelId, String messageId, String name) {
            String id = String.valueOf(nextId++);
            threads.add(new String[]{channelId, messageId, name, id});
            return id;
        }

        @Override
        public void react(String channelId, String messageId, String emoji) {
            reactions.add(messageId + ":" + emoji);
        }

        @Override
        public void addRole(String guildId, String userId, String roleId, String reason) throws Refused {
            if (addRoleFailure != null) {
                throw addRoleFailure;
            }
            rolesGiven.add(new String[]{guildId, userId, roleId});
        }

        @Override
        public JsonArray roles(String guildId) {
            return guildRoles;
        }

        @Override
        public JsonObject member(String guildId, String userId) {
            return botMember;
        }
    }

    static final class FakeAlerts implements ServerCommands.Alerts {
        String off;
        boolean lokaDown;
        boolean secondFails;
        final List<String> samples = new ArrayList<>();

        @Override
        public String offReason() {
            return off;
        }

        @Override
        public int intervalSeconds() {
            return 30;
        }

        @Override
        public int probeLoka() throws Exception {
            if (lokaDown) {
                throw new java.io.IOException("connection refused");
            }
            return 1234;
        }

        @Override
        public List<ServerCommands.Delivery> postSample(String requestedBy, boolean ping) {
            samples.add(requestedBy + ":" + ping);
            return List.of(new ServerCommands.Delivery("<#777>", "888", null),
                    new ServerCommands.Delivery("<#779>", "889", secondFails ? "Discord answered 403" : null));
        }
    }

    private final FakeDiscord discord = new FakeDiscord();
    private final FakeAlerts alerts = new FakeAlerts();
    private final BotStatus status = new BotStatus();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ServerCommands commands(Path dir) {
        return new ServerCommands(discord, status, new CommunityStore(dir.resolve("community.json")), alerts,
                () -> BOT, Runnable::run, now::get);
    }

    // ---- building interactions as Discord sends them ----

    private static JsonObject member(String... roles) {
        JsonObject user = new JsonObject();
        user.addProperty("id", USER);
        user.addProperty("username", "steve");
        user.addProperty("global_name", "Steve");
        JsonObject member = new JsonObject();
        member.add("user", user);
        JsonArray roleArray = new JsonArray();
        for (String role : roles) {
            roleArray.add(role);
        }
        member.add("roles", roleArray);
        return member;
    }

    private static JsonObject interaction(int type, JsonObject data, String... memberRoles) {
        JsonObject interaction = new JsonObject();
        interaction.addProperty("id", "1");
        interaction.addProperty("token", "token");
        interaction.addProperty("application_id", "app");
        interaction.addProperty("type", type);
        interaction.addProperty("guild_id", GUILD);
        interaction.addProperty("channel_id", CHANNEL);
        interaction.add("member", member(memberRoles));
        interaction.add("data", data);
        return interaction;
    }

    private static JsonObject command(String name, String optionsJson) {
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        if (optionsJson != null) {
            data.add("options", JsonParser.parseString(optionsJson));
        }
        return data;
    }

    private static JsonObject click(String customId, String messageId) {
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", customId);
        data.addProperty("component_type", 2);
        JsonObject interaction = interaction(3, data);
        JsonObject message = new JsonObject();
        message.addProperty("id", messageId);
        interaction.add("message", message);
        return interaction;
    }

    private static JsonObject form(String customId, String title, String body) {
        JsonObject data = JsonParser.parseString("""
                {"custom_id": "%s", "components": [
                  {"type": 1, "components": [{"type": 4, "custom_id": "title", "value": %s}]},
                  {"type": 1, "components": [{"type": 4, "custom_id": "body", "value": %s}]}]}
                """.formatted(customId, quote(title), quote(body))).getAsJsonObject();
        return interaction(5, data);
    }

    private static String quote(String text) {
        return new com.google.gson.JsonPrimitive(text).toString();
    }

    private static String content(JsonObject message) {
        return message.has("content") ? message.get("content").getAsString() : "";
    }

    private static String customIdOfFirstButton(JsonObject message) {
        return message.getAsJsonArray("components").get(0).getAsJsonObject()
                .getAsJsonArray("components").get(0).getAsJsonObject().get("custom_id").getAsString();
    }

    // ---- the commands themselves ----

    @Test
    void theCommandsAreValidForDiscord() {
        JsonArray definitions = ServerCommands.definitions();
        List<String> names = new ArrayList<>();
        for (JsonElement element : definitions) {
            JsonObject command = element.getAsJsonObject();
            String name = command.get("name").getAsString();
            names.add(name);
            assertTrue(name.matches("[a-z0-9_-]{1,32}"), name);
            assertTrue(command.get("description").getAsString().length() <= 100, name);
            boolean optionalSeen = false;
            for (JsonElement option : command.getAsJsonArray("options")) {
                JsonObject o = option.getAsJsonObject();
                assertTrue(o.get("name").getAsString().matches("[a-z0-9_-]{1,32}"));
                assertTrue(o.get("description").getAsString().length() <= 100);
                boolean required = o.get("required").getAsBoolean();
                assertFalse(required && optionalSeen, "Discord wants required options first: " + name);
                optionalSeen |= !required;
            }
        }
        assertEquals(List.of("test", "verification", "suggestions"), names);
        assertEquals("268435456", definitions.get(1).getAsJsonObject().get("default_member_permissions").getAsString(),
                "setting up verification takes Manage Roles");
        JsonObject postTo = definitions.get(2).getAsJsonObject().getAsJsonArray("options").get(0).getAsJsonObject();
        assertEquals("post_to", postTo.get("name").getAsString());
        assertTrue(postTo.get("required").getAsBoolean(), "suggestions always say where they go");
    }

    @Test
    void everyCommandIsRegisteredInEnglish() {
        List<String> names = new ArrayList<>();
        for (JsonElement element : SlashCommands.allCommands()) {
            names.add(element.getAsJsonObject().get("name").getAsString());
        }
        assertEquals(List.of("check", "test", "verification", "suggestions"), names);
    }

    @Test
    void otherInteractionsAreLeftAlone(@TempDir Path dir) {
        ServerCommands commands = commands(dir);
        assertFalse(commands.handle(interaction(2, command("check", null))));
        assertFalse(commands.handle(click("bla:answer:1", "5")), "the assistant's questions channel");
        assertTrue(discord.responses.isEmpty());
    }

    // ---- verification ----

    @Test
    void setUpVerificationPostsAGreenButtonForTheRole(@TempDir Path dir) {
        discord.botMember = JsonParser.parseString("{\"roles\": [\"" + BOT_ROLE + "\"]}").getAsJsonObject();
        discord.guildRoles = JsonParser.parseString("""
                [{"id": "%s", "position": 0, "permissions": "0"},
                 {"id": "%s", "position": 5, "permissions": "268435456"},
                 {"id": "%s", "position": 2, "permissions": "0"}]""".formatted(GUILD, BOT_ROLE, ROLE)).getAsJsonArray();
        assertTrue(commands(dir).handle(interaction(2, command("verification",
                "[{\"name\": \"role\", \"type\": 8, \"value\": \"" + ROLE + "\"}, "
                        + "{\"name\": \"channel\", \"type\": 7, \"value\": \"999\"}]"))));

        assertEquals("999", discord.postedTo.get(0)[0], "the channel picked, not the one typed in");
        JsonObject message = discord.posted.get(0);
        assertEquals("blb:verify:" + ROLE, customIdOfFirstButton(message));
        JsonObject button = message.getAsJsonArray("components").get(0).getAsJsonObject()
                .getAsJsonArray("components").get(0).getAsJsonObject();
        assertEquals(3, button.get("style").getAsInt(), "green");
        String reply = content(discord.edits.get(0));
        assertTrue(reply.startsWith("✅"), reply);
        assertFalse(reply.contains("⚠️"), "the bot's role is above, and it may manage roles");
    }

    @Test
    void setUpWarnsWhenTheBotCannotGiveTheRole(@TempDir Path dir) {
        discord.botMember = JsonParser.parseString("{\"roles\": [\"" + BOT_ROLE + "\"]}").getAsJsonObject();
        discord.guildRoles = JsonParser.parseString("""
                [{"id": "%s", "position": 0, "permissions": "0"},
                 {"id": "%s", "position": 1, "permissions": "0"},
                 {"id": "%s", "position": 3, "permissions": "0"}]""".formatted(GUILD, BOT_ROLE, ROLE)).getAsJsonArray();
        commands(dir).handle(interaction(2, command("verification",
                "[{\"name\": \"role\", \"type\": 8, \"value\": \"" + ROLE + "\"}]")));
        String reply = content(discord.edits.get(0));
        assertTrue(reply.contains("Manage Roles"), reply);
        assertTrue(reply.contains("above the bot's highest role"), reply);
        assertEquals(CHANNEL, discord.postedTo.get(0)[0], "posted anyway, in the channel it was typed in");
    }

    @Test
    void everyoneCannotBeHandedOut(@TempDir Path dir) {
        commands(dir).handle(interaction(2, command("verification",
                "[{\"name\": \"role\", \"type\": 8, \"value\": \"" + GUILD + "\"}]")));
        assertTrue(discord.posted.isEmpty());
        assertTrue(content(discord.edits.get(0)).startsWith("❌"));
    }

    @Test
    void clickingTheButtonGivesTheRole(@TempDir Path dir) {
        assertTrue(commands(dir).handle(click("blb:verify:" + ROLE, "50")));
        assertEquals(1, discord.rolesGiven.size());
        assertEquals(List.of(GUILD, USER, ROLE), List.of(discord.rolesGiven.get(0)));
        assertEquals(5, discord.responses.get(0).get("type").getAsInt(), "thinking first, then the answer");
        assertTrue(content(discord.edits.get(0)).contains("Verified"));
    }

    @Test
    void somebodyWhoHasTheRoleAlreadyIsJustTold(@TempDir Path dir) {
        JsonObject click = click("blb:verify:" + ROLE, "50");
        click.add("member", member(ROLE));
        commands(dir).handle(click);
        assertTrue(discord.rolesGiven.isEmpty());
        JsonObject response = discord.responses.get(0);
        assertEquals(4, response.get("type").getAsInt());
        assertEquals(64, response.getAsJsonObject("data").get("flags").getAsInt(), "only they see it");
        assertTrue(response.getAsJsonObject("data").get("content").getAsString().contains("already have"));
    }

    @Test
    void aRefusalSaysWhatTheAdminsHaveToFix(@TempDir Path dir) {
        discord.addRoleFailure = new DiscordActions.Refused(403, "Missing Permissions");
        commands(dir).handle(click("blb:verify:" + ROLE, "50"));
        String reply = content(discord.edits.get(0));
        assertTrue(reply.startsWith("❌") && reply.contains("Manage Roles"), reply);
    }

    // ---- suggestions ----

    private static final String SUGGESTIONS = "210";

    @Test
    void thePanelCarriesTheGreenButtonAndWhereSuggestionsGo(@TempDir Path dir) {
        commands(dir).handle(interaction(2, command("suggestions",
                "[{\"name\": \"post_to\", \"type\": 7, \"value\": \"" + SUGGESTIONS + "\"}]")));
        assertEquals(CHANNEL, discord.postedTo.get(0)[0], "the panel goes where the command was typed");
        JsonObject panel = discord.posted.get(0);
        JsonObject embed = panel.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("Want to add something to the mod? Press the green button and write your suggestion!",
                embed.get("description").getAsString());
        assertEquals("blb:suggest:" + SUGGESTIONS, customIdOfFirstButton(panel));
        String reply = content(discord.edits.get(0));
        assertTrue(reply.startsWith("✅") && reply.contains("<#" + SUGGESTIONS + ">"), reply);
    }

    @Test
    void theButtonOpensAFormForATitleAndTheSuggestion(@TempDir Path dir) {
        commands(dir).handle(click("blb:suggest:" + SUGGESTIONS, "77"));
        JsonObject response = discord.responses.get(0);
        assertEquals(9, response.get("type").getAsInt(), "a form");
        JsonObject data = response.getAsJsonObject("data");
        assertEquals("blb:suggest-form:" + SUGGESTIONS + ":77", data.get("custom_id").getAsString(),
                "remembers where suggestions go and which panel it came from");
        JsonArray rows = data.getAsJsonArray("components");
        JsonObject title = rows.get(0).getAsJsonObject().getAsJsonArray("components").get(0).getAsJsonObject();
        JsonObject body = rows.get(1).getAsJsonObject().getAsJsonArray("components").get(0).getAsJsonObject();
        assertEquals("title", title.get("custom_id").getAsString());
        assertEquals(1, title.get("style").getAsInt());
        assertEquals("body", body.get("custom_id").getAsString());
        assertEquals(2, body.get("style").getAsInt(), "a paragraph");
        assertEquals("New suggestion", data.get("title").getAsString());
        assertTrue(data.get("custom_id").getAsString().length() <= 100);
    }

    @Test
    void aSuggestionIsPostedInItsOwnChannelWithAThreadAndThePanelStaysPut(@TempDir Path dir) {
        ServerCommands commands = commands(dir);
        commands.handle(form("blb:suggest-form:" + SUGGESTIONS + ":77", "  Restart countdown  ",
                "I'd like to see on screen how long is left until the restart."));

        // The suggestion, as the bot's own message, in the suggestions channel.
        assertEquals(SUGGESTIONS, discord.postedTo.get(0)[0]);
        String suggestionId = discord.postedTo.get(0)[1];
        JsonObject embed = discord.posted.get(0).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("💡 Restart countdown", embed.get("title").getAsString());
        assertEquals("I'd like to see on screen how long is left until the restart.", embed.get("description").getAsString());
        assertEquals("Steve", embed.getAsJsonObject("author").get("name").getAsString());
        assertTrue(embed.getAsJsonObject("footer").get("text").getAsString().startsWith("Suggestion #1"));

        // Votes, and the thread on it in that channel, with its author called in.
        assertEquals(List.of(suggestionId + ":👍", suggestionId + ":👎"), discord.reactions);
        assertEquals(1, discord.threads.size());
        assertEquals(SUGGESTIONS, discord.threads.get(0)[0]);
        assertEquals(suggestionId, discord.threads.get(0)[1]);
        assertEquals("#1 Restart countdown", discord.threads.get(0)[2]);
        String thread = discord.threads.get(0)[3];
        assertEquals(thread, discord.postedTo.get(1)[0]);
        assertTrue(content(discord.posted.get(1)).contains("<@" + USER + ">"));
        assertEquals(USER, discord.posted.get(1).getAsJsonObject("allowed_mentions").getAsJsonArray("users")
                .get(0).getAsString(), "the author, and nobody else, is pinged");

        // The panel has a channel to itself, so nothing pushed it up and it is not moved.
        assertEquals(2, discord.posted.size());
        assertTrue(discord.deleted.isEmpty());

        String reply = content(discord.edits.get(0));
        assertTrue(reply.contains("#1") && reply.contains("<#" + SUGGESTIONS + ">") && reply.contains("<#" + thread + ">"),
                reply);
    }

    @Test
    void aPanelInTheSuggestionsChannelItselfMovesBackDown(@TempDir Path dir) {
        discord.storedMessage = ServerCommands.suggestionPanel(CHANNEL, null, null);
        ServerCommands commands = commands(dir);
        commands.handle(form("blb:suggest-form:" + CHANNEL + ":77", "First idea", "The first suggestion, long enough."));
        assertEquals(CHANNEL, discord.postedTo.get(0)[0]);
        assertEquals(CHANNEL, discord.postedTo.get(2)[0]);
        assertEquals("blb:suggest:" + CHANNEL, customIdOfFirstButton(discord.posted.get(2)));
        assertEquals(List.of("77"), discord.deleted);

        String movedPanel = discord.postedTo.get(2)[1];
        now.addAndGet(ServerCommands.SUGGEST_COOLDOWN_MILLIS + 1);
        JsonObject second = form("blb:suggest-form:" + CHANNEL + ":77", "Second idea", "The second suggestion, long enough.");
        second.getAsJsonObject("member").getAsJsonObject("user").addProperty("id", "301");
        commands.handle(second);
        assertEquals(List.of("77", movedPanel), discord.deleted, "the panel from the form is stale by now");
        JsonObject embed = discord.posted.get(3).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertTrue(embed.getAsJsonObject("footer").get("text").getAsString().startsWith("Suggestion #2"));
    }

    @Test
    void aPanelFromTheLastVersionStillWorksInItsOwnChannel(@TempDir Path dir) {
        discord.storedMessage = ServerCommands.suggestionPanel(null, null, null);
        ServerCommands commands = commands(dir);
        commands.handle(click("blb:suggest", "77"));
        assertEquals("blb:suggest-form::77",
                discord.responses.get(0).getAsJsonObject("data").get("custom_id").getAsString());
        commands.handle(form("blb:suggest-form:77", "Old panel", "A suggestion from a panel posted by 0.49."));
        assertEquals(CHANNEL, discord.postedTo.get(0)[0], "the channel it was sent from");
        assertEquals(List.of("77"), discord.deleted, "and that panel moves down as before");
    }

    @Test
    void tooShortIsSentBackAndNothingIsPosted(@TempDir Path dir) {
        commands(dir).handle(form("blb:suggest-form:" + SUGGESTIONS + ":77", "ab", "short"));
        assertTrue(discord.posted.isEmpty());
        JsonObject response = discord.responses.get(0);
        assertEquals(4, response.get("type").getAsInt());
        assertTrue(response.getAsJsonObject("data").get("content").getAsString().startsWith("❌"));
    }

    @Test
    void oneSuggestionAMinute(@TempDir Path dir) {
        ServerCommands commands = commands(dir);
        commands.handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "First idea", "The first suggestion, long enough."));
        int posts = discord.posted.size();
        now.addAndGet(30_000);
        commands.handle(click("blb:suggest:" + SUGGESTIONS, "77"));
        JsonObject response = discord.responses.get(discord.responses.size() - 1);
        assertEquals(4, response.get("type").getAsInt(), "no form while waiting");
        assertTrue(response.getAsJsonObject("data").get("content").getAsString().contains("30 s"));
        commands.handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "Second idea", "The second suggestion, long enough."));
        assertEquals(posts, discord.posted.size(), "and a form already open is refused too");
        now.addAndGet(31_000);
        commands.handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "Second idea", "The second suggestion, long enough."));
        assertTrue(discord.posted.size() > posts);
    }

    @Test
    void numbersCarryOnAfterARestart(@TempDir Path dir) {
        commands(dir).handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "First idea", "The first suggestion, long enough."));
        now.addAndGet(ServerCommands.SUGGEST_COOLDOWN_MILLIS + 1);
        commands(dir).handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "Second idea", "The second suggestion, long enough."));
        JsonObject last = null;
        for (JsonObject message : discord.posted) {
            if (message.has("embeds") && message.getAsJsonArray("embeds").size() > 0) {
                last = message;
            }
        }
        String footer = last.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("footer")
                .get("text").getAsString();
        assertTrue(footer.startsWith("Suggestion #2"), footer);
    }

    @Test
    void aChannelTheBotCannotWriteInIsSaidPlainly(@TempDir Path dir) {
        discord.postFailure = new DiscordActions.Refused(403, "Missing Access");
        ServerCommands commands = commands(dir);
        commands.handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "First idea", "The first suggestion, long enough."));
        String reply = content(discord.edits.get(0));
        assertTrue(reply.startsWith("❌") && reply.contains("<#" + SUGGESTIONS + ">"), reply);
        discord.postFailure = null;
        commands.handle(form("blb:suggest-form:" + SUGGESTIONS + ":", "First idea", "The first suggestion, long enough."));
        assertFalse(discord.posted.isEmpty(), "a failed post does not start the cooldown");
    }

    // ---- /test ----

    @Test
    void testReportsEverythingAndPostsASampleEverywhere(@TempDir Path dir) {
        status.watchStarted();
        status.checked(true, 1234);
        commands(dir).handle(interaction(2, command("test", null)));
        assertEquals(List.of(USER + ":false"), alerts.samples, "no role ping unless asked");
        JsonObject embed = discord.edits.get(0).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("✅ The bot is working", embed.get("title").getAsString());
        String fields = embed.getAsJsonArray("fields").toString();
        assertTrue(fields.contains("1234"), fields);
        assertTrue(fields.contains("every 30 s"), fields);
        assertTrue(fields.contains("<#777>") && fields.contains("<#779>"), "both places: " + fields);
        assertTrue(fields.contains("<@&888>") && fields.contains("<@&889>"), fields);
    }

    @Test
    void testCanPingTheRole(@TempDir Path dir) {
        commands(dir).handle(interaction(2, command("test", "[{\"name\": \"ping\", \"type\": 5, \"value\": true}]")));
        assertEquals(List.of(USER + ":true"), alerts.samples);
        String fields = discord.edits.get(0).getAsJsonArray("embeds").get(0).getAsJsonObject()
                .getAsJsonArray("fields").toString();
        assertTrue(fields.contains("pinged <@&888>"), fields);
    }

    @Test
    void onePlaceFailingIsNamed(@TempDir Path dir) {
        alerts.secondFails = true;
        commands(dir).handle(interaction(2, command("test", null)));
        JsonObject embed = discord.edits.get(0).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertTrue(embed.get("title").getAsString().startsWith("❌"));
        assertTrue(embed.getAsJsonArray("fields").toString().contains("❌ <#779>: Discord answered 403"));
    }

    @Test
    void testSaysWhenTheWatchIsOffOrLokaIsDown(@TempDir Path dir) {
        alerts.off = "Set either webhookUrl, or botToken together with channelId.";
        commands(dir).handle(interaction(2, command("test", null)));
        JsonObject embed = discord.edits.get(0).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertTrue(embed.get("title").getAsString().startsWith("⚠️"));
        assertTrue(alerts.samples.isEmpty());

        alerts.off = null;
        alerts.lokaDown = true;
        commands(dir).handle(interaction(2, command("test", null)));
        embed = discord.edits.get(1).getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertTrue(embed.get("title").getAsString().startsWith("❌"));
        assertTrue(embed.getAsJsonArray("fields").toString().contains("connection refused"));
    }

    @Test
    void theSampleLooksLikeARealAlert() {
        JsonObject embed = BetterLokaBot.sampleEmbed();
        assertEquals("Example Town (TEST)", embed.get("title").getAsString());
        assertTrue(embed.getAsJsonArray("fields").get(0).getAsJsonObject().get("value").getAsString()
                .contains("3727"));
    }

    // ---- small things ----

    @Test
    void namesAndPicturesComeFromWhereDiscordPutsThem() {
        JsonObject interaction = interaction(3, new JsonObject());
        assertEquals("Steve", ServerCommands.displayName(interaction));
        interaction.getAsJsonObject("member").addProperty("nick", "Stefan");
        assertEquals("Stefan", ServerCommands.displayName(interaction));
        assertTrue(ServerCommands.avatarUrl(interaction).startsWith("https://cdn.discordapp.com/embed/avatars/"));
        interaction.getAsJsonObject("member").getAsJsonObject("user").addProperty("avatar", "abc");
        assertEquals("https://cdn.discordapp.com/avatars/300/abc.png?size=128", ServerCommands.avatarUrl(interaction));
        assertEquals(100, ServerCommands.threadName(12, "x".repeat(200)).length());
        assertEquals("a b", ServerCommands.tidy(" a \n b ", false));
        assertEquals("a\n\nb", ServerCommands.tidy("a\n\n\n\nb", true));
        assertNull(ServerCommands.userId(new JsonObject()));
    }
}
