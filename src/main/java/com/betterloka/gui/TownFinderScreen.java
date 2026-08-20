package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Looks a town up: who it is, where it is, and every territory it currently holds.
 *
 * <p>The territories come from the Town Logger's sweep rather than another set of requests, so this
 * costs one lookup and answers "what do they actually own" as a side effect of the watching that is
 * already happening.
 */
public class TownFinderScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 400;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    private static final int SEARCH_ROW_Y = 26;
    private static final int SEARCH_ROW_HEIGHT = 18;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int VIEWPORT_TOP = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 6;

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private TextFieldWidget queryField;
    private ButtonWidget searchButton;

    private LokaTown town;
    private List<Territory> held = List.of();
    private Text message;
    private boolean loading;
    private int generation;

    public TownFinderScreen(Screen parent) {
        super(Text.translatable("betterloka.module.town_finder"));
        this.parent = parent;
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

        String previous = queryField != null ? queryField.getText() : "";
        queryField = new TextFieldWidget(this.textRenderer, left, SEARCH_ROW_Y,
                width - SEARCH_BUTTON_WIDTH - 4, SEARCH_ROW_HEIGHT,
                Text.translatable("betterloka.town_finder.field"));
        queryField.setMaxLength(32);
        queryField.setPlaceholder(Text.translatable("betterloka.town_finder.placeholder")
                .formatted(Formatting.DARK_GRAY));
        queryField.setText(previous);
        addDrawableChild(queryField);

        searchButton = ButtonWidget.builder(Text.translatable("betterloka.finder.search"), button -> search())
                .dimensions(left + width - SEARCH_BUTTON_WIDTH, SEARCH_ROW_Y, SEARCH_BUTTON_WIDTH, SEARCH_ROW_HEIGHT)
                .build();
        addDrawableChild(searchButton);

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 26, 200, 20).build());

        setInitialFocus(queryField);
    }

    private void search() {
        String query = queryField.getText().trim();
        if (query.isEmpty() || loading) {
            return;
        }
        loading = true;
        message = null;
        town = null;
        held = List.of();
        scrollPanel.reset();
        int mine = ++generation;

        CompletableFuture
                .supplyAsync(() -> BetterLokaClient.towns().byName(query), BetterLokaClient.lokaExecutor())
                .whenComplete((found, error) -> onClientThread(mine, () -> {
                    loading = false;
                    if (error != null) {
                        message = Text.translatable("betterloka.town_finder.unreachable").formatted(Formatting.RED);
                        return;
                    }
                    if (found != null) {
                        town = found;
                        held = BetterLokaClient.townLogger().heldBy(found.id());
                        return;
                    }
                    // A name Loka has no living town for is usually a town that was deleted, which the
                    // logger already knows the names of — worth saying so rather than "no such town".
                    LokaTown dead = BetterLokaClient.townLogger().deletedByName(query);
                    if (dead != null) {
                        town = dead;
                        held = BetterLokaClient.townLogger().heldBy(dead.id());
                    } else {
                        message = Text.translatable("betterloka.town_finder.not_found", query)
                                .formatted(Formatting.RED);
                    }
                }));
    }

    private void onClientThread(int mine, Runnable action) {
        if (this.client == null) {
            return;
        }
        this.client.execute(() -> {
            if (mine == generation && this.client.currentScreen == this) {
                action.run();
            }
        });
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && queryField.isFocused()) {
            search();
            return true;
        }
        return super.keyPressed(input);
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
        searchButton.active = !loading && !queryField.getText().trim().isEmpty();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used;

        if (loading) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.searching"),
                    left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else if (message != null) {
            context.drawTextWithShadow(this.textRenderer, message, left, y + 4, GuiTheme.BAD);
            used = 20;
        } else if (town == null) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.town_finder.hint"),
                    left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else {
            used = renderTown(context, left, y, cardWidth) - y;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);
    }

    private int renderTown(DrawContext context, int left, int y, int width) {
        int inner = width - CARD_PADDING * 2;

        int headHeight = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, headHeight);
        Text name = Text.literal(town.name()).formatted(Formatting.BOLD);
        context.drawTextWithShadow(this.textRenderer, name, left + CARD_PADDING, y + CARD_PADDING,
                town.deleted() ? GuiTheme.BAD : GuiTheme.ACCENT);
        String continent = town.continentName() == null ? "?" : town.continentName();
        context.drawTextWithShadow(this.textRenderer, continent,
                left + width - CARD_PADDING - this.textRenderer.getWidth(continent), y + CARD_PADDING,
                GuiTheme.MUTED);
        String second = town.deleted()
                ? Text.translatable("betterloka.town_finder.deleted").getString()
                : town.slogan();
        if (second != null && !second.isBlank()) {
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(second, inner), left + CARD_PADDING,
                    y + CARD_PADDING + ROW_HEIGHT, town.deleted() ? GuiTheme.BAD : GuiTheme.MUTED);
        }
        y += headHeight + CARD_GAP;

        // Level, strength and roster do not survive deletion, so a dead town skips straight to the
        // territories it is still recorded against — which is exactly what makes it worth looking up.
        if (town.deleted()) {
            return renderTerritories(context, left, y, width, inner);
        }

        int statsHeight = CARD_PADDING * 2 + ROW_HEIGHT * 4;
        GuiTheme.panel(context, left, y, width, statsHeight);
        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                Text.translatable("betterloka.town_finder.level").getString(),
                String.format(Locale.ROOT, "%.0f", town.townLevel()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                Text.translatable("betterloka.town_finder.strength").getString(),
                String.format(Locale.ROOT, "%.0f", town.strength()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 2, inner,
                Text.translatable("betterloka.town_finder.members").getString(),
                String.valueOf(town.memberCount()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 3, inner,
                Text.translatable("betterloka.town_finder.recruiting").getString(),
                Text.translatable(town.recruiting() ? "betterloka.yes" : "betterloka.no").getString(),
                town.recruiting() ? GuiTheme.GOOD : GuiTheme.MUTED);
        y += statsHeight + CARD_GAP;
        return renderTerritories(context, left, y, width, inner);
    }

    /** Territories come from the logger's standing sweep, so this costs nothing extra. */
    private int renderTerritories(DrawContext context, int left, int y, int width, int inner) {
        int headerHeight = CARD_PADDING * 2 + ROW_HEIGHT;
        GuiTheme.panel(context, left, y, width, headerHeight);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.town_finder.territories", held.size()),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.TEXT);
        y += headerHeight + CARD_GAP;

        if (held.isEmpty()) {
            GuiTheme.panel(context, left, y, width, headerHeight);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.town_finder.no_territories"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            return y + headerHeight + CARD_GAP;
        }

        for (Territory territory : held) {
            GuiTheme.panel(context, left, y, width, headerHeight);
            String where = Text.translatable("betterloka.town_log.where", territory.continent(),
                    territory.num(), territory.coordinates()).getString();
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(where, inner - 70), left + CARD_PADDING, y + CARD_PADDING,
                    GuiTheme.TEXT);
            if (territory.areaName() != null) {
                context.drawTextWithShadow(this.textRenderer, territory.areaName(),
                        left + width - CARD_PADDING - this.textRenderer.getWidth(territory.areaName()),
                        y + CARD_PADDING, GuiTheme.MUTED);
            }
            y += headerHeight + CARD_GAP;
        }
        return y;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
