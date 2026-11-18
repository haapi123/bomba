package com.betterloka.mixin;

import com.betterloka.gui.DuelVersusScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the duel's VS screen is open, the arena's countdown title and action bar are not drawn by the
 * game: they would land in the middle of the screen, over the players' numbers. The VS screen shows
 * the countdown itself, in a box of its own. Nothing else of the HUD changes, and the moment the
 * screen closes the titles are drawn as usual again.
 */
@Mixin(Hud.class)
public abstract class DuelHudTitleMixin {
    @Inject(method = "extractTitle", at = @At("HEAD"), cancellable = true)
    private void betterloka$hideTitle(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (versusOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "extractOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void betterloka$hideActionBar(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (versusOpen()) {
            ci.cancel();
        }
    }

    private static boolean versusOpen() {
        Minecraft client = Minecraft.getInstance();
        return client.gui != null && client.gui.screen() instanceof DuelVersusScreen;
    }
}
