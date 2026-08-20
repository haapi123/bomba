package com.betterloka.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * Scrolling for a rectangular region of a screen, with a draggable scrollbar down its right edge.
 *
 * <p>Screens own their layout rather than using a list widget, so this handles the arithmetic and
 * the bar; the screen still draws its own content and calls {@link #contentTop()} to know where to
 * start. Mouse events are forwarded from the screen.
 */
public final class ScrollPanel {
    /** Width of the bar's track, including the gap between it and the content. */
    public static final int BAR_WIDTH = 6;

    private static final int TRACK_COLOR = 0x40FFFFFF;
    private static final int THUMB_COLOR = 0xFF5A5A6E;
    private static final int THUMB_HOVER_COLOR = 0xFF8A8AA0;
    private static final int MIN_THUMB_HEIGHT = 16;

    private int x;
    private int y;
    private int width;
    private int height;

    private int scroll;
    private int contentHeight;

    private boolean dragging;
    /** Distance from the top of the thumb to where the drag started, so the thumb does not jump. */
    private double dragGrabOffset;

    /** Positions the viewport. Call from the screen's {@code init}, and whenever it resizes. */
    public void setViewport(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        clamp();
    }

    /** Tells the panel how tall the drawn content turned out, so the bar can size itself. */
    public void setContentHeight(int contentHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        clamp();
    }

    /** The y to start drawing content at, i.e. the top of the viewport shifted by the scroll. */
    public int contentTop() {
        return y - scroll;
    }

    public int viewportTop() {
        return y;
    }

    public int viewportBottom() {
        return y + height;
    }

    /** Content width, i.e. the viewport minus the room the bar needs when one is showing. */
    public int contentWidth() {
        return needsBar() ? width - BAR_WIDTH : width;
    }

    public void reset() {
        scroll = 0;
        dragging = false;
    }

    public boolean needsBar() {
        return contentHeight > height;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - height);
    }

    private void clamp() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (!contains(mouseX, mouseY) || !needsBar()) {
            return false;
        }
        scroll -= (int) (verticalAmount * 14);
        clamp();
        return true;
    }

    /** @return true when the press landed on the scrollbar and started a drag. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        if (!needsBar() || mouseX < barX() || mouseX > barX() + BAR_WIDTH || mouseY < y || mouseY > y + height) {
            return false;
        }
        int thumbHeight = thumbHeight();
        int thumbY = thumbY(thumbHeight);
        if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
            dragging = true;
            dragGrabOffset = mouseY - thumbY;
        } else {
            // Clicking the track jumps the thumb to the pointer and grabs it from the middle.
            dragging = true;
            dragGrabOffset = thumbHeight / 2.0;
            scrollToThumbTop(mouseY - dragGrabOffset, thumbHeight);
        }
        return true;
    }

    public boolean mouseDragged(double mouseY) {
        if (!dragging) {
            return false;
        }
        scrollToThumbTop(mouseY - dragGrabOffset, thumbHeight());
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private void scrollToThumbTop(double thumbTop, int thumbHeight) {
        int travel = height - thumbHeight;
        if (travel <= 0) {
            scroll = 0;
            return;
        }
        double fraction = (thumbTop - y) / travel;
        scroll = (int) Math.round(Math.max(0, Math.min(1, fraction)) * maxScroll());
        clamp();
    }

    private int barX() {
        return x + width - BAR_WIDTH + 1;
    }

    private int thumbHeight() {
        return Math.max(MIN_THUMB_HEIGHT, (int) ((long) height * height / Math.max(1, contentHeight)));
    }

    private int thumbY(int thumbHeight) {
        int max = maxScroll();
        int travel = height - thumbHeight;
        return max == 0 ? y : y + (int) Math.round((double) scroll / max * travel);
    }

    /** Draws the bar. Call after the content, and outside any scissor covering the viewport. */
    public void render(DrawContext context, double mouseX, double mouseY) {
        if (!needsBar()) {
            return;
        }
        int barX = barX();
        int thumbHeight = thumbHeight();
        int thumbY = thumbY(thumbHeight);

        context.fill(barX, y, barX + BAR_WIDTH - 2, y + height, TRACK_COLOR);
        boolean hovered = dragging
                || (mouseX >= barX && mouseX <= barX + BAR_WIDTH && mouseY >= y && mouseY <= y + height);
        context.fill(barX, thumbY, barX + BAR_WIDTH - 2, thumbY + thumbHeight,
                hovered ? THUMB_HOVER_COLOR : THUMB_COLOR);
    }
}
