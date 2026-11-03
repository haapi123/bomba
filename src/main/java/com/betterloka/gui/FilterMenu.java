package com.betterloka.gui;

import com.betterloka.api.model.MarketSort;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The Market's Filter button and the menu it opens: newest, oldest, most expensive, cheapest.
 *
 * <p>One button rather than four, so the choice takes no room until it is wanted; the menu drops
 * over whatever is below it, and goes away on a choice or a click anywhere else.
 */
final class FilterMenu {
    static final int HEIGHT = 18;
    private static final int OPTION_HEIGHT = 14;

    private final Consumer<MarketSort> onChange;
    private MarketSort sort;
    private boolean open;

    private int x;
    private int y;
    private int width;

    FilterMenu(MarketSort initial, Consumer<MarketSort> onChange) {
        this.sort = initial;
        this.onChange = onChange;
    }

    MarketSort sort() {
        return sort;
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        open = false;
    }

    /** Where the button goes; the menu opens under it. */
    void place(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    int x() {
        return x;
    }

    int width() {
        return width;
    }

    /** The width the button wants, so its label fits whatever order is chosen. */
    static int preferredWidth(Font font) {
        int widest = 0;
        for (MarketSort value : MarketSort.values()) {
            widest = Math.max(widest, font.width(label(value)));
        }
        return widest + 22;
    }

    private static Component label(MarketSort value) {
        return Component.translatable("betterloka.market.filter", Component.translatable(value.key()));
    }

    void render(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, width, HEIGHT);
        button(context, x, y, width, HEIGHT, hovered || open);
        Component text = label(sort);
        context.text(font, text, x + 6, y + (HEIGHT - 8) / 2, GuiTheme.TEXT);
        // A small caret, drawn rather than typed: the font is not sure to have one.
        int cx = x + width - 8;
        int cy = y + HEIGHT / 2 - 1;
        for (int row = 0; row < 3; row++) {
            int half = open ? row : 2 - row;
            context.fill(cx - half, cy + row, cx + half + 1, cy + row + 1, GuiTheme.MUTED);
        }
    }

    /** The open menu, drawn last so it lies over the rows under it. */
    void renderMenu(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        context.nextStratum();
        MarketSort[] values = MarketSort.values();
        int top = y + HEIGHT + 1;
        int height = values.length * OPTION_HEIGHT + 4;
        context.fill(x, top, x + width, top + height, 0xF0101016);
        outline(context, x, top, width, height, 0xFF5A5A70);
        for (int i = 0; i < values.length; i++) {
            int rowY = top + 2 + i * OPTION_HEIGHT;
            boolean hovered = inside(mouseX, mouseY, x, rowY, width, OPTION_HEIGHT);
            if (hovered) {
                context.fill(x + 1, rowY, x + width - 1, rowY + OPTION_HEIGHT, 0x40FFFFFF);
            }
            boolean chosen = values[i] == sort;
            if (chosen) {
                context.fill(x + 4, rowY + 5, x + 8, rowY + 9, GuiTheme.LIVE);
            }
            context.text(font, Component.translatable(values[i].key()), x + 12, rowY + 3,
                    chosen ? GuiTheme.LIVE : hovered ? GuiTheme.ACCENT : GuiTheme.TEXT);
        }
    }

    /** @return whether the click was the menu's: the button, an option, or a click that shut it. */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (open) {
            MarketSort[] values = MarketSort.values();
            int top = y + HEIGHT + 1;
            for (int i = 0; i < values.length; i++) {
                int rowY = top + 2 + i * OPTION_HEIGHT;
                if (inside(mouseX, mouseY, x, rowY, width, OPTION_HEIGHT)) {
                    open = false;
                    if (values[i] != sort) {
                        sort = values[i];
                        onChange.accept(sort);
                    }
                    return true;
                }
            }
            open = false;
            // A click on the button itself only closes it; anywhere else it closes and is spent,
            // so it does not also open whatever row happened to be under the menu.
            return true;
        }
        if (inside(mouseX, mouseY, x, y, width, HEIGHT)) {
            open = true;
            return true;
        }
        return false;
    }

    /** The middle of the {@code i}th option of the open menu, for the dev harness. */
    int[] optionCentre(int i) {
        return new int[]{x + width / 2, y + HEIGHT + 1 + 2 + i * OPTION_HEIGHT + OPTION_HEIGHT / 2};
    }

    /** The middle of the button. */
    int[] centre() {
        return new int[]{x + width / 2, y + HEIGHT / 2};
    }

    private static boolean inside(double px, double py, int x, int y, int width, int height) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    /** A button drawn by hand, in the same style as the map's. */
    static void button(GuiGraphicsExtractor context, int x, int y, int width, int height, boolean hovered) {
        context.fill(x, y, x + width, y + height, hovered ? MapStyle.BUTTON_FILL_HOVER : MapStyle.BUTTON_FILL);
        outline(context, x, y, width, height, hovered ? MapStyle.BUTTON_BORDER_HOVER : MapStyle.BUTTON_BORDER);
    }

    static void outline(GuiGraphicsExtractor context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }
}
