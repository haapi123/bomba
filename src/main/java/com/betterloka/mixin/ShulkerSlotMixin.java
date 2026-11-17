package com.betterloka.mixin;

import com.betterloka.helper.ShulkerScanner;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shulker Scanner: tints a shulker box's slot before its item is drawn, in every container screen.
 * The screen has already moved the drawing to its own corner, so the slot's x and y are where it is.
 */
@Mixin(AbstractContainerScreen.class)
abstract class ShulkerSlotMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void betterloka$tintShulker(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ShulkerScanner.drawSlot(graphics, slot);
    }
}
