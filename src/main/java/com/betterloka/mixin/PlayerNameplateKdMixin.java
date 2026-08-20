package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import com.betterloka.gui.GuiTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Appends a player's Conquest K/D to the nameplate drawn above their head.
 *
 * <p>The label is built into the render state once per frame, so editing it here means vanilla draws
 * the result — no separate text pass, and the badge inherits the game's own nameplate placement,
 * scaling and occlusion.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerNameplateKdMixin {

    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL"))
    private void betterloka$appendKillDeath(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
        if (state.displayName == null || !BetterLokaClient.isNameplateKdEnabled()) {
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
        state.displayName = state.displayName.copy().append(badge(ratio.getAsDouble()));
    }

    /**
     * The player's Mojang account name.
     *
     * <p>Deliberately not the nameplate text or {@code getNameForScoreboard}: Loka decorates players
     * with a rank ("Duelist Rezorie"), and looking that up in a stats service finds nothing. The
     * player list carries the undecorated profile, which is what the stats sites are keyed on.
     */
    private static String accountNameOf(PlayerLikeEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayNetworkHandler network = client == null ? null : client.getNetworkHandler();
        if (network != null) {
            PlayerListEntry listed = network.getPlayerListEntry(entity.getUuid());
            if (listed != null && listed.getProfile() != null) {
                String name = listed.getProfile().name();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        String fallback = entity.getNameForScoreboard();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    /** {@code  | 3.30}, coloured by how good the ratio is. */
    private static MutableText badge(double ratio) {
        return Text.literal(" | ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(String.format(Locale.ROOT, "%.2f", ratio))
                        .withColor(GuiTheme.nameplateRatioColor(ratio)));
    }
}
