package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loka Helper's clear lava: with it on, being in lava leaves the view as clear as a spectator's
 * rather than closing it down to an arm's length of orange.
 *
 * <p>Only the fog the game sets for the camera being in lava is changed, after the game has set it,
 * and only while the player has the option on — it is off until they turn it on.
 */
@Mixin(LavaFogEnvironment.class)
public abstract class ClearLavaMixin {
    @Inject(method = "setupFog", at = @At("TAIL"))
    private void betterloka$clearLava(FogData fog, Camera camera, ClientLevel level, float renderDistance,
                                      DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!BetterLokaClient.isClearLavaEnabled()) {
            return;
        }
        // What the game gives a spectator in lava: the fog pushed out to half the render distance.
        fog.environmentalStart = -8.0f;
        fog.environmentalEnd = renderDistance * 0.5f;
        fog.skyEnd = fog.environmentalEnd;
        fog.cloudEnd = fog.environmentalEnd;
    }
}
