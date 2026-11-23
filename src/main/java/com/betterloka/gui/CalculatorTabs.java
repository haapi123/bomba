package com.betterloka.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The tabs across the top of Loka Calculator: Strength, for conquest fights, and Brewer Calculator,
 * for a potion machine. Each is a screen of its own; the module opens whichever was open last.
 */
public final class CalculatorTabs {
    public enum Tab {
        STRENGTH("betterloka.calculator.tab_strength"),
        BREWER("betterloka.calculator.tab_brewer");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    public static final int HEIGHT = 17;
    private static final int GAP = 4;
    private static final int ICON = 12;

    private static Tab last = Tab.STRENGTH;

    private CalculatorTabs() {
    }

    /** The calculator as the player left it. */
    public static Screen open(Screen parent) {
        return last == Tab.BREWER ? new BrewerCalculatorScreen(parent) : new LokaCalculatorScreen(parent);
    }

    static int tabWidth(Font font, Tab tab) {
        return font.width(Component.translatable(tab.key)) + ICON + 14;
    }

    /** How much of the header the tabs take. */
    public static int totalWidth(Font font) {
        int width = -GAP;
        for (Tab tab : Tab.values()) {
            width += tabWidth(font, tab) + GAP;
        }
        return width;
    }

    /** Where the tabs start: at the right of the window's header, by the close button. */
    public static int left(Font font, LokaWindow window) {
        return window.headerRight() - totalWidth(font);
    }

    private static int top(LokaWindow window) {
        return window.y() + (LokaWindow.HEADER - HEIGHT) / 2;
    }

    public static void draw(GuiGraphicsExtractor context, Font font, LokaWindow window, Tab active,
                            int mouseX, int mouseY) {
        last = active;
        int x = left(font, window);
        int ty = top(window);
        for (Tab tab : Tab.values()) {
            int width = tabWidth(font, tab);
            boolean on = tab == active;
            boolean hover = !on && mouseX >= x && mouseX < x + width && mouseY >= ty && mouseY < ty + HEIGHT;
            HelperUi.button(context, x, ty, width, HEIGHT, hover ? 1f : 0f, true, on);
            var pose = context.pose();
            pose.pushMatrix();
            pose.translate(x + 5, ty + (HEIGHT - ICON) / 2f);
            pose.scale(ICON / 16f, ICON / 16f);
            context.item(new ItemStack(tab == Tab.STRENGTH ? Items.IRON_SWORD : Items.BREWING_STAND), 0, 0);
            pose.popMatrix();
            context.text(font, Component.translatable(tab.key), x + ICON + 9, ty + (HEIGHT - 8) / 2,
                    on ? HelperUi.GOLD_LIGHT : hover ? GuiTheme.TEXT : GuiTheme.MUTED);
            x += width + GAP;
        }
    }

    /**
     * Opens the tab clicked, if one was.
     *
     * @return whether the click was on a tab
     */
    public static boolean click(Font font, LokaWindow window, Tab active, double mouseX, double mouseY, Screen parent) {
        int ty = top(window);
        if (mouseY < ty || mouseY >= ty + HEIGHT) {
            return false;
        }
        int x = left(font, window);
        for (Tab tab : Tab.values()) {
            int width = tabWidth(font, tab);
            if (mouseX >= x && mouseX < x + width) {
                if (tab != active) {
                    last = tab;
                    Minecraft.getInstance().gui.setScreen(open(parent));
                }
                return true;
            }
            x += width + GAP;
        }
        return false;
    }

    /** Development only: which tab the module opens next. */
    public static void devSet(Tab tab) {
        last = tab;
    }
}
