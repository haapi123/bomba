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

    // --- the game's own widgets, as BetterLoka draws them on its screens ---

    /**
     * A button: navy with a lighter edge, brighter under the pointer, gold-edged when it is the chosen
     * one of a row of tabs, darker when it cannot be pressed.
     *
     * @param hover how far into its hover look it is, 0 to 1
     */
    public static void button(GuiGraphicsExtractor context, int x, int y, int width, int height, float hover,
                              boolean active, boolean chosen) {
        int radius = height >= 16 ? 4 : 3;
        int fill;
        int edge;
        if (!active) {
            fill = 0xFF141B28;
            edge = 0xFF222C40;
        } else if (chosen) {
            fill = mix(0xFF2A2716, 0xFF3A3318, hover);
            edge = mix(0xFFB8913A, GOLD, hover);
        } else {
            fill = mix(0xFF1C2638, 0xFF283652, hover);
            edge = mix(0xFF2E3C58, 0xFF4A5E88, hover);
        }
        round(context, x, y + 1, width, height, radius, 0x40000000);
        framed(context, x, y, width, height, radius, fill, edge);
        if (active) {
            // A faint light along the top, so it reads as raised.
            context.fill(x + radius, y + 1, x + width - radius, y + 2, alpha(0xFFFFFF, chosen ? 0x22 : 0x14));
        }
        if (chosen) {
            context.fill(x + radius + 2, y + height - 3, x + width - radius - 2, y + height - 2, GOLD);
        }
    }

    /** A text box: darker than the window, its edge gold while it is being typed in. */
    public static void field(GuiGraphicsExtractor context, int x, int y, int width, int height, boolean focused,
                             boolean active) {
        int edge = focused ? 0xFFC9A04A : active ? 0xFF2E3C58 : 0xFF222C40;
        framed(context, x, y, width, height, 3, active ? 0xFF0B111C : 0xFF0E141F, edge);
        if (focused) {
            context.fill(x + 3, y + height - 2, x + width - 3, y + height - 1, alpha(GOLD, 0x60));
        }
    }

    // --- on the game's screen, over the world ---

    /** Fill and edge of a box drawn over the world: the window's navy, see-through enough to play past. */
    public static final int HUD_FILL = 0xB80D131F;
    public static final int HUD_EDGE = 0xC0304060;

    /** A box over the world, with {@code accent} (if not 0) as a short bar down its left side. */
    public static void hudPanel(GuiGraphicsExtractor context, int x, int y, int width, int height, int accent) {
        framed(context, x, y, width, height, Math.min(4, height / 2), HUD_FILL, HUD_EDGE);
        if (accent != 0) {
            context.fill(x + 1, y + 3, x + 3, y + height - 3, accent);
        }
    }

    /** Eases out: quick at first, settling at the end. */
    public static float easeOut(float t) {
        float s = Math.max(0f, Math.min(1f, t));
        return 1 - (1 - s) * (1 - s) * (1 - s);
    }
}
