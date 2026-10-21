package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Draws the running timers in the corner of the screen.
 *
 * <p>The whole point of the timers is knowing where they are without opening anything, so they are
 * shown in the top right and nowhere else. A timer that has not been started draws nothing — an
 * overlay you cannot turn off is a worse deal than no overlay.
 */
public final class GrindHud {
    /** Purple for shulkers, gold for glowstone. */
    private static final int SHULKER_COLOR = 0xFFB86BFF;
    private static final int GLOWSTONE_COLOR = 0xFFFFC93C;

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 11;

    private final GrindTimers timers;
    private final BetterLokaConfig config;

    public GrindHud(GrindTimers timers, BetterLokaConfig config) {
        this.timers = timers;
        this.config = config;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "grind_timers"), this::render);
    }

    private void render(GuiGraphicsExtractor context, net.minecraft.client.DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui.hud.isHidden() || client.gui.screen() != null) {
            return;
        }

        int y = MARGIN;
        y = drawTimer(context, client, y, timers.shulker(), config.shulkerHudEnabled(),
                "betterloka.grind.hud.shulker", SHULKER_COLOR);
        drawTimer(context, client, y, timers.glowstone(), config.glowstoneHudEnabled(),
                "betterloka.grind.hud.glowstone", GLOWSTONE_COLOR);
    }

    /** @return the y for the next line, unchanged when nothing was drawn. */
    private int drawTimer(GuiGraphicsExtractor context, Minecraft client, int y, GrindTimer timer,
                          boolean enabled, String key, int color) {
        if (!enabled || (!timer.running() && !timer.finished())) {
            return y;
        }
        // "ready" rather than 0:00 once it runs out: the number stops being the useful part then.
        Component line = timer.finished()
                ? Component.translatable(key, Component.translatable("betterloka.grind.ready").getString())
                : Component.translatable(key, timer.remainingText());

        int width = client.font.width(line);
        int x = context.guiWidth() - width - MARGIN;
        context.text(client.font, line, x, y, color);
        return y + LINE_HEIGHT;
    }
}
