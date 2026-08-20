package com.betterloka;

import com.betterloka.api.EldritchApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.betterloka.api.MarketApi;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.data.TownCache;
import com.betterloka.gui.BetterLokaMenuScreen;
import com.betterloka.stats.NameplateKdService;
import com.betterloka.stats.PlayerStatsService;
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
    private static HttpTransport transport;
    private static LokaApi loka;
    private static EldritchApi eldritch;
    private static MarketApi market;
    private static TownCache towns;
    private static PlayerStatsService stats;
    private static NameplateKdService nameplateKd;
    private static TranslationService translations;
    private static ChatLog chatLog;
    private static BetterLokaConfig config;

    @Override
    public void onInitializeClient() {
        config = BetterLokaConfig.load(configDir().resolve("config.json"));

        transport = new HttpTransport();
        loka = new LokaApi(transport);
        eldritch = new EldritchApi(transport);
        market = new MarketApi(transport);
        towns = new TownCache(loka);
        stats = new PlayerStatsService(loka, eldritch, towns);
        nameplateKd = new NameplateKdService(eldritch);
        translations = new TranslationService(transport);
        chatLog = new ChatLog(translations, config);

        // Loka sends its chat as system messages; signed player chat is captured too so the
        // Translator also works on servers that use it.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chatLog.record(message.getString());
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                chatLog.record(message.getString()));

        openMenuKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.betterloka.open_menu", GLFW.GLFW_KEY_L, KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BetterLokaMenuScreen(null));
                }
            }
        });

        BetterLoka.LOGGER.info("BetterLoka {} ready — press the BetterLoka key to open the menu", BetterLoka.VERSION);
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
