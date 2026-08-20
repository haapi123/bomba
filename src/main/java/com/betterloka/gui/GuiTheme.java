package com.betterloka.gui;

import com.betterloka.stats.PlayerTrait;
import com.betterloka.translate.ChatChannel;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Shared colours and small drawing helpers, so every BetterLoka screen looks like the same mod. */
public final class GuiTheme {
    public static final int TEXT = 0xFFE8E8EC;
    public static final int MUTED = 0xFF9A9AA8;
    public static final int ACCENT = 0xFF4FC3F7;
    public static final int GOOD = 0xFF7BD88F;
    public static final int BAD = 0xFFE06C75;
    public static final int LIVE = 0xFFFFB454;

    /**
     * A pill with a coloured outline, as the trait chips are drawn.
     *
     * <p>The fill is a dimmed version of the outline rather than a flat dark, so a row of chips reads
     * as coloured at a glance without the text having to fight its own background.
     */
    public static void chip(DrawContext context, int x, int y, int width, int height, int color) {
        int fill = (color & 0x00FFFFFF) | 0x30000000;
        // Corners left unpainted, which reads as rounded at this size.
        context.fill(x + 1, y, x + width - 1, y + height, fill);
        context.fill(x, y + 1, x + 1, y + height - 1, fill);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, fill);

        context.fill(x + 1, y, x + width - 1, y + 1, color);
        context.fill(x + 1, y + height - 1, x + width - 1, y + height, color);
        context.fill(x, y + 1, x + 1, y + height - 1, color);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    /** Enchantments, in the violet the game already associates with them. */
    public static final int ENCHANT = 0xFFB9A0FF;

    /** Town and alliance chat, kept close to the colours the server already uses for them. */
    public static final int TOWN_CHAT = 0xFF6FDC7C;
    public static final int ALLIANCE_CHAT = 0xFF62D8E8;

    private static final int PANEL_FILL = 0xB0101015;
    private static final int PANEL_BORDER = 0xFF2C2C38;
    private static final int SEPARATOR = 0xFF33333F;

    private GuiTheme() {
    }

    /** A dark rounded-looking card with a one pixel border. */
    public static void panel(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, PANEL_FILL);
        context.fill(x, y, x + width, y + 1, PANEL_BORDER);
        context.fill(x, y + height - 1, x + width, y + height, PANEL_BORDER);
        context.fill(x, y, x + 1, y + height, PANEL_BORDER);
        context.fill(x + width - 1, y, x + width, y + height, PANEL_BORDER);
    }

    public static void separator(DrawContext context, int x, int y, int width) {
        context.fill(x, y, x + width, y + 1, SEPARATOR);
    }

    /** A label/value pair, with the value right-aligned inside {@code width}. */
    public static void statRow(DrawContext context, TextRenderer textRenderer, int x, int y, int width,
                               String label, String value, int valueColor) {
        context.drawTextWithShadow(textRenderer, label, x, y, MUTED);
        int valueWidth = textRenderer.getWidth(value);
        context.drawTextWithShadow(textRenderer, value, x + width - valueWidth, y, valueColor);
    }

    public static void progressBar(DrawContext context, int x, int y, int width, int height, float fraction) {
        context.fill(x, y, x + width, y + height, 0xFF23232B);
        int filled = Math.round(Math.max(0f, Math.min(1f, fraction)) * (width - 2));
        if (filled > 0) {
            context.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, ACCENT);
        }
    }

    /**
     * K/D colour, matching how Loka players already read the number: below 1.0 is red, 1.0 and above
     * is yellow, and 3.0 and above is gold.
     *
     * <p>Returned without an alpha channel, which is what {@code Text.withColor} takes. Screen
     * drawing wants ARGB — use {@link #ratioColor(double)} there, or the text renders invisible.
     */
    public static int nameplateRatioColor(double ratio) {
        if (ratio >= 3.0) {
            return 0xFFD700;
        }
        return ratio >= 1.0 ? 0xFFFF55 : 0xFF5555;
    }

    /** The same K/D colour as {@link #nameplateRatioColor}, opaque, for drawing into a screen. */
    public static int ratioColor(double ratio) {
        return 0xFF000000 | nameplateRatioColor(ratio);
    }

    /** The colour a trait chip is drawn in: green, yellow, red. */
    public static int traitColor(PlayerTrait.Level level) {
        return switch (level) {
            case GOOD -> 0xFF5FD37A;
            case MIXED -> 0xFFE8C349;
            case POOR -> 0xFFE0605C;
        };
    }

    /** The colour a chat channel is drawn in. */
    public static int channelColor(ChatChannel channel) {
        return switch (channel) {
            case TOWN -> TOWN_CHAT;
            case ALLIANCE -> ALLIANCE_CHAT;
            case PUBLIC -> ACCENT;
        };
    }
}
