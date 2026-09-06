package com.betterloka;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.betterloka.api.MarketApi;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.data.TownCache;
import com.betterloka.grind.GrindHud;
import com.betterloka.map.DynmapApi;
import com.betterloka.map.MapIcons;
import com.betterloka.api.LabyApi;
import com.betterloka.map.MapDataStore;
import com.betterloka.stats.NameHistoryService;
import com.betterloka.map.MapService;
import com.betterloka.map.MapTerrain;
import com.betterloka.map.WaypointHud;
import com.betterloka.map.WaypointWorldRenderer;
import com.betterloka.grind.GrindTimer;
import com.betterloka.grind.GrindTimers;
import com.betterloka.grind.ShulkerWatcher;
import com.betterloka.gui.BetterLokaMenuScreen;
import com.betterloka.stats.ArenaService;
import com.betterloka.stats.NameplateKdService;
import com.betterloka.stats.PlayerStatsService;
import com.betterloka.towns.TownActivityStore;
import com.betterloka.towns.TownInfoReader;
import com.betterloka.towns.TownLogStore;
import com.betterloka.towns.TownLogger;
import com.betterloka.translate.ChatChannels;
import com.betterloka.translate.ChatLog;
import com.betterloka.translate.TranslationService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * Client entry point. Wires up the HTTP layer, the two data sources, the chat capture and the
 * keybind that opens the BetterLoka menu.
 *
 * <p>Everything here is client side — the mod sends nothing to the game server and only ever reads
 * from public HTTP endpoints.
 */
public class BetterLokaClient implements ClientModInitializer {
    /**
     * Registered once at class initialisation; {@code Category.create} throws if the same identifier
     * is registered twice.
     */
    private static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of(BetterLoka.MOD_ID, "main"));

    private static KeyBinding openMenuKey;
    private static KeyBinding shulkerTimerKey;
    private static KeyBinding glowstoneTimerKey;
    private static HttpTransport transport;
    private static LokaApi loka;
    private static EldritchApi eldritch;
    private static MarketApi market;
    private static ArenaApi arena;
    private static TownCache towns;
    private static PlayerStatsService stats;
    private static ArenaService arenaStats;
    private static NameplateKdService nameplateKd;
    private static TranslationService translations;
    private static ChatLog chatLog;
    private static TownLogger townLogger;
    private static TownActivityStore townActivity;
    private static NameHistoryService nameHistory;
    private static MapService map;
    private static MapDataStore mapData;
    private static MapIcons mapIcons;
    private static MapTerrain mapTerrain;
    private static GrindTimers grindTimers;
    private static BetterLokaConfig config;

    @Override
    public void onInitializeClient() {
        config = BetterLokaConfig.load(configDir().resolve("config.json"));

        transport = new HttpTransport();
        loka = new LokaApi(transport);
        eldritch = new EldritchApi(transport);
        market = new MarketApi(transport);
        arena = new ArenaApi(transport);
        towns = new TownCache(loka, configDir().resolve("towns.json"));
        stats = new PlayerStatsService(loka, eldritch, towns, configDir().resolve("fights.json"));
        arenaStats = new ArenaService(arena, configDir().resolve("arena-history.json"));
        nameplateKd = new NameplateKdService(eldritch);
        translations = new TranslationService(transport);
        chatLog = new ChatLog(translations, config);
        townLogger = new TownLogger(loka, towns, config,
                new TownLogStore(configDir().resolve("town-log.json")));
        townLogger.start();
        // The active count is the number Loka deletes towns on, and the only place it exists is the
        // /town info panel. Read passively: the mod never runs the command, it reads a screen the
        // player opened.
        // Mojang withdrew name history in 2022; Laby.net kept its own and has more of it than
        // Loka's own /find does.
        nameHistory = new NameHistoryService(new LabyApi(transport), transport,
                configDir().resolve("name-history.json"));
        map = new MapService(configDir().resolve("waypoints.json"));
        // The map's own data lives on its own, refreshed in the background, so opening the screen
        // is instant and a capture shows up whether or not anybody was looking.
        mapData = new MapDataStore(new DynmapApi(transport), configDir().resolve("mapcache"),
                config.mapRefreshSeconds());
        mapData.start();
        mapIcons = new MapIcons(transport, configDir().resolve("map-icons"));
        mapTerrain = new MapTerrain(transport, configDir().resolve("map-terrain"));
        new WaypointHud(map).register();
        new WaypointWorldRenderer(map, mapIcons).register();
        townActivity = new TownActivityStore(configDir().resolve("town-activity.json"));
        new TownInfoReader(townActivity).register();
        grindTimers = new GrindTimers();
        grindTimers.glowstone().setDurationMillis(config.glowstoneMinutes() * 60_000L);
        new ShulkerWatcher(grindTimers, config).register();
        new GrindHud(grindTimers, config).register();

        // Loka sends its chat as system messages; signed player chat is captured too so the
        // Translator also works on servers that use it. The whole Text is inspected rather than just
        // its string, because Loka marks town and alliance chat by colour and nothing else.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chatLog.record(ChatChannels.read(message));
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                chatLog.record(ChatChannels.read(message)));

        openMenuKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.betterloka.open_menu", GLFW.GLFW_KEY_L, KEY_CATEGORY));
        // G and H are free in vanilla, and both are rebindable from Options -> Controls.
        shulkerTimerKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.betterloka.shulker_timer", GLFW.GLFW_KEY_G, KEY_CATEGORY));
        glowstoneTimerKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.betterloka.glowstone_timer", GLFW.GLFW_KEY_H, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BetterLokaMenuScreen(null));
                }
            }
            while (shulkerTimerKey.wasPressed()) {
                toggleTimer(grindTimers.shulker(), "betterloka.grind.tab.shulker");
            }
            while (glowstoneTimerKey.wasPressed()) {
                toggleTimer(grindTimers.glowstone(), "betterloka.grind.tab.glowstone");
            }
        });

        if ("1".equals(System.getenv("BETTERLOKA_SHOTS"))) {
            DevShots.register();
        }

        BetterLoka.LOGGER.info("BetterLoka {} ready — press the BetterLoka key to open the menu", BetterLoka.VERSION);
    }

    /**
     * The keybind starts a timer, restarts one that is already counting, and stops one that has run
     * out. Restarting matters more than stopping: the key gets pressed on a kill, and the second kill
     * of the night should not be ignored because the first timer is still going.
     */
    private static void toggleTimer(GrindTimer timer, String nameKey) {
        String action;
        if (timer.running() || !timer.finished()) {
            timer.start();
            action = "betterloka.grind.started";
        } else {
            timer.stop();
            action = "betterloka.grind.stopped";
        }
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            // The action bar, not chat: this fires while grinding, and it should not build a log.
            client.player.sendMessage(net.minecraft.text.Text.translatable(action,
                    net.minecraft.text.Text.translatable(nameKey), timer.durationText()), true);
        }
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(BetterLoka.MOD_ID);
    }

    /** Guards the nameplate mixin, which runs for every player every frame. */
    public static boolean isNameplateKdEnabled() {
        return config != null && config.showNameplateKd() && nameplateKd != null;
    }

    public static BetterLokaConfig config() {
        return config;
    }

    public static MarketApi market() {
        return market;
    }

    public static PlayerStatsService stats() {
        return stats;
    }

    public static ArenaService arenaStats() {
        return arenaStats;
    }

    public static GrindTimers grindTimers() {
        return grindTimers;
    }

    public static KeyBinding shulkerTimerKey() {
        return shulkerTimerKey;
    }

    public static KeyBinding glowstoneTimerKey() {
        return glowstoneTimerKey;
    }

    public static TownLogger townLogger() {
        return townLogger;
    }

    public static TownActivityStore townActivity() {
        return townActivity;
    }

    public static NameHistoryService nameHistory() {
        return nameHistory;
    }

    public static MapService map() {
        return map;
    }

    /** Loka's map data, kept current in the background whether or not the screen is open. */
    public static MapDataStore mapData() {
        return mapData;
    }

    public static MapIcons mapIcons() {
        return mapIcons;
    }

    public static MapTerrain mapTerrain() {
        return mapTerrain;
    }

    public static TownCache towns() {
        return towns;
    }

    public static LokaApi lokaApi() {
        return loka;
    }

    /** For screens that need to run a Loka lookup off the render thread. */
    public static java.util.concurrent.ExecutorService lokaExecutor() {
        return loka.executor();
    }

    public static NameplateKdService nameplateKd() {
        return nameplateKd;
    }

    public static TranslationService translations() {
        return translations;
    }

    public static ChatLog chatLog() {
        return chatLog;
    }
}
