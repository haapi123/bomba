package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import com.betterloka.gui.GuiTheme;
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
 * Appends a player's Conquest K/D to the nameplate drawn above their head.
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
        if (!(entity instanceof Player) || !BetterLokaClient.isNameplateKdEnabled()) {
            return;
        }
        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        String account = accountNameOf(entity);
        if (account == null) {
            return;
        }
        OptionalDouble ratio = BetterLokaClient.nameplateKd().killDeathOf(account);
        if (ratio.isEmpty()) {
            return;
        }
        cir.setReturnValue(original.copy().append(badge(ratio.getAsDouble())));
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

    /** {@code  | 3.30}, coloured by how good the ratio is. */
    private static MutableComponent badge(double ratio) {
        return Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(String.format(Locale.ROOT, "%.2f", ratio))
                        .withColor(GuiTheme.nameplateRatioColor(ratio)));
    }
}
