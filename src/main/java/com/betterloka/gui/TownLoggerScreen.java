package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.Territory;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.towns.TownLogEvent;
import com.betterloka.towns.TownLogger;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The record of which towns have fallen.
 *
 * <p>The point of the screen is the first tab. A territory changing hands is a fight and says
 * nothing; a territory still recorded to a town Loka no longer has means that town is gone, and that
 * is what is listed, with its continent, territory number and beacon coordinates.
 */
public class TownLoggerScreen extends Screen implements LokaStyled {
    private enum Tab {
        FALLEN("betterloka.town_log.tab.fallen"),
        CHANGES("betterloka.town_log.tab.changes"),
        UNOWNED("betterloka.town_log.tab.unowned");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final int MAX_CONTENT_WIDTH = 420;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int CONTROL_ROW_HEIGHT = 16;

    private final Screen parent;
    private final LokaWindow window;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private Tab tab = Tab.FALLEN;

    public TownLoggerScreen(Screen parent) {
        super(Component.translatable("betterloka.module.town_logger"));
        this.parent = parent;
        this.window = new LokaWindow(this.title, Component.translatable("betterloka.module.town_logger.desc"),
                this::onClose).content(MAX_CONTENT_WIDTH, 420);
    }

    private static TownLogger logger() {
        return BetterLokaClient.townLogger();
    }

    private static BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    private int controlRowY() {
        return tabRowY() + TAB_ROW_HEIGHT + 4;
    }

    private int tabRowY() {
        return window.top();
    }

    private int viewportTop() {
        return controlRowY() + CONTROL_ROW_HEIGHT + 16;
    }

    private int contentWidth() {
        return window.innerWidth();
    }

    private int contentLeft() {
        return window.left();
    }

    private int viewportBottom() {
        return window.bottom();
    }

    @Override
    protected void init() {
        window.layout(this.width, this.height);
        int left = contentLeft();
        int width = contentWidth();

        scrollPanel.setViewport(left, viewportTop(), width, Math.max(20, viewportBottom() - viewportTop()));

        int tabWidth = (width - 8) / 3;
        int x = left;
        for (Tab value : Tab.values()) {
            Tab target = value;
            int thisWidth = value == Tab.UNOWNED ? left + width - x : tabWidth;
            addRenderableWidget(Button.builder(tabLabel(value), button -> selectTab(target))
                    .bounds(x, tabRowY(), thisWidth, TAB_ROW_HEIGHT).build());
            x += tabWidth + 4;
        }

        int third = (width - 8) / 3;
        addRenderableWidget(Button.builder(watchLabel(), button -> {
                    config().setTownLogEnabled(!config().townLogEnabled());
                    button.setMessage(watchLabel());
                    if (config().townLogEnabled()) {
                        logger().poll();
                    }
                })
                .bounds(left, controlRowY(), third, CONTROL_ROW_HEIGHT).build());

        addRenderableWidget(Button.builder(intervalLabel(), button -> {
                    config().setTownLogIntervalMinutes(nextInterval(config().townLogIntervalMinutes()));
                    button.setMessage(intervalLabel());
                })
                .bounds(left + third + 4, controlRowY(), third, CONTROL_ROW_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.translatable("betterloka.town_log.check_now"),
                        button -> logger().poll())
                .bounds(left + (third + 4) * 2, controlRowY(), width - (third + 4) * 2, CONTROL_ROW_HEIGHT)
                .build());
    }

    private Component tabLabel(Tab value) {
        Component label = Component.translatable(value.key);
        return value == tab ? label.copy().withStyle(ChatFormatting.YELLOW) : label;
    }

    private static Component watchLabel() {
        boolean on = config().townLogEnabled();
        return Component.translatable("betterloka.town_log.watching")
                .append(Component.translatable(on ? "betterloka.on" : "betterloka.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static Component intervalLabel() {
        return Component.translatable("betterloka.town_log.every", config().townLogIntervalMinutes());
    }

    private static int nextInterval(int current) {
        int[] options = BetterLokaConfig.TOWN_LOG_INTERVALS;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                return options[(i + 1) % options.length];
            }
        }
        return options[0];
    }

    private void selectTab(Tab target) {
        if (tab == target) {
            return;
        }
        tab = target;
        scrollPanel.reset();
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (window.mouseClicked(click.x(), click.y())) {
            return true;
        }
        return scrollPanel.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        window.draw(context, this.font, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int left = contentLeft();
        int width = contentWidth();
        renderStatus(context, left, width);

        context.enableScissor(left, viewportTop(), left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used = switch (tab) {
            case FALLEN -> renderEvents(context, left, y, cardWidth, TownLogger.newestFirst(logger().fallen()));
            case CHANGES -> renderEvents(context, left, y, cardWidth, TownLogger.newestFirst(logger().events()));
            case UNOWNED -> renderUnowned(context, left, y, cardWidth);
        } - y;
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /** One line saying whether the watch is running and when it last looked. */
    private void renderStatus(GuiGraphicsExtractor context, int left, int width) {
        TownLogger logger = logger();
        int y = viewportTop() - 12;

        Component status = switch (logger.state()) {
            case OFF -> Component.translatable("betterloka.town_log.state.off");
            case POLLING -> Component.translatable("betterloka.town_log.state.polling");
            case FAILED -> Component.translatable("betterloka.town_log.state.failed",
                    logger.lastError() == null ? "?" : logger.lastError());
            case IDLE -> logger.lastPollAt() == 0
                    ? Component.translatable("betterloka.town_log.state.waiting")
                    : Component.translatable("betterloka.town_log.state.checked", TimeFormat.ago(logger.lastPollAt()));
        };
        context.text(this.font, status, left, y,
                logger.state() == TownLogger.State.FAILED ? GuiTheme.BAD : GuiTheme.MUTED);

        String counts = Component.translatable("betterloka.town_log.counts",
                logger.territoryCount(), logger.deletedTownCount()).getString();
        context.text(this.font, counts,
                left + width - this.font.width(counts), y, GuiTheme.MUTED);
    }

    /** @return the y just past the last card. */
    private int renderEvents(GuiGraphicsExtractor context, int left, int y, int width, List<TownLogEvent> events) {
        if (events.isEmpty()) {
            return renderEmpty(context, left, y, width, tab == Tab.FALLEN
                    ? "betterloka.town_log.none_fallen" : "betterloka.town_log.none_yet");
        }

        int inner = width - CARD_PADDING * 2;
        for (TownLogEvent event : events) {
            boolean fell = event.kind().isTownGone();
            int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
            GuiTheme.panel(context, left, y, width, height);
            if (fell) {
                context.fill(left, y, left + 2, y + height, GuiTheme.BAD);
            }

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            Component headline = switch (event.kind()) {
                case TOWN_FELL -> Component.translatable("betterloka.town_log.fell", event.townName());
                case CAPTURED -> Component.translatable("betterloka.town_log.captured", event.townName(),
                        event.otherTown());
                case RELEASED -> Component.translatable("betterloka.town_log.released", event.townName());
                case CLAIMED -> Component.translatable("betterloka.town_log.claimed", event.townName());
            };
            context.text(this.font, headline, textX, textY,
                    fell ? GuiTheme.BAD : GuiTheme.TEXT);

            // A town found by the claims it left behind was already gone when the mod first looked,
            // so its date is when it was noticed rather than when it fell. Saying which is which
            // beats printing a date that reads like a fact and is not one.
            boolean dated = !fell || !event.hasTerritory();
            String when = dated ? TimeFormat.ago(event.at())
                    : Component.translatable("betterloka.town_log.already_gone").getString();
            context.text(this.font, when,
                    left + width - CARD_PADDING - this.font.width(when), textY,
                    dated ? GuiTheme.MUTED : GuiTheme.ACCENT);

            // The line the whole module exists for: where the territory was. A town seen falling
            // names no territory — the fall is the event, and its land is a separate question.
            String where = event.hasTerritory()
                    ? Component.translatable("betterloka.town_log.where", event.continent(),
                            event.num(), event.coordinates()).getString()
                    : Component.translatable("betterloka.town_log.town_gone",
                            event.continent() == null ? "?" : event.continent()).getString();
            context.text(this.font,
                    this.font.plainSubstrByWidth(where, inner - 60), textX, textY + ROW_HEIGHT,
                    fell ? GuiTheme.TEXT : GuiTheme.MUTED);

            if (event.areaName() != null) {
                context.text(this.font, event.areaName(),
                        left + width - CARD_PADDING - this.font.width(event.areaName()),
                        textY + ROW_HEIGHT, GuiTheme.MUTED);
            }

            y += height + CARD_GAP;
        }
        return y;
    }

    private int renderUnowned(GuiGraphicsExtractor context, int left, int y, int width) {
        List<Territory> free = logger().unowned();
        if (free.isEmpty()) {
            return renderEmpty(context, left, y, width, "betterloka.town_log.none_yet");
        }

        context.text(this.font,
                Component.translatable("betterloka.town_log.unowned_count", free.size()), left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        int height = CARD_PADDING * 2 + ROW_HEIGHT;
        for (Territory territory : free) {
            GuiTheme.panel(context, left, y, width, height);
            String where = Component.translatable("betterloka.town_log.where", territory.continent(),
                    territory.num(), territory.coordinates()).getString();
            context.text(this.font, where, left + CARD_PADDING, y + CARD_PADDING,
                    GuiTheme.TEXT);
            if (territory.areaName() != null) {
                context.text(this.font, territory.areaName(),
                        left + width - CARD_PADDING - this.font.width(territory.areaName()),
                        y + CARD_PADDING, GuiTheme.MUTED);
            }
            y += height + CARD_GAP;
        }
        return y;
    }

    private int renderEmpty(GuiGraphicsExtractor context, int left, int y, int width, String key) {
        GuiTheme.panel(context, left, y, width, CARD_PADDING * 2 + ROW_HEIGHT);
        context.text(this.font,
                this.font.plainSubstrByWidth(Component.translatable(key).getString(), width - CARD_PADDING * 2),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
        return y + CARD_PADDING * 2 + ROW_HEIGHT + CARD_GAP;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
