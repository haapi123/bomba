package com.betterloka.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.format.TextStyle;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Live stat tiles: locked voice channels whose names are Loka's numbers, at the top of a Discord
 * server — players on, fights on, this month's most conquest kills and damage, and the top of ranked
 * potion.
 *
 * <p>{@code /stats setup} makes them (a category and five voice channels nobody can join), {@code
 * /stats remove} takes them away; in between they are renamed as the numbers change. Discord lets a
 * channel be renamed twice in ten minutes, so they are updated every ten minutes at the quickest and
 * only when their text has changed; a rename Discord holds back is simply tried at the next update.
 */
public final class StatChannels {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");

    /** The five tiles, in the order they are listed. */
    public enum Tile {
        ONLINE("online", "Online"),
        FIGHTS("fights", "Fights now"),
        KILLS("kills", "Most kills"),
        DAMAGE("damage", "Most damage"),
        POTION("potion", "#1 Potion");

        final String key;
        final String label;

        Tile(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static final String DEFAULT_CATEGORY = "📊 Loka Live";
    /** Joining a voice channel; the tiles deny it to everybody, so they are only looked at. */
    static final long CONNECT = 1L << 20;
    static final int NAME_MAX = 100;

    private final DiscordActions discord;
    private final CommunityStore store;
    private final Supplier<LiveStats.Snapshot> latest;
    private final int intervalMinutes;
    private final Executor work;
    /** The name each channel was last given, so an unchanged one is not renamed again. */
    private final Map<String, String> shown = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** @param latest the newest numbers without asking Loka again, or {@code null} before the first */
    public StatChannels(DiscordActions discord, CommunityStore store, Supplier<LiveStats.Snapshot> latest,
                        int intervalMinutes, Executor work) {
        this.discord = discord;
        this.store = store;
        this.latest = latest;
        this.intervalMinutes = intervalMinutes;
        this.work = work;
    }

    // ---- the names ----

    /**
     * A tile's channel name for these numbers, or {@code null} while its number is not known yet —
     * then the channel keeps the name it has.
     */
    static String name(Tile tile, LiveStats.Snapshot stats) {
        if (stats == null) {
            return null;
        }
        String month = stats.month() == null ? ""
                : " (" + stats.month().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ")";
        String name = switch (tile) {
            case ONLINE -> stats.players() == null ? null
                    : stats.players().offline() ? "🔴 Loka offline" : "🟢 Online: " + stats.players().online();
            case FIGHTS -> {
                if (stats.fights() == null) {
                    yield null;
                }
                String text = "⚔️ Fights now: " + stats.fights().now();
                yield stats.fights().declared() > 0 ? text + " · " + stats.fights().declared() + " declared" : text;
            }
            case KILLS -> leader("🗡️ Most kills" + month, stats.kills(), false);
            case DAMAGE -> leader("💥 Most damage" + month, stats.damage(), true);
            case POTION -> stats.potion() == null ? null
                    : "🏆 #1 Potion: " + stats.potion().name()
                    + (stats.potion().rank().isBlank() ? "" : " · " + stats.potion().rank());
        };
        return name == null || name.length() <= NAME_MAX ? name : name.substring(0, NAME_MAX);
    }

    private static String leader(String label, LiveStats.Named top, boolean compact) {
        if (top == null) {
            return null;
        }
        if (top.name().isEmpty()) {
            return label + ": —";
        }
        return label + ": " + top.name() + " · " + (compact ? compact(top.value()) : String.valueOf(Math.round(top.value())));
    }

    /** 950, 45.2k, 1.3M. */
    static String compact(double value) {
        if (value < 1000) {
            return String.valueOf(Math.round(value));
        }
        double scaled = value < 1_000_000 ? value / 1000 : value / 1_000_000;
        String unit = value < 1_000_000 ? "k" : "M";
        String text = scaled >= 100 ? String.valueOf(Math.round(scaled))
                : String.format(Locale.ROOT, "%.1f", scaled).replaceAll("\\.0$", "");
        return text + unit;
    }

    // ---- keeping them current ----

    /** Whether any server has tiles to keep current: if not, Loka is not asked. */
    public boolean anySetUp() {
        return !store.statChannels().isEmpty();
    }

    /** Renames every server's tiles whose text these numbers change. */
    public void update(LiveStats.Snapshot stats) throws InterruptedException {
        for (Map.Entry<String, CommunityStore.StatChannelSet> server : store.statChannels().entrySet()) {
            CommunityStore.StatChannelSet set = server.getValue();
            boolean changed = false;
            for (Tile tile : Tile.values()) {
                String channel = set.channels.get(tile.key);
                String name = name(tile, stats);
                if (channel == null || name == null || name.equals(shown.get(channel))) {
                    continue;
                }
                try {
                    discord.renameChannel(channel, name);
                    shown.put(channel, name);
                } catch (DiscordActions.Refused refused) {
                    if (refused.status() == 404) {
                        // Deleted by hand: forget it rather than ask about it forever.
                        set.channels.remove(tile.key);
                        changed = true;
                        LOG.info("Stat channel {} ({}) is gone from server {}; forgotten", channel, tile.key, server.getKey());
                    } else if (refused.status() == 429) {
                        LOG.debug("Discord holds back renaming {} for now", channel);
                    } else if (warned.add(channel)) {
                        LOG.warn("Could not rename stat channel {} in server {}: {} (does the bot have Manage "
                                + "Channels there?)", channel, server.getKey(), refused.getMessage());
                    }
                } catch (IOException e) {
                    LOG.warn("Could not rename stat channel {}: {}", channel, e.getMessage());
                }
            }
            if (changed) {
                store.setStatChannels(server.getKey(), set);
            }
        }
    }

    // ---- /stats ----

    /** {@code /stats setup} and {@code /stats remove}; for whoever may manage channels. */
    public static JsonObject definition() {
        JsonObject command = new JsonObject();
        command.addProperty("name", "stats");
        command.addProperty("description", "Live Loka stat channels at the top of the server");
        command.addProperty("type", 1);
        command.addProperty("default_member_permissions", "16");
        JsonArray contexts = new JsonArray();
        contexts.add(0);
        command.add("contexts", contexts);

        JsonObject category = new JsonObject();
        category.addProperty("type", 3);
        category.addProperty("name", "category");
        category.addProperty("description", "The category's name (" + DEFAULT_CATEGORY + " if left out)");
        category.addProperty("required", false);
        category.addProperty("max_length", 90);
        JsonArray setupOptions = new JsonArray();
        setupOptions.add(category);

        JsonObject setup = new JsonObject();
        setup.addProperty("type", 1);
        setup.addProperty("name", "setup");
        setup.addProperty("description", "Makes the stat channels: players on, fights, top kills and damage, #1 potion");
        setup.add("options", setupOptions);

        JsonObject remove = new JsonObject();
        remove.addProperty("type", 1);
        remove.addProperty("name", "remove");
        remove.addProperty("description", "Deletes the stat channels this bot made here");

        JsonArray subcommands = new JsonArray();
        subcommands.add(setup);
        subcommands.add(remove);
        command.add("options", subcommands);
        return command;
    }

    /** @return true if the interaction is {@code /stats}, and is being answered */
    public boolean handle(JsonObject interaction) {
        JsonObject data = object(interaction, "data");
        if (data == null || !"stats".equals(string(data, "name"))
                || !interaction.has("type") || interaction.get("type").getAsInt() != 2) {
            return false;
        }
        JsonArray options = data.has("options") && data.get("options").isJsonArray() ? data.getAsJsonArray("options") : new JsonArray();
        JsonObject sub = options.isEmpty() ? null : options.get(0).getAsJsonObject();
        String which = sub == null ? "" : string(sub, "name");
        work.execute(() -> {
            try {
                if ("remove".equals(which)) {
                    remove(interaction);
                } else {
                    setup(interaction, sub);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.warn("/stats failed", e);
            }
        });
        return true;
    }

    void setup(JsonObject interaction, JsonObject sub) throws InterruptedException {
        if (!defer(interaction)) {
            return;
        }
        String guild = string(interaction, "guild_id");
        if (guild == null) {
            reply(interaction, "❌ This only works in a server.");
            return;
        }
        CommunityStore.StatChannelSet existing = store.statChannels().get(guild);
        if (existing != null && !existing.channels.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (String channel : existing.channels.values()) {
                list.append("<#").append(channel).append("> ");
            }
            reply(interaction, "ℹ️ The stat channels are already here: " + list.toString().strip()
                    + "\nUse `/stats remove` first to make them again.");
            return;
        }
        String categoryName = optionString(sub, "category");
        if (categoryName == null || categoryName.isBlank()) {
            categoryName = DEFAULT_CATEGORY;
        }
        LiveStats.Snapshot stats = latest == null ? null : latest.get();
        CommunityStore.StatChannelSet set = new CommunityStore.StatChannelSet();
        String reason = "Stat channels, set up by " + userName(interaction);
        try {
            JsonObject category = new JsonObject();
            category.addProperty("name", categoryName);
            category.addProperty("type", 4);
            category.addProperty("position", 0);
            category.add("permission_overwrites", lockedToEveryone(guild));
            set.category = discord.createChannel(guild, category, reason);
            store.setStatChannels(guild, set);

            int position = 0;
            for (Tile tile : Tile.values()) {
                String name = name(tile, stats);
                JsonObject channel = new JsonObject();
                channel.addProperty("name", name != null ? name : "⏳ " + tile.label);
                channel.addProperty("type", 2);
                channel.addProperty("parent_id", set.category);
                channel.addProperty("position", position++);
                channel.add("permission_overwrites", lockedToEveryone(guild));
                String id = discord.createChannel(guild, channel, reason);
                set.channels.put(tile.key, id);
                if (name != null) {
                    shown.put(id, name);
                }
                // Saved after each, so a half-made set can still be removed with /stats remove.
                store.setStatChannels(guild, set);
            }
        } catch (DiscordActions.Refused refused) {
            reply(interaction, refused.status() == 403
                    ? "❌ Discord would not let the bot make channels. Give its role **Manage Channels** "
                    + "(Server Settings → Roles), then try again." + made(set)
                    : "❌ Discord refused: " + refused.getMessage() + made(set));
            return;
        } catch (IOException e) {
            reply(interaction, "❌ Could not reach Discord: " + e.getMessage() + made(set));
            return;
        }
        LOG.info("Stat channels set up in server {}", guild);
        reply(interaction, "✅ Made **" + categoryName + "** with " + set.channels.size() + " stat channels. "
                + "Nobody can join them; they show Loka's numbers and update every " + intervalMinutes
                + " minutes (Discord allows a channel to be renamed only twice in ten minutes). "
                + "Drag the category wherever you like. `/stats remove` deletes them.");
    }

    private static String made(CommunityStore.StatChannelSet set) {
        return set.category.isEmpty() ? "" : "\nWhat was made so far can be deleted with `/stats remove`.";
    }

    void remove(JsonObject interaction) throws InterruptedException {
        if (!defer(interaction)) {
            return;
        }
        String guild = string(interaction, "guild_id");
        CommunityStore.StatChannelSet set = guild == null ? null : store.statChannels().get(guild);
        if (set == null) {
            reply(interaction, "ℹ️ There are no stat channels from this bot here.");
            return;
        }
        String reason = "Stat channels removed by " + userName(interaction);
        int failed = 0;
        Set<String> all = new HashSet<>(set.channels.values());
        if (!set.category.isEmpty()) {
            all.add(set.category);
        }
        for (String channel : all) {
            if (channel.equals(set.category)) {
                continue;
            }
            failed += deleteQuietly(channel, reason);
        }
        if (!set.category.isEmpty()) {
            failed += deleteQuietly(set.category, reason);
        }
        for (String channel : all) {
            shown.remove(channel);
        }
        store.setStatChannels(guild, null);
        reply(interaction, failed == 0 ? "🗑️ The stat channels are gone."
                : "⚠️ Forgotten, but " + failed + " channel(s) could not be deleted — delete them by hand.");
    }

    /** @return 1 if it is still there, 0 if it is gone (or was already) */
    private int deleteQuietly(String channel, String reason) throws InterruptedException {
        try {
            discord.deleteChannel(channel, reason);
            return 0;
        } catch (DiscordActions.Refused refused) {
            return refused.status() == 404 ? 0 : 1;
        } catch (IOException e) {
            return 1;
        }
    }

    /** Everybody may see it; nobody may join it. */
    private static JsonArray lockedToEveryone(String guildId) {
        JsonObject everyone = new JsonObject();
        everyone.addProperty("id", guildId);
        everyone.addProperty("type", 0);
        everyone.addProperty("deny", String.valueOf(CONNECT));
        everyone.addProperty("allow", "0");
        JsonArray overwrites = new JsonArray();
        overwrites.add(everyone);
        return overwrites;
    }

    // ---- interaction plumbing ----

    private boolean defer(JsonObject interaction) throws InterruptedException {
        JsonObject response = new JsonObject();
        response.addProperty("type", 5);
        JsonObject data = new JsonObject();
        data.addProperty("flags", 64);
        response.add("data", data);
        try {
            discord.respond(string(interaction, "id"), string(interaction, "token"), response);
            return true;
        } catch (IOException e) {
            LOG.warn("Could not answer /stats: {}", e.getMessage());
            return false;
        }
    }

    private void reply(JsonObject interaction, String text) throws InterruptedException {
        JsonObject message = new JsonObject();
        message.addProperty("content", text);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        message.add("allowed_mentions", mentions);
        try {
            discord.editReply(string(interaction, "application_id"), string(interaction, "token"), message);
        } catch (IOException e) {
            LOG.warn("Could not finish answering /stats: {}", e.getMessage());
        }
    }

    private static String userName(JsonObject interaction) {
        JsonObject member = object(interaction, "member");
        JsonObject user = member != null ? object(member, "user") : object(interaction, "user");
        String name = user == null ? null : string(user, "username");
        return name == null ? "an admin" : name;
    }

    private static String optionString(JsonObject sub, String name) {
        if (sub == null || !sub.has("options") || !sub.get("options").isJsonArray()) {
            return null;
        }
        for (JsonElement option : sub.getAsJsonArray("options")) {
            if (option.isJsonObject() && name.equals(string(option.getAsJsonObject(), "name"))) {
                return string(option.getAsJsonObject(), "value");
            }
        }
        return null;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : null;
    }
}
