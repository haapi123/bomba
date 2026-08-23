package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

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
        HudElementRegistry.addLast(Identifier.of(BetterLoka.MOD_ID, "grind_timers"), this::render);
    }

    private void render(DrawContext context, net.minecraft.client.render.RenderTickCounter tick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options.hudHidden || client.currentScreen != null) {
            return;
        }

        int y = MARGIN;
        y = drawTimer(context, client, y, timers.shulker(), config.shulkerHudEnabled(),
                "betterloka.grind.hud.shulker", SHULKER_COLOR);
        drawTimer(context, client, y, timers.glowstone(), config.glowstoneHudEnabled(),
                "betterloka.grind.hud.glowstone", GLOWSTONE_COLOR);
    }

    /** @return the y for the next line, unchanged when nothing was drawn. */
    private int drawTimer(DrawContext context, MinecraftClient client, int y, GrindTimer timer,
                          boolean enabled, String key, int color) {
        if (!enabled || (!timer.running() && !timer.finished())) {
            return y;
        }
        // "ready" rather than 0:00 once it runs out: the number stops being the useful part then.
        Text line = timer.finished()
                ? Text.translatable(key, Text.translatable("betterloka.grind.ready").getString())
                : Text.translatable(key, timer.remainingText());

        int width = client.textRenderer.getWidth(line);
        int x = context.getScaledWindowWidth() - width - MARGIN;
        context.drawTextWithShadow(client.textRenderer, line, x, y, color);
        return y + LINE_HEIGHT;
    }
}
