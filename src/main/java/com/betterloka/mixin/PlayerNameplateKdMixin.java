package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import com.betterloka.gui.EloColors;
import com.betterloka.gui.GuiTheme;
import com.betterloka.gui.PingColors;
import com.betterloka.stats.EloService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Appends a player's Conquest K/D, their Conquest Elo and their ping to the nameplate drawn above
 * their head — each when its switch in the Player Finder is on.
 *
 * <p>Hooks the label at its source — {@link EntityRenderer#getNameTag} is what every renderer
 * asks for the text above an entity — so the badge inherits the game's own nameplate placement,
 * scaling and occlusion, and there is no second text pass.
 */
@Mixin(EntityRenderer.class)
public abstract class PlayerNameplateKdMixin {

    @Inject(method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true)
    private void betterloka$appendKillDeath(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (!(entity instanceof Player)) {
            return;
        }
        boolean showKd = BetterLokaClient.isNameplateKdEnabled();
        boolean showElo = BetterLokaClient.isNameplateEloEnabled();
        boolean showPing = BetterLokaClient.isNameplatePingEnabled();
        if (!showKd && !showElo && !showPing) {
            return;
        }
        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        MutableComponent label = null;
        if (showKd) {
            String account = accountNameOf(entity);
            OptionalDouble ratio = account == null ? OptionalDouble.empty()
                    : BetterLokaClient.nameplateKd().killDeathOf(account);
            if (ratio.isPresent()) {
                label = original.copy().append(badge(ratio.getAsDouble()));
            }
        }
        if (showElo) {
            // By account rather than by name: the leaderboard is keyed on UUIDs, and the lookup is
            // local, so a nameplate costs no request at all.
            EloService.Entry rated = BetterLokaClient.elo().byUuid(entity.getUUID());
            if (rated != null) {
                label = (label != null ? label : original.copy()).append(eloBadge(rated.elo()));
            }
        }
        if (showPing) {
            // Live from the player list the server keeps the client up to date with: no request.
            int ping = pingOf(entity);
            if (ping >= 0) {
                label = (label != null ? label : original.copy()).append(pingBadge(ping));
            }
        }
        if (label != null) {
            cir.setReturnValue(label);
        }
    }

    /** The player's ping as the server last reported it, or -1 when they are not on the player list. */
    private static int pingOf(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener network = client == null ? null : client.getConnection();
        PlayerInfo listed = network == null ? null : network.getPlayerInfo(entity.getUUID());
        return listed == null ? -1 : listed.getLatency();
    }

    /** {@code  | 45ms}, coloured by how much it will be felt. */
    private static MutableComponent pingBadge(int ping) {
        return Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(ping + "ms").withColor(PingColors.color(ping)));
    }

    /**
     * The player's Mojang account name.
     *
     * <p>Deliberately not the nameplate text or {@code getNameForScoreboard}: Loka decorates players
     * with a rank ("Duelist Rezorie"), and looking that up in a stats service finds nothing. The
     * player list carries the undecorated profile, which is what the stats sites are keyed on.
     */
    private static String accountNameOf(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener network = client == null ? null : client.getConnection();
        if (network != null) {
            PlayerInfo listed = network.getPlayerInfo(entity.getUUID());
            if (listed != null && listed.getProfile() != null) {
                String name = listed.getProfile().name();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        String fallback = entity.getScoreboardName();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    /** {@code  | 2068 ELO}, the rating coloured by its band. */
    private static MutableComponent eloBadge(double elo) {
        return Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(String.valueOf(Math.round(elo))).withColor(EloColors.color(elo)))
                .append(Component.literal(" ELO").withStyle(ChatFormatting.GRAY));
    }

    /** {@code  | 3.30}, coloured by how good the ratio is. */
    private static MutableComponent badge(double ratio) {
        return Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(String.format(Locale.ROOT, "%.2f", ratio))
                        .withColor(GuiTheme.nameplateRatioColor(ratio)));
    }
}
