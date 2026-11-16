package com.betterloka.mixin;

import com.betterloka.duel.DuelTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the server tells every player near a fight, handed to the duel statistics: swings (the swing
 * animation packet), crits (the crit animation), hits (damage events, which name who hit whom), sweeps
 * (the sweep sound, played where the attacker stands) and deaths. Read only — nothing is changed or cancelled.
 *
 * <p>Each hook runs right after the handler has moved itself onto the game thread: a handler first
 * runs on the network thread, where that call throws to reschedule it, so this sees each packet once.
 */
@Mixin(ClientPacketListener.class)
abstract class DuelPacketsMixin {
    private static final String ON_GAME_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;"
            + "ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;"
            + "Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleAnimate", at = @At(value = "INVOKE", target = ON_GAME_THREAD, shift = At.Shift.AFTER))
    private void betterloka$animate(ClientboundAnimatePacket packet, CallbackInfo ci) {
        DuelTracker.onAnimate(packet.getId(), packet.getAction());
    }

    /**
     * Somebody else's arm swing. Minecraft 26 sends these on a packet of their own — the animate
     * packet's old "swing" number now means waking up — so this is where an opponent's swings are.
     */
    @Inject(method = "handleSwingAnimation", at = @At(value = "INVOKE", target = ON_GAME_THREAD, shift = At.Shift.AFTER))
    private void betterloka$swing(ClientboundSwingAnimationPacket packet, CallbackInfo ci) {
        DuelTracker.onSwing(packet.entityId(), packet.hand() == net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    @Inject(method = "handleDamageEvent", at = @At(value = "INVOKE", target = ON_GAME_THREAD, shift = At.Shift.AFTER))
    private void betterloka$damage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        DuelTracker.onDamage(packet.entityId(), packet.sourceCauseId(), packet.sourceDirectId());
    }

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = ON_GAME_THREAD, shift = At.Shift.AFTER))
    private void betterloka$sound(ClientboundSoundPacket packet, CallbackInfo ci) {
        DuelTracker.onSound(packet.getSound().value().location(), packet.getX(), packet.getY(), packet.getZ());
    }

    @Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = ON_GAME_THREAD, shift = At.Shift.AFTER))
    private void betterloka$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        DuelTracker.onEntityEvent(packet, packet.getEventId());
    }
}
