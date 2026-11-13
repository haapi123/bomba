package com.betterloka;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.betterloka.api.MarketApi;
import com.betterloka.assistant.AssistantClient;
import com.betterloka.fights.FightWatch;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.data.TownCache;
import com.betterloka.grind.GrindHud;
import com.betterloka.grind.GrindTimer;
import com.betterloka.grind.GrindTimers;
import com.betterloka.grind.ShrineIcons;
import com.betterloka.grind.ShrineTimers;
import com.betterloka.grind.ShrineTracker;
import com.betterloka.grind.ShrineWaypoints;
import com.betterloka.grind.ShulkerWatcher;
import com.betterloka.gui.BetterLokaMenuScreen;
import com.betterloka.helper.GuiScaleController;
import com.betterloka.map.Continent;
import com.betterloka.map.DynmapApi;
import com.betterloka.map.MapDataStore;
import com.betterloka.map.MapIcons;
import com.betterloka.map.MapService;
import com.betterloka.map.MapTerrain;
import com.betterloka.map.WaypointHud;
import com.betterloka.map.WaypointWorldRenderer;
import com.betterloka.stats.ArenaService;
import com.betterloka.stats.BattleIndex;
import com.betterloka.stats.NameplateKdService;
import com.betterloka.stats.PlayerStatsService;
import com.betterloka.towns.TownActivityStore;
import com.betterloka.towns.TownInfoReader;
import com.betterloka.towns.TownLogStore;
import com.betterloka.towns.TownLogger;
import com.betterloka.translate.ChatChannels;
import com.betterloka.translate.ChatLog;
import com.betterloka.translate.TranslationService;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.resources.Identifier;

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
    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "main"));

    private static KeyMapping openMenuKey;
    private static KeyMapping shulkerTimerKey;
    private static KeyMapping glowstoneTimerKey;
    private static KeyMapping coordsPositionKey;
    private static KeyMapping tlKey;
    private static HttpTransport transport;
    private static LokaApi loka;
    private static EldritchApi eldritch;
    private static MarketApi market;
    private static ArenaApi arena;
    private static TownCache towns;
    private static PlayerStatsService stats;
    private static ArenaService arenaStats;
    private static BattleIndex battleIndex;
    private static NameplateKdService nameplateKd;
    private static com.betterloka.stats.EloService elo;
    private static TranslationService translations;
    private static ChatLog chatLog;
    private static TownLogger townLogger;
    private static TownActivityStore townActivity;
    private static MapService map;
    private static MapDataStore mapData;
    private static MapIcons mapIcons;
    private static MapTerrain mapTerrain;
    private static com.betterloka.map.TerrainDownload terrainDownload;
    private static GrindTimers grindTimers;
    private static ShrineTracker shrines;
    private static ShrineIcons shrineIcons;
    private static BetterLokaConfig config;
    private static FightWatch fights;
    private static com.betterloka.conquest.StandingsService standings;

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
        // Conquest Elo: one static file from loka-elo.pages.dev, kept on disk and checked for a newer
        // version every half hour. It also knows every name each account has had, which is what finds
        // a player searched for under an old one.
        elo = new com.betterloka.stats.EloService(transport, configDir().resolve("elo.json"),
                transport.backgroundExecutor());
        elo.start();
        stats.useElo(elo);
        arenaStats = new ArenaService(arena, configDir().resolve("arena-history-v2.json"));
        // Loka's battle archive is the only place a career splits by format. Built on demand rather
        // than at startup — it is 77 MB the first time — and kept once built.
        battleIndex = new BattleIndex(loka, configDir().resolve("battle-index.json"));
        // What the Player Finder's "Nemesis of" and "Previously known as" kept, before both went.
        deleteQuietly(configDir().resolve("nemesis.json"));
        deleteQuietly(configDir().resolve("name-history.json"));
        nameplateKd = new NameplateKdService(eldritch);
        translations = new TranslationService(transport);
        chatLog = new ChatLog(translations, config);
        townLogger = new TownLogger(loka, towns, config,
                new TownLogStore(configDir().resolve("town-log.json")));
        townLogger.start();
        // The active count is the number Loka deletes towns on, and the only place it exists is the
        // /town info panel. Read passively: the mod never runs the command, it reads a screen the
        // player opened.
        map = new MapService(configDir().resolve("waypoints.json"));
        // The market and the ranked ladders are fetched ahead of time while the player is online, so
        // neither screen opens on a wait: the last whole market comes back off disk at once, and a
        // fresh one replaces it every few minutes in the background lane.
        market.useCache(configDir().resolve("market-cache.json"));
        java.util.concurrent.ScheduledExecutorService warmup =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "BetterLoka-Warmup");
                    thread.setDaemon(true);
                    return thread;
                });
        warmup.scheduleWithFixedDelay(BetterLokaClient::warmUp, 20, 60, java.util.concurrent.TimeUnit.SECONDS);
        // And straight after joining a server, rather than up to a minute later.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> warmup.schedule(BetterLokaClient::warmUp, 3,
                        java.util.concurrent.TimeUnit.SECONDS));

        // The declared fights, for the map: asked for only while the map is open, every fifteen
        // seconds, so a fight placed while somebody is looking is on it within a quarter of a minute.
        fights = new FightWatch(new FightWatch.Source() {
            @Override
            public java.util.List<com.betterloka.api.model.ScheduledFight> fights() throws com.betterloka.api.ApiException {
                java.util.List<com.betterloka.api.model.ScheduledFight> staged = DevDiagnostics.stagedFights(loka);
                return staged != null ? staged : loka.fetchScheduledFights();
            }

            @Override
            public com.betterloka.api.model.TerritoryDetails territory(String territoryId)
                    throws com.betterloka.api.ApiException {
                return loka.fetchTerritory(territoryId);
            }
        }, loka.executor());
        // Who stands where in conquest, for the calculator and the fights on the map: read only
        // while one of them is on screen, and then at most once a minute.
        standings = new com.betterloka.conquest.StandingsService(new com.betterloka.conquest.StandingsService.Source() {
            @Override
            public java.util.List<com.betterloka.api.model.LokaAlliance> alliances() throws com.betterloka.api.ApiException {
                return loka.fetchAlliances();
            }

            @Override
            public java.util.List<com.betterloka.api.model.LokaTown> towns() {
                return towns.all();
            }

            @Override
            public com.betterloka.api.model.LokaTown town(String townId) throws com.betterloka.api.ApiException {
                return loka.findTownById(townId);
            }
        }, loka.executor());
        // The map's own data lives on its own, refreshed in the background, so opening the screen
        // is instant and a capture shows up whether or not anybody was looking.
        mapData = new MapDataStore(new DynmapApi(transport), configDir().resolve("mapcache"),
                config.mapRefreshSeconds());
        mapData.start();
        mapIcons = new MapIcons(transport, configDir().resolve("map-icons"));
        mapTerrain = new MapTerrain(transport, configDir().resolve("map-terrain"));
        // The terrain paints the claims into each tile as it decodes it, from the same store the
        // screen reads, so the two can never disagree about who holds what.
        mapTerrain.setClaims(world -> {
            Continent continent = Continent.ofWorld(world);
            return continent == null ? java.util.List.of() : mapData.snapshot(continent).territories();
        });
        terrainDownload = new com.betterloka.map.TerrainDownload(transport, mapTerrain);
        warmMapBase();
        new WaypointHud(map).register();
        new WaypointWorldRenderer(map, mapIcons).register();
        townActivity = new TownActivityStore(configDir().resolve("town-activity.json"));
        new TownInfoReader(townActivity).register();
        grindTimers = new GrindTimers();
        grindTimers.glowstone().setDurationMillis(config.glowstoneMinutes() * 60_000L);
        new ShulkerWatcher(grindTimers, config).register();
        new GrindHud(grindTimers, config).register();
        // The Nether shrines: markers over each, the ground around each, and a twenty minute
        // timer started by the server's own "was purged!" line.
        shrines = new ShrineTracker(new ShrineTimers(configDir().resolve("shrines.json"),
                () -> config.shrineCooldownMinutes() * 60_000L), config);
        shrines.register();
        shrineIcons = new ShrineIcons();
        new ShrineWaypoints(shrines, shrineIcons, config).register();
        // The inventory and the hotbar get their own GUI scales. Off by default, and it hands the
        // player's own setting back on the way out of a world.
        new GuiScaleController(config).register();

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

        openMenuKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.betterloka.open_menu", InputConstants.KEY_L, KEY_CATEGORY));
        // G and H are free in vanilla, and both are rebindable from Options -> Controls.
        shulkerTimerKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.betterloka.shulker_timer", InputConstants.KEY_G, KEY_CATEGORY));
        glowstoneTimerKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.betterloka.glowstone_timer", InputConstants.KEY_H, KEY_CATEGORY));
        // P as asked, though vanilla has Social Interactions there too: both fire, and this one opens
        // its screen after, so the coordinates win. Rebindable from Options -> Controls.
        coordsPositionKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.betterloka.coords_position", InputConstants.KEY_P, KEY_CATEGORY));
        // No key until the player picks one, and it does nothing until they also turn it on.
        tlKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.betterloka.tl", InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));
        new com.betterloka.helper.CoordsHud(config).register();
        new com.betterloka.helper.ImportantWaypointsHud(config).register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new BetterLokaMenuScreen(null));
                }
            }
            while (shulkerTimerKey.consumeClick()) {
                toggleTimer(grindTimers.shulker(), "betterloka.grind.tab.shulker");
            }
            while (glowstoneTimerKey.consumeClick()) {
                toggleTimer(grindTimers.glowstone(), "betterloka.grind.tab.glowstone");
            }
            while (coordsPositionKey.consumeClick()) {
                var open = client.gui.screen();
                if (open == null || open instanceof net.minecraft.client.gui.screens.social.SocialInteractionsScreen) {
                    client.gui.setScreen(new com.betterloka.gui.CoordsHudPositionScreen(null));
                }
            }
            while (tlKey.consumeClick()) {
                // One press, one /tl, sent as if typed — and only with the option on and the game in
                // view, never from a menu.
                if (config.tlKeyEnabled() && client.gui.screen() == null && client.player != null) {
                    client.player.connection.sendCommand("tl");
                }
            }
        });

        if ("1".equals(System.getenv("BETTERLOKA_SHOTS"))) {
            DevShots.register();
        }
        if (java.util.Set.of("1", "map", "waypoints", "shrines", "translator", "market", "fights", "helper", "finder", "v41", "v42", "v43", "v46").contains(String.valueOf(System.getenv("BETTERLOKA_DIAG")))) {
            DevDiagnostics.register();
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
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client != null && client.player != null) {
            // The action bar, not chat: this fires while grinding, and it should not build a log.
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(action,
                    net.minecraft.network.chat.Component.translatable(nameKey), timer.durationText()));
        }
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(BetterLoka.MOD_ID);
    }

    /** Guards the nameplate mixin, which runs for every player every frame. */
    /** Conquest Elo, from loka-elo.pages.dev. */
    public static com.betterloka.stats.EloService elo() {
        return elo;
    }

    public static boolean isNameplatePingEnabled() {
        return config != null && config.showNameplatePing();
    }

    public static boolean isNameplateEloEnabled() {
        return config != null && config.showNameplateElo() && elo != null;
    }

    public static boolean isNameplateKdEnabled() {
        return config != null && config.showNameplateKd() && nameplateKd != null;
    }

    public static BetterLokaConfig config() {
        return config;
    }

    private static AssistantClient assistant;

    /**
     * The Loka Assistant client. It logs in by telling Mojang the player is "joining" the assistant
     * server's one-time id, as joining any server does; the token goes only to Mojang.
     */
    public static synchronized AssistantClient assistant() {
        if (assistant == null) {
            assistant = new AssistantClient(
                    () -> AssistantClient.server(System.getenv()),
                    () -> Minecraft.getInstance().getUser().getName(),
                    serverId -> {
                        Minecraft client = Minecraft.getInstance();
                        User user = client.getUser();
                        client.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
                    });
        }
        return assistant;
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

    /** Every battle on Loka's books, folded into per-player totals. */
    public static BattleIndex battleIndex() {
        return battleIndex;
    }

    public static GrindTimers grindTimers() {
        return grindTimers;
    }

    public static ShrineTracker shrines() {
        return shrines;
    }

    public static ShrineIcons shrineIcons() {
        return shrineIcons;
    }

    /** The key that opens the coordinates' position screen. */
    public static KeyMapping coordsPositionKey() {
        return coordsPositionKey;
    }

    /** The key that sends /tl. */
    public static KeyMapping tlKey() {
        return tlKey;
    }

    /** Whether lava should be seen through; read from the fog code on the render thread. */
    public static boolean isClearLavaEnabled() {
        return config != null && config.clearLava();
    }

    public static KeyMapping shulkerTimerKey() {
        return shulkerTimerKey;
    }

    public static KeyMapping glowstoneTimerKey() {
        return glowstoneTimerKey;
    }

    public static TownLogger townLogger() {
        return townLogger;
    }

    public static TownActivityStore townActivity() {
        return townActivity;
    }

    /** Removes a file the mod no longer uses; one that cannot be removed is left, harmlessly. */
    private static void deleteQuietly(java.nio.file.Path file) {
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not remove {}", file, e);
        }
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

    /**
     * Fetches every continent's coarsest ground in the background, once its claims are known.
     *
     * <p>Waits on the claims because they are what says where a continent is. The map store reads
     * them off disk within a moment of starting and off the network a few seconds later, so the loop
     * tries every few seconds for a while and stops once each continent has been done.
     */
    private static void warmMapBase() {
        Thread warmer = new Thread(() -> {
            java.util.Set<Continent> done = java.util.EnumSet.noneOf(Continent.class);
            for (int attempt = 0; attempt < 60 && done.size() < Continent.values().length; attempt++) {
                try {
                    Thread.sleep(attempt == 0 ? 8_000 : 5_000);
                } catch (InterruptedException e) {
                    return;
                }
                for (Continent continent : Continent.values()) {
                    if (done.contains(continent)) {
                        continue;
                    }
                    var territories = mapData.snapshot(continent).territories();
                    if (territories.isEmpty()) {
                        continue;
                    }
                    int queued = mapTerrain.warmBase(continent,
                            com.betterloka.map.TerrainDownload.Bounds.of(territories));
                    BetterLoka.LOGGER.debug("[betterloka] queued {} base tiles for {}", queued, continent);
                    done.add(continent);
                }
            }
        }, "BetterLoka-MapWarm");
        warmer.setDaemon(true);
        warmer.start();
    }

    /** The whole-continent map download, which the map screen starts and reports on. */
    public static com.betterloka.map.TerrainDownload terrainDownload() {
        return terrainDownload;
    }

    /**
     * How old the market may get, while the player is online, before it is fetched again unasked.
     *
     * <p>Ten minutes, not five: a whole market is forty-five requests against Loka's budget of about
     * one a second, and a sweep nobody asked for every five minutes kept that budget low just when a
     * player pressed Refresh and wanted all of it.
     */
    private static final long MARKET_PREFETCH_MILLIS = 10 * 60 * 1000L;

    /**
     * Keeps what the screens open on ready: a minute after another, while connected to a server.
     * Nothing is fetched in the menus.
     */
    private static void warmUp() {
        try {
            if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
                return;
            }
            arenaStats.prewarm();
            market.sweep(MARKET_PREFETCH_MILLIS);
            elo.refreshIfDue();
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Warm-up failed", e);
        }
    }

    /** The declared fights, which the map polls while it is open. */
    public static FightWatch fights() {
        return fights;
    }

    public static TownCache towns() {
        return towns;
    }

    /** The conquest standings, which the calculator and the map's fights poll while they are open. */
    public static com.betterloka.conquest.StandingsService standings() {
        return standings;
    }

    public static LokaApi lokaApi() {
        return loka;
    }

    /** For screens that need to run a Loka lookup off the render thread. */
    public static java.util.concurrent.ExecutorService lokaExecutor() {
        return loka.executor();
    }

    /** The lane for short background fan-out, such as reading several fight pages at once. */
    public static java.util.concurrent.ExecutorService lokaBulkExecutor() {
        return transport.bulkExecutor();
    }

    /** The lane for long jobs nobody is watching, such as building the battle archive. */
    public static java.util.concurrent.ExecutorService backgroundExecutor() {
        return transport.backgroundExecutor();
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
