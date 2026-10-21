package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.grind.GrindTimer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Timers for the things worth grinding on Loka.
 *
 * <p>The timers live outside the screen, so shutting the GUI does not stop them — a twenty minute
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

    /** Minutes per press of - and +. Single minutes would be a long way from three hours. */
    private static final int GLOWSTONE_STEP = 5;

    private final Screen parent;
    private Tab tab = Tab.SHULKER;

    /** Where the start box was drawn this frame, so a click can find it. */
    private int boxX;
    private int boxY;

    public LokaGrinderScreen(Screen parent) {
        super(Component.translatable("betterloka.module.loka_grinder"));
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
            addRenderableWidget(Button.builder(tabLabel(value), button -> selectTab(target))
                    .bounds(x, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            x += half + 4;
        }

        int controlY = VIEWPORT_TOP + CARD_PADDING * 2 + ROW_HEIGHT * 6 + CARD_GAP;
        if (tab == Tab.SHULKER) {
            addRenderableWidget(Button.builder(autoStartLabel(), button -> {
                        BetterLokaClient.config().setShulkerAutoStart(
                                !BetterLokaClient.config().shulkerAutoStart());
                        button.setMessage(autoStartLabel());
                    })
                    .bounds(left, controlY, width, TAB_ROW_HEIGHT).build());
        } else {
            int third = (width - 8) / 3;
            addRenderableWidget(Button.builder(Component.literal("-" + GLOWSTONE_STEP + "m"),
                            button -> changeGlowstone(-GLOWSTONE_STEP))
                    .bounds(left, controlY, third, TAB_ROW_HEIGHT).build());
            addRenderableWidget(Button.builder(Component.literal("+" + GLOWSTONE_STEP + "m"),
                            button -> changeGlowstone(GLOWSTONE_STEP))
                    .bounds(left + third + 4, controlY, third, TAB_ROW_HEIGHT).build());
            addRenderableWidget(Button.builder(Component.translatable("betterloka.grind.reset"),
                            button -> glowstone().stop())
                    .bounds(left + (third + 4) * 2, controlY, width - (third + 4) * 2,
                            TAB_ROW_HEIGHT).build());
        }

        // The on-screen countdown is per-timer, so it is switched from the tab it belongs to.
        addRenderableWidget(Button.builder(hudLabel(), button -> {
                    BetterLokaConfig config = BetterLokaClient.config();
                    if (tab == Tab.SHULKER) {
                        config.setShulkerHudEnabled(!config.shulkerHudEnabled());
                    } else {
                        config.setGlowstoneHudEnabled(!config.glowstoneHudEnabled());
                    }
                    button.setMessage(hudLabel());
                })
                .bounds(left, controlY + TAB_ROW_HEIGHT + 4, width, TAB_ROW_HEIGHT).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    private static Component autoStartLabel() {
        boolean on = BetterLokaClient.config().shulkerAutoStart();
        return Component.translatable("betterloka.grind.shulker.auto")
                .append(Component.translatable(on ? "betterloka.on" : "betterloka.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private Component hudLabel() {
        boolean on = tab == Tab.SHULKER
                ? BetterLokaClient.config().shulkerHudEnabled()
                : BetterLokaClient.config().glowstoneHudEnabled();
        return Component.translatable("betterloka.grind.hud.toggle")
                .append(Component.translatable(on ? "betterloka.on" : "betterloka.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /** Changes the glowstone length and keeps the timer in step with it. */
    private void changeGlowstone(int delta) {
        BetterLokaConfig config = BetterLokaClient.config();
        config.setGlowstoneMinutes(config.glowstoneMinutes() + delta);
        glowstone().setDurationMillis(config.glowstoneMinutes() * 60_000L);
        rebuildWidgets();
    }

    private Component tabLabel(Tab value) {
        Component label = Component.translatable(value.key);
        return value == tab ? label.copy().withStyle(ChatFormatting.YELLOW) : label;
    }

    private void selectTab(Tab target) {
        if (tab == target) {
            return;
        }
        tab = target;
        rebuildWidgets();
    }

    private static GrindTimer shulker() {
        return BetterLokaClient.grindTimers().shulker();
    }

    private static GrindTimer glowstone() {
        return BetterLokaClient.grindTimers().glowstone();
    }

    private GrindTimer active() {
        return tab == Tab.SHULKER ? shulker() : glowstone();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.x() >= boxX && click.x() < boxX + BOX_SIZE
                && click.y() >= boxY && click.y() < boxY + BOX_SIZE) {
            GrindTimer timer = active();
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
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        renderTimer(context, left, VIEWPORT_TOP, width, mouseX, mouseY,
                tab == Tab.SHULKER ? shulker() : glowstone(),
                tab == Tab.SHULKER ? "betterloka.grind.shulker.title" : "betterloka.grind.glowstone.title",
                tab == Tab.SHULKER ? "betterloka.grind.shulker.hint" : "betterloka.grind.glowstone.hint");
    }

    /** One card, whichever timer the tab is showing — they differ only in their labels. */
    private void renderTimer(GuiGraphicsExtractor context, int left, int y, int width, int mouseX, int mouseY,
                             GrindTimer timer, String titleKey, String hintKey) {
        int inner = width - CARD_PADDING * 2;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 6;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;

        context.text(this.font,
                Component.translatable(titleKey).copy().withStyle(ChatFormatting.BOLD),
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

        String remaining = timer.running() || timer.finished()
                ? timer.remainingText()
                : timer.durationText();
        int remainingColor = timer.finished() ? GuiTheme.GOOD
                : (timer.running() ? GuiTheme.TEXT : GuiTheme.MUTED);
        context.text(this.font, remaining, textX, textY + ROW_HEIGHT, remainingColor);

        Component status;
        if (timer.finished()) {
            status = Component.translatable("betterloka.grind.ready");
        } else if (timer.running()) {
            status = Component.translatable("betterloka.grind.counting");
        } else {
            status = Component.translatable("betterloka.grind.idle");
        }
        context.text(this.font,
                this.font.plainSubstrByWidth(status.getString(), inner - 40),
                textX + this.font.width(remaining) + 8, textY + ROW_HEIGHT,
                timer.finished() ? GuiTheme.GOOD : GuiTheme.MUTED);

        GuiTheme.progressBar(context, textX, textY + ROW_HEIGHT * 2 + 2, inner, 5, timer.progress());

        context.text(this.font, Component.translatable(hintKey),
                textX, textY + ROW_HEIGHT * 3, GuiTheme.MUTED);
        context.text(this.font,
                Component.translatable("betterloka.grind.shulker.hint2"),
                textX, textY + ROW_HEIGHT * 4, GuiTheme.MUTED);
        context.text(this.font,
                Component.translatable("betterloka.grind.keybind.hint",
                        keyName(tab == Tab.SHULKER
                                ? BetterLokaClient.shulkerTimerKey()
                                : BetterLokaClient.glowstoneTimerKey())),
                textX, textY + ROW_HEIGHT * 5, GuiTheme.MUTED);
    }

    /** The key as it is actually bound, so rebinding it in Options is reflected here. */
    private static Component keyName(net.minecraft.client.KeyMapping key) {
        return key == null ? Component.translatable("key.keyboard.unknown") : key.getTranslatedKeyMessage();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
