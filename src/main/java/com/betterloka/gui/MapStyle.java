package com.betterloka.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Every colour, size and spacing the Loka Map screen draws with, in one place.
 *
 * <p>The screen itself decides what to draw and this decides how it looks, so changing the map's
 * appearance is a matter of editing numbers here rather than hunting through the drawing code.
 */
public final class MapStyle {
    private MapStyle() {
    }

    // --- the map itself ---

    /** Deep water: what lies under everything, the way Loka's map shows the gaps between land. */
    public static final int OCEAN = 0xFF17263A;

    /**
     * How much of a claim's colour survives over the ground.
     *
     * <p>The ground is the point of the map — the desert, the lava, the coastline — so the claim
     * colours sit over it as a wash rather than a coat of paint, and the borders carry the reading.
     */
    public static final int FILL_ALPHA_NEUTRAL = 0x30;
    public static final int FILL_ALPHA_OWNED = 0x4A;
    public static final int FILL_ALPHA_SELECTED = 0x66;

    /**
     * Neutral ground is outlined faintly and held ground firmly.
     *
     * <p>That contrast is the point: with every hex outlined alike, the eye has to read the fills to
     * find where somebody's land begins.
     */
    public static final int BORDER_NEUTRAL = 0x55000000;
    public static final int BORDER_NEUTRAL_WIDTH = 1;

    /** Inside a town's own land the line is hairline; the edge of its holding is drawn heavily. */
    public static final int BORDER_INNER_WIDTH = 1;
    public static final int BORDER_OUTER_WIDTH = 2;

    /**
     * A dark rim under a town's outer border.
     *
     * <p>Loka's ground runs from near-white desert to black volcanic rock inside a single territory,
     * and no single colour reads on both. The rim gives every border its own dark backing so the
     * town colour only ever has to contrast with that.
     */
    public static final int BORDER_RIM = 0xC0000000;
    public static final int BORDER_RIM_WIDTH = 4;

    public static final int BORDER_SELECTED = 0xFFFFFFFF;
    public static final int BORDER_SELECTED_WIDTH = 2;

    /** Loka's markers, at the size its own map draws them. */
    public static final int ICON_SIZE = 8;

    // --- zoom ---

    /**
     * World blocks per screen pixel. Smaller is closer in.
     *
     * <p>The far end has to be far enough that "Fit continent" can actually fit one. Kalros spans
     * 5800 blocks north to south and needs about 44 blocks to the pixel on a small window; at the
     * old limit of 30 the button clamped, the island stayed cropped, and zooming out did nothing.
     */
    public static final double MIN_BLOCKS_PER_PIXEL = 0.5;
    public static final double MAX_BLOCKS_PER_PIXEL = 64;

    /** One press of a zoom button, or one notch of the wheel. */
    public static final double ZOOM_STEP = 1.25;

    /** Space left around the island when a continent is first opened. */
    public static final double FIT_MARGIN = 1.12;

    public static final int ZOOM_BUTTON_SIZE = 16;
    public static final int ZOOM_BUTTON_GAP = 2;
    public static final int ZOOM_BUTTON_MARGIN = 6;

    public static final int BUTTON_FILL = 0xC81A1A22;
    public static final int BUTTON_FILL_HOVER = 0xE0343442;
    public static final int BUTTON_BORDER = 0xFF4A4A5A;
    public static final int BUTTON_BORDER_HOVER = 0xFF7A7A92;
    public static final int BUTTON_TEXT = 0xFFE8E8EC;
    public static final int BUTTON_TEXT_DISABLED = 0xFF5A5A68;

    // --- the information card ---

    public static final int CARD_FILL = 0xE60D0D12;
    public static final int CARD_SEPARATOR = 0x40FFFFFF;
    public static final int CARD_PADDING = 6;
    public static final int CARD_ROW_HEIGHT = 11;
    public static final int CARD_SECTION_GAP = 4;
    public static final int CARD_MIN_WIDTH = 120;

    /** How far the card sits from the cursor, and how close to the screen edge it may come. */
    public static final int CARD_CURSOR_OFFSET = 12;
    public static final int CARD_SCREEN_MARGIN = 4;

    /** The secondary section's text, as a fraction of the normal size. */
    public static final float SMALL_SCALE = 0.75f;

    /** The swatch that shows a town's colour beside its name. */
    public static final int SWATCH_WIDTH = 3;
    public static final int SWATCH_HEIGHT = 8;
    public static final int SWATCH_GAP = 4;

    // --- drawing ---

    /**
     * The card's body: translucent dark, corners cut back a pixel so they read as rounded, and a
     * border in whatever colour the thing being described is.
     */
    public static void card(DrawContext context, int x, int y, int width, int height,
                            int borderColor) {
        int right = x + width;
        int bottom = y + height;

        context.fill(x + 2, y, right - 2, bottom, CARD_FILL);
        context.fill(x, y + 2, x + 2, bottom - 2, CARD_FILL);
        context.fill(right - 2, y + 2, right, bottom - 2, CARD_FILL);
        context.fill(x + 1, y + 1, x + 2, y + 2, CARD_FILL);
        context.fill(right - 2, y + 1, right - 1, y + 2, CARD_FILL);
        context.fill(x + 1, bottom - 2, x + 2, bottom - 1, CARD_FILL);
        context.fill(right - 2, bottom - 2, right - 1, bottom - 1, CARD_FILL);

        context.fill(x + 2, y, right - 2, y + 1, borderColor);
        context.fill(x + 2, bottom - 1, right - 2, bottom, borderColor);
        context.fill(x, y + 2, x + 1, bottom - 2, borderColor);
        context.fill(right - 1, y + 2, right, bottom - 2, borderColor);
        context.fill(x + 1, y + 1, x + 2, y + 2, borderColor);
        context.fill(right - 2, y + 1, right - 1, y + 2, borderColor);
        context.fill(x + 1, bottom - 2, x + 2, bottom - 1, borderColor);
        context.fill(right - 2, bottom - 2, right - 1, bottom - 1, borderColor);
    }

    /** A square button with a hover state, drawn by hand so it can sit over the map. */
    public static void button(DrawContext context, TextRenderer textRenderer, int x, int y,
                              int size, String label, boolean hovered, boolean enabled) {
        context.fill(x, y, x + size, y + size, hovered && enabled ? BUTTON_FILL_HOVER : BUTTON_FILL);
        int border = hovered && enabled ? BUTTON_BORDER_HOVER : BUTTON_BORDER;
        context.fill(x, y, x + size, y + 1, border);
        context.fill(x, y + size - 1, x + size, y + size, border);
        context.fill(x, y, x + 1, y + size, border);
        context.fill(x + size - 1, y, x + size, y + size, border);

        int width = textRenderer.getWidth(label);
        context.drawTextWithShadow(textRenderer, label,
                x + (size - width) / 2, y + (size - textRenderer.fontHeight) / 2 + 1,
                enabled ? BUTTON_TEXT : BUTTON_TEXT_DISABLED);
    }

    /** Text at the secondary size, for the side notes under the main rows. */
    public static void small(DrawContext context, TextRenderer textRenderer, Text text,
                            int x, int y, int color) {
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate((float) x, (float) y);
        matrices.scale(SMALL_SCALE, SMALL_SCALE);
        context.drawTextWithShadow(textRenderer, text, 0, 0, color);
        matrices.popMatrix();
    }

    public static int smallWidth(TextRenderer textRenderer, Text text) {
        return Math.round(textRenderer.getWidth(text) * SMALL_SCALE);
    }

    public static int smallHeight(TextRenderer textRenderer) {
        return Math.round((textRenderer.fontHeight + 1) * SMALL_SCALE);
    }
}
