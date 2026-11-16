package com.betterloka.mixin;

import com.betterloka.BetterLokaClient;
import com.betterloka.duel.DuelLog;
import com.betterloka.duel.DuelTracker;
import com.betterloka.gui.DuelResultScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A click on a duel's line in chat opens that duel's screen.
 *
 * <p>The line carries a custom click, {@code betterloka:duel/<number>}. Left alone the game would
 * send it to the server; this catches it first — only ours, by namespace — opens the screen, and
 * sends nothing.
 */
@Mixin(Screen.class)
abstract class DuelChatClickMixin {
    @Inject(method = "defaultHandleGameClickEvent", at = @At("HEAD"), cancellable = true)
    private static void betterloka$openDuel(ClickEvent event, Minecraft client, Screen screen, CallbackInfo ci) {
        if (!(event instanceof ClickEvent.Custom custom)) {
            return;
        }
        int id = DuelTracker.duelId(custom.id());
        if (id < 0) {
            return;
        }
        ci.cancel();
        DuelLog log = BetterLokaClient.duelLog();
        DuelLog.Entry duel = log == null ? null : log.byId(id);
        if (duel != null) {
            client.gui.setScreen(new DuelResultScreen(duel));
        }
    }
}
