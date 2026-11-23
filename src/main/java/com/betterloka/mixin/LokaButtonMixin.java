package com.betterloka.mixin;

import com.betterloka.gui.HelperUi;
import com.betterloka.gui.LokaStyled;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On a BetterLoka screen, the game's buttons are drawn in BetterLoka's navy instead of stone, easing
 * into their hover look; a yellow-labelled one is the chosen tab of its row. Everywhere else they are
 * left exactly as the game draws them.
 */
@Mixin(AbstractButton.class)
abstract class LokaButtonMixin {
    @Unique
    private static final TextColor CHOSEN = TextColor.fromLegacyFormat(ChatFormatting.YELLOW);

    @Unique
    private float betterloka$hover;
    @Unique
    private long betterloka$lastFrame;

    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    private void betterloka$lokaStyle(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!LokaStyled.active()) {
            return;
        }
        AbstractButton self = (AbstractButton) (Object) this;
        long now = System.nanoTime();
        float dt = betterloka$lastFrame == 0 ? 1f : Math.min(0.1f, (now - betterloka$lastFrame) / 1_000_000_000f);
        betterloka$lastFrame = now;
        float target = self.active && self.isHovered() ? 1f : 0f;
        betterloka$hover += (target - betterloka$hover) * (1 - (float) Math.exp(-16 * dt));
        boolean chosen = CHOSEN.equals(self.getMessage().getStyle().getColor());
        HelperUi.button(graphics, self.getX(), self.getY(), self.getWidth(), self.getHeight(), betterloka$hover,
                self.active, chosen);
        ci.cancel();
    }
}
