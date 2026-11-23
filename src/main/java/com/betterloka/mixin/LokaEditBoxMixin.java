package com.betterloka.mixin;

import com.betterloka.gui.HelperUi;
import com.betterloka.gui.LokaStyled;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** On a BetterLoka screen, a text box's frame is drawn in BetterLoka's style; the text is the game's. */
@Mixin(EditBox.class)
abstract class LokaEditBoxMixin {
    @WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void betterloka$lokaFrame(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                      int x, int y, int width, int height, Operation<Void> original) {
        if (LokaStyled.active()) {
            EditBox self = (EditBox) (Object) this;
            HelperUi.field(graphics, x, y, width, height, self.isFocused(), self.active);
        } else {
            original.call(graphics, pipeline, sprite, x, y, width, height);
        }
    }
}
