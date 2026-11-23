package com.betterloka.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing for BetterLoka's own-style windows: rounded panels, soft glows and colour blending, drawn
 * with plain fills so they stay as sharp as the game's own pixels at every GUI scale.
 */
public final class HelperUi {
    // The window's palette: a deep navy, as the BetterLoka logo's sea, with its gold for what is chosen.
    public static final int WINDOW = 0xF20D131F;
    public static final int WINDOW_EDGE = 0xFF27344E;
    public static final int HEADER_TOP = 0xFF16233A;
    public static final int HEADER_BOTTOM = 0xFF0F1828;
    public static final int SIDEBAR = 0xFF0A1019;
    public static final int CARD = 0xFF131B2A;
    public static final int CARD_HOVER = 0xFF172134;
    public static final int CARD_EDGE = 0xFF212C42;
    public static final int CARD_EDGE_HOVER = 0xFF34435F;
    public static final int ICON_TILE = 0xFF1B2538;
    public static final int GOLD = 0xFFF2BE4E;
    public static final int GOLD_LIGHT = 0xFFFFE29A;
    public static final int ON = 0xFF33B36B;
    public static final int OFF = 0xFF39425A;
    public static final int TEXT = 0xFFF0F3F8;
    public static final int MUTED = 0xFF8C97AD;
    public static final int FAINT = 0xFF5C6780;

    private HelperUi() {
    }

    /** A filled rectangle with corners rounded to {@code radius}, a row of fills at a time. */
    public static void round(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        if (r == 0) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }
        context.fill(x, y + r, x + width, y + height - r, color);
        for (int i = 0; i < r; i++) {
            int inset = inset(r, i);
            context.fill(x + inset, y + i, x + width - inset, y + i + 1, color);
            context.fill(x + inset, y + height - 1 - i, x + width - inset, y + height - i, color);
        }
    }

    /** How far row {@code row} of a corner of radius {@code r} starts in from the side. */
    private static int inset(int r, int row) {
        double dy = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** A rounded rectangle with a one pixel edge in another colour. */
    public static void framed(GuiGraphicsExtractor context, int x, int y, int width, int height, int radius,
                              int fill, int edge) {
        round(context, x, y, width, height, radius, edge);
        round(context, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    /** A filled ellipse, for glows and the shadows of things that hover. */
    public static void ellipse(GuiGraphicsExtractor context, int cx, int cy, int rx, int ry, int color) {
        if (rx <= 0 || ry <= 0) {
            return;
        }
        for (int row = -ry; row < ry; row++) {
            double t = (row + 0.5) / ry;
            int half = (int) Math.round(rx * Math.sqrt(Math.max(0, 1 - t * t)));
            if (half > 0) {
                context.fill(cx - half, cy + row, cx + half, cy + row + 1, color);
            }
        }
    }

    /** A soft glow: a few ellipses, each wider and fainter than the last. */
    public static void glow(GuiGraphicsExtractor context, int cx, int cy, int rx, int ry, int rgb, int alpha) {
        for (int i = 3; i >= 1; i--) {
            int a = alpha * (4 - i) / 4;
            ellipse(context, cx, cy, rx + i * 4, ry + i * 3, (a << 24) | (rgb & 0xFFFFFF));
        }
    }

    /** A line of dots from one point to another, every {@code step} pixels, {@code offset} along. */
    public static void dotted(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1, int step, float offset,
                              int color) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        if (length < 1) {
            return;
        }
        for (double d = offset % step; d < length; d += step) {
            int x = (int) Math.round(x0 + (x1 - x0) * d / length);
            int y = (int) Math.round(y0 + (y1 - y0) * d / length);
            context.fill(x, y, x + 1, y + 1, color);
        }
    }

    /** {@code a} turned into {@code b} by {@code t}, channel by channel, alpha too. */
    public static int mix(int a, int b, float t) {
        float s = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * s) & 0xFF) << shift;
        }
        return out;
    }

    /** The same colour with another alpha. */
    public static int alpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
    }

    /** Lighter by {@code amount}, towards white. */
    public static int lighten(int color, float amount) {
        return mix(color, color | 0x00FFFFFF, amount);
    }

    /** Eases out: quick at first, settling at the end. */
    public static float easeOut(float t) {
        float s = Math.max(0f, Math.min(1f, t));
        return 1 - (1 - s) * (1 - s) * (1 - s);
    }
}
