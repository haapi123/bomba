package com.betterloka;

import com.betterloka.api.LokaApi;
import com.betterloka.data.BattleSyncService;
import com.betterloka.data.TownCache;
import com.betterloka.gui.BetterLokaMenuScreen;
import com.betterloka.stats.PlayerStatsService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * Client entry point. Wires up the API client, the battle history cache and the keybind that opens
 * the BetterLoka menu.
 *
 * <p>Everything here is client side — the mod sends nothing to the game server and only ever reads
 * from Loka's public HTTP API.
 */
public class BetterLokaClient implements ClientModInitializer {
    /**
     * Registered once at class initialisation; {@code Category.create} throws if the same identifier
     * is registered twice.
     */
    private static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of(BetterLoka.MOD_ID, "main"));

    private static KeyBinding openMenuKey;
    private static LokaApi api;
    private static TownCache towns;
    private static BattleSyncService sync;
    private static PlayerStatsService stats;

    @Override
    public void onInitializeClient() {
        api = new LokaApi();
        towns = new TownCache(api);
        sync = new BattleSyncService(api, cacheFile());
        stats = new PlayerStatsService(api, sync, towns);

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

    private static Path cacheFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(BetterLoka.MOD_ID).resolve("battles.bin");
    }

    public static BattleSyncService sync() {
        return sync;
    }

    public static PlayerStatsService stats() {
        return stats;
    }
}
