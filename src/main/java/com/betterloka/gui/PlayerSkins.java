package com.betterloka.gui;

import com.betterloka.BetterLoka;
import com.betterloka.BetterLokaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A Loka player's skin, for drawing them in a screen.
 *
 * <p>Somebody on the same server is already known to the game, skin and all, so theirs is used as
 * it is. Anybody else is looked up the way the game looks up a player it meets: their profile from
 * Mojang by UUID, then the skin through the game's own skin manager, which downloads and caches it.
 * Until it lands — or if it never does — the default skin the game would give that UUID stands in.
 *
 * <p>Only Mojang's own profile service is asked, the same one the game asks for every player it
 * sees; nothing is sent anywhere else.
 */
public final class PlayerSkins {
    /** One lookup per player per session; a failed one is forgotten so the next search retries. */
    private static final Map<UUID, Supplier<PlayerSkin>> LOOKUPS = new ConcurrentHashMap<>();

    private PlayerSkins() {
    }

    /** The skin to draw for {@code uuid}: the real one as soon as it is known, a default before. */
    public static Supplier<PlayerSkin> of(UUID uuid) {
        if (uuid == null) {
            PlayerSkin fallback = DefaultPlayerSkin.getDefaultSkin();
            return () -> fallback;
        }
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(uuid);
            if (info != null) {
                return info::getSkin;
            }
        }
        return LOOKUPS.computeIfAbsent(uuid, PlayerSkins::fetch);
    }

    private static Supplier<PlayerSkin> fetch(UUID uuid) {
        AtomicReference<PlayerSkin> skin = new AtomicReference<>(DefaultPlayerSkin.get(uuid));
        Minecraft client = Minecraft.getInstance();
        CompletableFuture
                .supplyAsync(() -> client.services().profileResolver().fetchById(uuid),
                        BetterLokaClient.backgroundExecutor())
                .thenCompose(profile -> profile.isPresent()
                        ? client.getSkinManager().get(profile.get())
                        : CompletableFuture.completedFuture(Optional.<PlayerSkin>empty()))
                .whenComplete((loaded, error) -> {
                    if (error == null && loaded != null && loaded.isPresent()) {
                        skin.set(loaded.get());
                    } else {
                        BetterLoka.LOGGER.debug("No skin for {}", uuid, error);
                        LOOKUPS.remove(uuid);
                    }
                });
        return skin::get;
    }
}
