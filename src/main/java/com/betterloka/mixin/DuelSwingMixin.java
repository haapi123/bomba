package com.betterloka.mixin;

import com.betterloka.duel.DuelTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player's own swings for the duel statistics: a left click that swings the arm — at another
 * player or at the air, not at a block being mined. The server shows everybody else's swings, but
 * never a player's own back to them, so this is the one thing counted on this side.
 */
@Mixin(Minecraft.class)
abstract class DuelSwingMixin {
    @Inject(method = "startAttack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;"
                    + "Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
    private void betterloka$swing(CallbackInfoReturnable<Boolean> cir) {
        DuelTracker.onOwnSwing(((Minecraft) (Object) this).hitResult);
    }
}
