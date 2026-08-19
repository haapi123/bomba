package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import com.betterloka.gui.GuiTheme;
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
        OptionalDouble ratio = BetterLokaClient.nameplateKd().killDeathOf(entity.getNameForScoreboard());
        if (ratio.isEmpty()) {
            return;
        }
        state.displayName = state.displayName.copy().append(badge(ratio.getAsDouble()));
    }

    /** {@code  | +3.30}, coloured by how good the ratio is. */
    private static MutableText badge(double ratio) {
        return Text.literal(" | ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(String.format(Locale.ROOT, "%.2f", ratio))
                        .withColor(GuiTheme.nameplateRatioColor(ratio)));
    }
}
