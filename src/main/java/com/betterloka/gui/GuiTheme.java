package com.betterloka.gui;

import com.betterloka.stats.PlayerTrait;
import com.betterloka.translate.ChatChannel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared colours and small drawing helpers, so every BetterLoka screen looks like the same mod. */
public final class GuiTheme {
    public static final int TEXT = HelperUi.TEXT;
    public static final int MUTED = HelperUi.MUTED;
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
    public static void chip(GuiGraphicsExtractor context, int x, int y, int width, int height, int color) {
        int fill = (color & 0x00FFFFFF) | 0x30000000;
        int radius = Math.min(4, height / 2);
        HelperUi.round(context, x, y, width, height, radius, color);
        HelperUi.round(context, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), 0xFF0E1522);
        HelperUi.round(context, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    /**
     * A fight's turnout: how many warped in, against how many signed up for it.
     *
     * <p>Not "online": Loka publishes nothing about who is logged in — a town member's record is
     * literally {@code {"subowner": false}} — so the pair here is who came against who was down for
     * it, which its battle records do say.
     */
    public static final int TURNOUT_PRESENT = 0xFF8CE99A;
    public static final int TURNOUT_SIGNED_UP = 0xFFB03A3A;

    /** Enchantments, in the violet the game already associates with them. */
    public static final int ENCHANT = 0xFFB9A0FF;

    /** Town and alliance chat, kept close to the colours the server already uses for them. */
    public static final int TOWN_CHAT = 0xFF6FDC7C;
    public static final int ALLIANCE_CHAT = 0xFF62D8E8;

    /** A card's edge and the lines between its parts, in the window's navy. */
    public static final int CARD_EDGE = HelperUi.CARD_EDGE;
    public static final int SEPARATOR = 0xFF243048;

    private GuiTheme() {
    }

    /** A card: a shade lighter than the window, with rounded corners and a one pixel edge. */
    public static void panel(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        HelperUi.framed(context, x, y, width, height, 4, HelperUi.CARD, HelperUi.CARD_EDGE);
    }

    /** A card in a colour of its own: its edge, with the fill a dim wash of it over the card. */
    public static void tintedPanel(GuiGraphicsExtractor context, int x, int y, int width, int height, int color) {
        HelperUi.framed(context, x, y, width, height, 4, HelperUi.CARD, HelperUi.mix(HelperUi.CARD_EDGE, color, 0.7f));
        HelperUi.round(context, x + 1, y + 1, width - 2, height - 2, 3, (color & 0x00FFFFFF) | 0x18000000);
    }

    public static void separator(GuiGraphicsExtractor context, int x, int y, int width) {
        context.fill(x, y, x + width, y + 1, SEPARATOR);
    }

    /** A label/value pair, with the value right-aligned inside {@code width}. */
    public static void statRow(GuiGraphicsExtractor context, Font textRenderer, int x, int y, int width,
                               String label, String value, int valueColor) {
        context.text(textRenderer, label, x, y, MUTED);
        int valueWidth = textRenderer.width(value);
        context.text(textRenderer, value, x + width - valueWidth, y, valueColor);
    }

    public static void progressBar(GuiGraphicsExtractor context, int x, int y, int width, int height, float fraction) {
        int radius = Math.min(2, height / 2);
        HelperUi.round(context, x, y, width, height, radius, 0xFF1B2538);
        int filled = Math.round(Math.max(0f, Math.min(1f, fraction)) * (width - 2));
        if (filled > 0) {
            HelperUi.round(context, x + 1, y + 1, filled, height - 2, Math.max(0, radius - 1), ACCENT);
        }
    }

    /**
     * K/D colour for a nameplate, from the one table in {@link KdColors}.
     *
     * <p>Returned without an alpha channel, which is what {@code Text.withColor} takes. Screen
     * drawing wants ARGB — use {@link #ratioColor(double)} there, or the text renders invisible.
     */
    public static int nameplateRatioColor(double ratio) {
        return KdColors.getKdColor(ratio);
    }

    /** The same K/D colour, opaque, for drawing into a screen. */
    public static int ratioColor(double ratio) {
        return KdColors.getKdColorArgb(ratio);
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
