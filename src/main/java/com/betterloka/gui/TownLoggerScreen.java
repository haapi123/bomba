package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.Territory;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.towns.TownLogEvent;
import com.betterloka.towns.TownLogger;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * The record of which towns have fallen.
 *
 * <p>The point of the screen is the first tab. A territory changing hands is a fight and says
 * nothing; a territory still recorded to a town Loka no longer has means that town is gone, and that
 * is what is listed, with its continent, territory number and beacon coordinates.
 */
public class TownLoggerScreen extends Screen {
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

    private static final int TAB_ROW_Y = 26;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int CONTROL_ROW_Y = TAB_ROW_Y + TAB_ROW_HEIGHT + 4;
    private static final int CONTROL_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = CONTROL_ROW_Y + CONTROL_ROW_HEIGHT + 16;

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private Tab tab = Tab.FALLEN;

    public TownLoggerScreen(Screen parent) {
        super(Text.translatable("betterloka.module.town_logger"));
        this.parent = parent;
    }

    private static TownLogger logger() {
        return BetterLokaClient.townLogger();
    }

    private static BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int viewportBottom() {
        return this.height - 34;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();

        scrollPanel.setViewport(left, VIEWPORT_TOP, width, Math.max(20, viewportBottom() - VIEWPORT_TOP));

        int tabWidth = (width - 8) / 3;
        int x = left;
        for (Tab value : Tab.values()) {
            Tab target = value;
            int thisWidth = value == Tab.UNOWNED ? left + width - x : tabWidth;
            addDrawableChild(ButtonWidget.builder(tabLabel(value), button -> selectTab(target))
                    .dimensions(x, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            x += tabWidth + 4;
        }

        int third = (width - 8) / 3;
        addDrawableChild(ButtonWidget.builder(watchLabel(), button -> {
                    config().setTownLogEnabled(!config().townLogEnabled());
                    button.setMessage(watchLabel());
                    if (config().townLogEnabled()) {
                        logger().poll();
                    }
                })
                .dimensions(left, CONTROL_ROW_Y, third, CONTROL_ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(intervalLabel(), button -> {
                    config().setTownLogIntervalMinutes(nextInterval(config().townLogIntervalMinutes()));
                    button.setMessage(intervalLabel());
                })
                .dimensions(left + third + 4, CONTROL_ROW_Y, third, CONTROL_ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.town_log.check_now"),
                        button -> logger().poll())
                .dimensions(left + (third + 4) * 2, CONTROL_ROW_Y, width - (third + 4) * 2, CONTROL_ROW_HEIGHT)
                .build());

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 26, 200, 20).build());
    }

    private Text tabLabel(Tab value) {
        Text label = Text.translatable(value.key);
        return value == tab ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    private static Text watchLabel() {
        boolean on = config().townLogEnabled();
        return Text.translatable("betterloka.town_log.watching")
                .append(Text.translatable(on ? "betterloka.on" : "betterloka.off")
                        .formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    private static Text intervalLabel() {
        return Text.translatable("betterloka.town_log.every", config().townLogIntervalMinutes());
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
        clearAndInit();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        return scrollPanel.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();
        renderStatus(context, left, width);

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
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
    private void renderStatus(DrawContext context, int left, int width) {
        TownLogger logger = logger();
        int y = VIEWPORT_TOP - 12;

        Text status = switch (logger.state()) {
            case OFF -> Text.translatable("betterloka.town_log.state.off");
            case POLLING -> Text.translatable("betterloka.town_log.state.polling");
            case FAILED -> Text.translatable("betterloka.town_log.state.failed",
                    logger.lastError() == null ? "?" : logger.lastError());
            case IDLE -> logger.lastPollAt() == 0
                    ? Text.translatable("betterloka.town_log.state.waiting")
                    : Text.translatable("betterloka.town_log.state.checked", TimeFormat.ago(logger.lastPollAt()));
        };
        context.drawTextWithShadow(this.textRenderer, status, left, y,
                logger.state() == TownLogger.State.FAILED ? GuiTheme.BAD : GuiTheme.MUTED);

        String counts = Text.translatable("betterloka.town_log.counts",
                logger.territoryCount(), logger.deletedTownCount()).getString();
        context.drawTextWithShadow(this.textRenderer, counts,
                left + width - this.textRenderer.getWidth(counts), y, GuiTheme.MUTED);
    }

    /** @return the y just past the last card. */
    private int renderEvents(DrawContext context, int left, int y, int width, List<TownLogEvent> events) {
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

            Text headline = switch (event.kind()) {
                case TOWN_FELL -> Text.translatable("betterloka.town_log.fell", event.townName());
                case CAPTURED -> Text.translatable("betterloka.town_log.captured", event.townName(),
                        event.otherTown());
                case RELEASED -> Text.translatable("betterloka.town_log.released", event.townName());
                case CLAIMED -> Text.translatable("betterloka.town_log.claimed", event.townName());
            };
            context.drawTextWithShadow(this.textRenderer, headline, textX, textY,
                    fell ? GuiTheme.BAD : GuiTheme.TEXT);

            // A town found by the claims it left behind was already gone when the mod first looked,
            // so its date is when it was noticed rather than when it fell. Saying which is which
            // beats printing a date that reads like a fact and is not one.
            boolean dated = !fell || !event.hasTerritory();
            String when = dated ? TimeFormat.ago(event.at())
                    : Text.translatable("betterloka.town_log.already_gone").getString();
            context.drawTextWithShadow(this.textRenderer, when,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(when), textY,
                    dated ? GuiTheme.MUTED : GuiTheme.ACCENT);

            // The line the whole module exists for: where the territory was. A town seen falling
            // names no territory — the fall is the event, and its land is a separate question.
            String where = event.hasTerritory()
                    ? Text.translatable("betterloka.town_log.where", event.continent(),
                            event.num(), event.coordinates()).getString()
                    : Text.translatable("betterloka.town_log.town_gone",
                            event.continent() == null ? "?" : event.continent()).getString();
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(where, inner - 60), textX, textY + ROW_HEIGHT,
                    fell ? GuiTheme.TEXT : GuiTheme.MUTED);

            if (event.areaName() != null) {
                context.drawTextWithShadow(this.textRenderer, event.areaName(),
                        left + width - CARD_PADDING - this.textRenderer.getWidth(event.areaName()),
                        textY + ROW_HEIGHT, GuiTheme.MUTED);
            }

            y += height + CARD_GAP;
        }
        return y;
    }

    private int renderUnowned(DrawContext context, int left, int y, int width) {
        List<Territory> free = logger().unowned();
        if (free.isEmpty()) {
            return renderEmpty(context, left, y, width, "betterloka.town_log.none_yet");
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.town_log.unowned_count", free.size()), left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        int height = CARD_PADDING * 2 + ROW_HEIGHT;
        for (Territory territory : free) {
            GuiTheme.panel(context, left, y, width, height);
            String where = Text.translatable("betterloka.town_log.where", territory.continent(),
                    territory.num(), territory.coordinates()).getString();
            context.drawTextWithShadow(this.textRenderer, where, left + CARD_PADDING, y + CARD_PADDING,
                    GuiTheme.TEXT);
            if (territory.areaName() != null) {
                context.drawTextWithShadow(this.textRenderer, territory.areaName(),
                        left + width - CARD_PADDING - this.textRenderer.getWidth(territory.areaName()),
                        y + CARD_PADDING, GuiTheme.MUTED);
            }
            y += height + CARD_GAP;
        }
        return y;
    }

    private int renderEmpty(DrawContext context, int left, int y, int width, String key) {
        GuiTheme.panel(context, left, y, width, CARD_PADDING * 2 + ROW_HEIGHT);
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(Text.translatable(key).getString(), width - CARD_PADDING * 2),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
        return y + CARD_PADDING * 2 + ROW_HEIGHT + CARD_GAP;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
