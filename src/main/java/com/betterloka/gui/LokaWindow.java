package com.betterloka.gui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * The window every BetterLoka module sits in, as Loka Helper's: a navy panel over the dimmed game,
 * a header band with the logo, the module's name and what it is for, and a close button in its
 * corner. A screen lays itself out inside {@link #left()}, {@link #top()}, {@link #right()} and
 * {@link #bottom()}, draws the window from {@code extractBackground}, so its own widgets and text
 * land on top, and passes clicks through {@link #mouseClicked} first.
 */
public final class LokaWindow {
    public static final int HEADER = 30;
    public static final int PAD = 8;
    private static final int MARGIN_X = 8;
    private static final int MARGIN_Y = 6;
    private static final int CLOSE = 16;

    private final Runnable close;
    private final Component title;
    private final Component subtitle;
    private int maxWidth = Integer.MAX_VALUE;
    private int maxHeight = Integer.MAX_VALUE;
    private int x;
    private int y;
    private int w;
    private int h;
    private int screenWidth;
    private int screenHeight;

    private int reserved;
    private float closeHover;
    private long lastFrame;

    /**
     * @param close what the close button does, normally the screen's {@code onClose}
     */
    public LokaWindow(Component title, Component subtitle, Runnable close) {
        this.title = title;
        this.subtitle = subtitle;
        this.close = close;
    }

    /** The biggest the window grows to on a big screen; it shrinks to fit a small one. */
    public LokaWindow max(int width, int height) {
        this.maxWidth = width;
        this.maxHeight = height;
        return this;
    }

    /** As {@link #max}, from the room the content needs inside the padding. */
    public LokaWindow content(int width, int height) {
        return max(width == Integer.MAX_VALUE ? width : width + 2 * PAD,
                height == Integer.MAX_VALUE ? height : height + HEADER + 2 * PAD);
    }

    /** Works out where the window goes; call from the screen's {@code init}. */
    public void layout(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        w = Math.max(120, Math.min(screenWidth - 2 * MARGIN_X, maxWidth));
        h = Math.max(80, Math.min(screenHeight - 2 * MARGIN_Y, maxHeight));
        x = (screenWidth - w) / 2;
        y = (screenHeight - h) / 2;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /** Where the content starts and ends, inside the padding and below the header. */
    public int left() {
        return x + PAD;
    }

    public int top() {
        return y + HEADER + PAD;
    }

    public int right() {
        return x + w - PAD;
    }

    public int bottom() {
        return y + h - PAD;
    }

    public int innerWidth() {
        return right() - left();
    }

    public int innerHeight() {
        return bottom() - top();
    }

    public int centreX() {
        return x + w / 2;
    }

    /** Keeps {@code width} of the header's right side for the screen's own things, clear of the title. */
    public void reserveHeader(int width) {
        this.reserved = width;
    }

    /** Where the title and subtitle end, for anything that wants the header's room beside them. */
    public int titleEnd(Font font) {
        int title = font.width(this.title.copy().withStyle(ChatFormatting.BOLD));
        int sub = subtitle == null ? 0 : font.width(subtitle);
        return Math.min(headerRight() - reserved - 4, x + 33 + Math.max(title, sub));
    }

    /** Where things drawn into the header's right side end, left of the close button. */
    public int headerRight() {
        return closeX() - 6;
    }

    private int closeX() {
        return x + w - CLOSE - 7;
    }

    private int closeY() {
        return y + (HEADER - CLOSE) / 2;
    }

    /** Draws the dimming, the window and its header. */
    public void draw(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;

        context.fill(0, 0, screenWidth, screenHeight, 0x66000000);
        HelperUi.round(context, x - 1, y + 2, w + 2, h + 2, 6, 0x50000000);
        HelperUi.framed(context, x, y, w, h, 5, HelperUi.WINDOW, HelperUi.WINDOW_EDGE);

        // A band a shade lighter than the window, under the title.
        context.fillGradient(x + 1, y + 3, x + w - 1, y + HEADER, HelperUi.HEADER_TOP, HelperUi.HEADER_BOTTOM);
        context.fillGradient(x + 3, y + 1, x + w - 3, y + 3, HelperUi.HEADER_TOP, HelperUi.HEADER_TOP);
        context.fill(x + 1, y + HEADER, x + w - 1, y + HEADER + 1, HelperUi.WINDOW_EDGE);
        logo(context, x + 8, y + 5, 20);
        int textW = headerRight() - reserved - (x + 33) - 4;
        Component name = title.copy().withStyle(ChatFormatting.BOLD);
        context.text(font, font.width(name) <= textW ? name.getVisualOrderText()
                : Component.literal(font.plainSubstrByWidth(name.getString(), textW)).withStyle(ChatFormatting.BOLD)
                .getVisualOrderText(), x + 33, y + 6, HelperUi.TEXT);
        if (subtitle != null) {
            context.text(font, font.plainSubstrByWidth(subtitle.getString(), textW), x + 33, y + 17, HelperUi.MUTED);
        }

        int bx = closeX();
        int by = closeY();
        boolean hover = mouseX >= bx && mouseX < bx + CLOSE && mouseY >= by && mouseY < by + CLOSE;
        closeHover += ((hover ? 1f : 0f) - closeHover) * (1 - (float) Math.exp(-18 * dt));
        HelperUi.round(context, bx, by, CLOSE, CLOSE, 3, HelperUi.mix(0x00000000, 0xFFB3423F, closeHover));
        context.centeredText(font, "×", bx + CLOSE / 2 + 1, by + 4, HelperUi.mix(HelperUi.MUTED, 0xFFFFFFFF, closeHover));
        if (hover) {
            context.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    /** A rounded label in the header, right-aligned at {@code right}; returns where it starts. */
    public int headerChip(GuiGraphicsExtractor context, Font font, Component text, int right, int color) {
        int cw = font.width(text) + 12;
        int cx = right - cw;
        int cy = y + (HEADER - 14) / 2;
        HelperUi.framed(context, cx, cy, cw, 14, 7, 0xFF141E30, 0xFF2B3A56);
        context.text(font, text, cx + 6, cy + 3, color);
        return cx;
    }

    /** @return whether the click closed the window */
    public boolean mouseClicked(double mouseX, double mouseY) {
        int bx = closeX();
        int by = closeY();
        if (mouseX >= bx && mouseX < bx + CLOSE && mouseY >= by && mouseY < by + CLOSE) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            close.run();
            return true;
        }
        return false;
    }

    /**
     * The BetterLoka logo, from the picture made for the current GUI scale, so it is drawn pixel for
     * pixel rather than squeezed from a bigger one.
     */
    public static void logo(GuiGraphicsExtractor context, int x, int y, int size) {
        if (size <= 0) {
            return;
        }
        int scale = Math.max(1, Math.min(6, Minecraft.getInstance().getWindow().getGuiScale()));
        int pixels = 26 * scale;
        Identifier logo = Identifier.fromNamespaceAndPath("betterloka", "textures/gui/logo_" + scale + ".png");
        context.blit(RenderPipelines.GUI_TEXTURED, logo, x, y, 0f, 0f, size, size, pixels, pixels, pixels, pixels,
                0xFFFFFFFF);
    }
}
