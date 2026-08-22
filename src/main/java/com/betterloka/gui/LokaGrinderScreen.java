package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.grind.GrindTimer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Timers for the things worth grinding on Loka.
 *
 * <p>The timers live outside the screen, so shutting the GUI does not stop them — a seventeen minute
 * countdown that only ran while you were looking at it would be no use at all.
 */
public class LokaGrinderScreen extends Screen {
    private enum Tab {
        SHULKER("betterloka.grind.tab.shulker"),
        GLOWSTONE("betterloka.grind.tab.glowstone");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int ROW_HEIGHT = 11;
    private static final int CARD_PADDING = 6;
    private static final int CARD_GAP = 6;

    private static final int TAB_ROW_Y = 26;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = TAB_ROW_Y + TAB_ROW_HEIGHT + 8;

    /** The square in the corner of the card that starts and stops the countdown. */
    private static final int BOX_SIZE = 12;

    private final Screen parent;
    private Tab tab = Tab.SHULKER;

    /** Where the start box was drawn this frame, so a click can find it. */
    private int boxX;
    private int boxY;

    public LokaGrinderScreen(Screen parent) {
        super(Text.translatable("betterloka.module.loka_grinder"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();

        int half = (width - 4) / 2;
        int x = left;
        for (Tab value : Tab.values()) {
            Tab target = value;
            int thisWidth = value == Tab.GLOWSTONE ? left + width - x : half;
            addDrawableChild(ButtonWidget.builder(tabLabel(value), button -> selectTab(target))
                    .dimensions(x, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            x += half + 4;
        }

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    private Text tabLabel(Tab value) {
        Text label = Text.translatable(value.key);
        return value == tab ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    private void selectTab(Tab target) {
        if (tab == target) {
            return;
        }
        tab = target;
        clearAndInit();
    }

    private static GrindTimer shulker() {
        return BetterLokaClient.grindTimers().shulker();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (tab == Tab.SHULKER
                && click.x() >= boxX && click.x() < boxX + BOX_SIZE
                && click.y() >= boxY && click.y() < boxY + BOX_SIZE) {
            GrindTimer timer = shulker();
            if (timer.running()) {
                timer.stop();
            } else {
                timer.start();
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        if (tab == Tab.SHULKER) {
            renderShulker(context, left, VIEWPORT_TOP, width, mouseX, mouseY);
        } else {
            renderGlowstone(context, left, VIEWPORT_TOP, width);
        }
    }

    private void renderShulker(DrawContext context, int left, int y, int width, int mouseX, int mouseY) {
        int inner = width - CARD_PADDING * 2;
        GrindTimer timer = shulker();

        int height = CARD_PADDING * 2 + ROW_HEIGHT * 5;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.grind.shulker.title").copy().formatted(Formatting.BOLD),
                textX, textY, GuiTheme.ACCENT);

        // The square the player clicks to start it, in the corner they asked for.
        boxX = left + width - CARD_PADDING - BOX_SIZE;
        boxY = textY - 2;
        boolean hovered = mouseX >= boxX && mouseX < boxX + BOX_SIZE
                && mouseY >= boxY && mouseY < boxY + BOX_SIZE;
        int boxColor = timer.running() ? GuiTheme.GOOD : (hovered ? GuiTheme.ACCENT : GuiTheme.MUTED);
        GuiTheme.chip(context, boxX, boxY, BOX_SIZE, BOX_SIZE, boxColor);
        if (timer.running()) {
            // A filled square reads as "armed" at a glance, which is the whole job of the box.
            context.fill(boxX + 3, boxY + 3, boxX + BOX_SIZE - 3, boxY + BOX_SIZE - 3, GuiTheme.GOOD);
        }

        String remaining = timer.running() || timer.finished() ? timer.remainingText() : "17:00";
        int remainingColor = timer.finished() ? GuiTheme.GOOD
                : (timer.running() ? GuiTheme.TEXT : GuiTheme.MUTED);
        context.drawTextWithShadow(this.textRenderer, remaining, textX, textY + ROW_HEIGHT, remainingColor);

        Text status;
        if (timer.finished()) {
            status = Text.translatable("betterloka.grind.shulker.ready");
        } else if (timer.running()) {
            status = Text.translatable("betterloka.grind.shulker.counting");
        } else {
            status = Text.translatable("betterloka.grind.shulker.idle");
        }
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(status.getString(), inner - 40),
                textX + this.textRenderer.getWidth(remaining) + 8, textY + ROW_HEIGHT,
                timer.finished() ? GuiTheme.GOOD : GuiTheme.MUTED);

        GuiTheme.progressBar(context, textX, textY + ROW_HEIGHT * 2 + 2, inner, 5, timer.progress());

        // Two lines rather than one trimmed: the second half is the part worth knowing.
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.grind.shulker.hint"), textX, textY + ROW_HEIGHT * 3,
                GuiTheme.MUTED);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.grind.shulker.hint2"), textX, textY + ROW_HEIGHT * 4,
                GuiTheme.MUTED);
    }

    private void renderGlowstone(DrawContext context, int left, int y, int width) {
        int inner = width - CARD_PADDING * 2;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, height);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.grind.glowstone.title").copy().formatted(Formatting.BOLD),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.ACCENT);
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(
                        Text.translatable("betterloka.grind.glowstone.todo").getString(), inner),
                left + CARD_PADDING, y + CARD_PADDING + ROW_HEIGHT, GuiTheme.MUTED);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
