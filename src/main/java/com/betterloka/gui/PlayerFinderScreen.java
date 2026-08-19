package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.LokaTown;
import com.betterloka.stats.FightSummary;
import com.betterloka.stats.PlayerProfile;
import com.betterloka.stats.PlayerStatsService;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * Looks a Loka player up and shows their Conquest record.
 *
 * <p>Career totals come from EldritchBot in a single request, so the card fills in as fast as the
 * network allows; the per-fight breakdown at the bottom arrives a moment later.
 */
public class PlayerFinderScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int ROW_HEIGHT = 11;
    private static final int CARD_PADDING = 6;
    private static final int CARD_GAP = 6;

    private static final int SEARCH_ROW_Y = 32;
    private static final int SEARCH_ROW_HEIGHT = 20;
    private static final int TOGGLE_ROW_Y = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 4;
    private static final int TOGGLE_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = TOGGLE_ROW_Y + TOGGLE_ROW_HEIGHT + 6;

    private final Screen parent;

    private TextFieldWidget nameField;
    private ButtonWidget searchButton;

    private PlayerProfile profile;
    private Text error;
    private boolean searching;
    /** Guards against a stale request overwriting the results of a newer one. */
    private int searchGeneration;

    private int scroll;
    private int contentHeight;

    public PlayerFinderScreen(Screen parent) {
        super(Text.translatable("betterloka.finder.title"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int viewportBottom() {
        return this.height - 38;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int fieldWidth = width - SEARCH_BUTTON_WIDTH - 4;

        String previous = nameField != null ? nameField.getText() : "";
        nameField = new TextFieldWidget(this.textRenderer, left, SEARCH_ROW_Y, fieldWidth, SEARCH_ROW_HEIGHT,
                Text.translatable("betterloka.finder.field"));
        nameField.setMaxLength(16);
        nameField.setPlaceholder(Text.translatable("betterloka.finder.placeholder").formatted(Formatting.DARK_GRAY));
        nameField.setText(previous);
        addDrawableChild(nameField);

        searchButton = ButtonWidget.builder(Text.translatable("betterloka.finder.search"), button -> search())
                .dimensions(left + fieldWidth + 4, SEARCH_ROW_Y, SEARCH_BUTTON_WIDTH, SEARCH_ROW_HEIGHT)
                .build();
        addDrawableChild(searchButton);

        addDrawableChild(ButtonWidget.builder(kdToggleLabel(), button -> {
                    BetterLokaClient.config().setShowNameplateKd(!BetterLokaClient.config().showNameplateKd());
                    button.setMessage(kdToggleLabel());
                })
                .dimensions(left, TOGGLE_ROW_Y, width, TOGGLE_ROW_HEIGHT)
                .build());

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());

        setInitialFocus(nameField);
    }

    private Text kdToggleLabel() {
        boolean on = BetterLokaClient.config().showNameplateKd();
        return Text.translatable("betterloka.finder.kd_toggle",
                Text.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .formatted(on ? Formatting.GREEN : Formatting.GRAY));
    }

    private void search() {
        String name = nameField.getText().trim();
        if (name.isEmpty() || searching) {
            return;
        }
        searching = true;
        error = null;
        profile = null;
        scroll = 0;
        int generation = ++searchGeneration;

        PlayerStatsService stats = BetterLokaClient.stats();
        stats.lookup(name, headline -> applyOnClientThread(generation, () -> {
            // Career totals are in; the fight rows below fill in as their pages land.
            searching = false;
            error = null;
            profile = headline;
        })).whenComplete((result, throwable) -> applyOnClientThread(generation, () -> {
            searching = false;
            if (throwable != null) {
                profile = null;
                error = describe(name, throwable);
            } else {
                profile = result;
                error = null;
            }
        }));
    }

    /** Runs {@code action} on the render thread, unless the search has been superseded or the screen closed. */
    private void applyOnClientThread(int generation, Runnable action) {
        if (this.client == null) {
            return;
        }
        this.client.execute(() -> {
            if (generation == searchGeneration && this.client.currentScreen == this) {
                action.run();
            }
        });
    }

    private static Text describe(String name, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        if (cause instanceof ApiException apiException && apiException.notFound()) {
            return Text.translatable("betterloka.finder.not_found", name).formatted(Formatting.RED);
        }
        return Text.translatable("betterloka.finder.unreachable").formatted(Formatting.RED);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && nameField.isFocused()) {
            search();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseY >= VIEWPORT_TOP && mouseY <= viewportBottom()) {
            int max = Math.max(0, contentHeight - (viewportBottom() - VIEWPORT_TOP));
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * 12)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        searchButton.active = !searching && !nameField.getText().trim().isEmpty();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = VIEWPORT_TOP - scroll;
        if (searching) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.searching"),
                    left, y + 4, GuiTheme.MUTED);
            contentHeight = 20;
        } else if (error != null) {
            context.drawTextWithShadow(this.textRenderer, error, left, y + 4, GuiTheme.BAD);
            contentHeight = 20;
        } else if (profile != null) {
            contentHeight = renderProfile(context, left, y, width) - (VIEWPORT_TOP - scroll);
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.hint"),
                    left, y + 4, GuiTheme.MUTED);
            contentHeight = 20;
        }
        context.disableScissor();
    }

    /** @return the y coordinate just past the rendered content. */
    private int renderProfile(DrawContext context, int left, int top, int width) {
        int inner = width - CARD_PADDING * 2;
        int y = top;
        EldritchStats stats = profile.stats();

        y = renderIdentityCard(context, left, y, width, inner);

        y = card(context, left, y, width, 2, (x, rowY) -> {
            twoColumns(context, x, rowY, inner,
                    label("betterloka.finder.stat.kills"), String.valueOf(stats.kills()), GuiTheme.GOOD,
                    label("betterloka.finder.stat.deaths"), String.valueOf(stats.deaths()), GuiTheme.BAD);
            double ratio = stats.killDeathRatio();
            twoColumns(context, x, rowY + ROW_HEIGHT, inner,
                    label("betterloka.finder.stat.kd"), String.format(Locale.ROOT, "%.2f", ratio),
                    GuiTheme.ratioColor(ratio),
                    label("betterloka.finder.stat.assists"), String.valueOf(stats.assists()), GuiTheme.TEXT);
        });

        y = card(context, left, y, width, 3, (x, rowY) -> {
            twoColumns(context, x, rowY, inner,
                    label("betterloka.finder.stat.wins"), String.valueOf(stats.wins()), GuiTheme.GOOD,
                    label("betterloka.finder.stat.losses"), String.valueOf(stats.losses()), GuiTheme.BAD);
            twoColumns(context, x, rowY + ROW_HEIGHT, inner,
                    label("betterloka.finder.stat.fights"), String.valueOf(stats.totalFights()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.winrate"),
                    String.format(Locale.ROOT, "%.0f%%", stats.winRate() * 100), GuiTheme.TEXT);
            twoColumns(context, x, rowY + ROW_HEIGHT * 2, inner,
                    label("betterloka.finder.stat.golems"), String.valueOf(stats.golems()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.lamps"), String.valueOf(stats.lamps()), GuiTheme.TEXT);
        });

        y = card(context, left, y, width, 2, (x, rowY) -> {
            twoColumns(context, x, rowY, inner,
                    label("betterloka.finder.stat.potions"), String.valueOf(stats.potions()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.pearls"), String.valueOf(stats.pearls()), GuiTheme.TEXT);
            twoColumns(context, x, rowY + ROW_HEIGHT, inner,
                    label("betterloka.finder.stat.food"), String.valueOf(stats.food()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.ingots"), String.valueOf(stats.ancientIngots()), GuiTheme.TEXT);
        });

        y = renderTownCard(context, left, y, width, inner);

        if (stats.nemesisName() != null) {
            y = card(context, left, y, width, 1, (x, rowY) ->
                    GuiTheme.statRow(context, this.textRenderer, x, rowY, inner,
                            label("betterloka.finder.nemesis"),
                            stats.nemesisName() + " (" + stats.nemesisDeaths() + ")", GuiTheme.BAD));
        }

        return renderFights(context, left, y, width, inner);
    }

    private int renderIdentityCard(DrawContext context, int left, int y, int width, int inner) {
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(profile.name()).formatted(Formatting.BOLD), textX, textY, GuiTheme.ACCENT);

        String badge = profile.inFightNow()
                ? Text.translatable("betterloka.finder.live").getString()
                : (profile.rank() == null ? null : profile.rank().toUpperCase(Locale.ROOT));
        if (badge != null) {
            context.drawTextWithShadow(this.textRenderer, badge,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(badge), textY,
                    profile.inFightNow() ? GuiTheme.LIVE : GuiTheme.MUTED);
        }

        boolean haveFirstSeen = profile.firstSeen() != null;
        String value = haveFirstSeen
                ? TimeFormat.date(profile.firstSeen())
                : (profile.stats().lastFight() != null ? profile.stats().lastFight() : "—");
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                label(haveFirstSeen ? "betterloka.finder.first_seen" : "betterloka.finder.last_fight"),
                value, GuiTheme.TEXT);

        return y + height + CARD_GAP;
    }

    private int renderTownCard(DrawContext context, int left, int y, int width, int inner) {
        LokaTown town = profile.town();
        int rows = town == null ? 2 : 3;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        String townName = profile.displayTown();

        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner, label("betterloka.finder.town"),
                townName != null ? townName : label("betterloka.finder.townless"),
                townName != null ? GuiTheme.TEXT : GuiTheme.MUTED);

        if (town != null) {
            StringBuilder details = new StringBuilder("lvl ").append((int) town.townLevel());
            if (town.continentName() != null) {
                details.append(" · ").append(town.continentName());
            }
            details.append(" · ").append(Text.translatable("betterloka.finder.members", town.memberCount()).getString());
            if (town.recruiting()) {
                details.append(" · ").append(label("betterloka.finder.recruiting"));
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(details.toString(), inner), textX, textY + ROW_HEIGHT, GuiTheme.MUTED);
            textY += ROW_HEIGHT;
        }

        String fightingFor = profile.fightingFor();
        boolean known = fightingFor != null;
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                label("betterloka.finder.fighting_for"),
                known ? fightingFor : (profile.fightsState() == PlayerProfile.FightsState.LOADING ? "..." : "—"),
                known ? GuiTheme.TEXT : GuiTheme.MUTED);

        return y + height + CARD_GAP;
    }

    private int renderFights(DrawContext context, int left, int y, int width, int inner) {
        if (profile.recentFights().isEmpty()) {
            return y;
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.recent", profile.recentFights().size()),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        for (FightSummary fight : profile.recentFights()) {
            int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
            GuiTheme.panel(context, left, y, width, height);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            StringBuilder headline = new StringBuilder(fight.location() != null ? fight.location() : fight.date());
            if (fight.hasDetail()) {
                headline.append("  ·  ").append(fight.ownSideCount()).append(" v ").append(fight.enemySideCount());
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(headline.toString(), inner - 62), textX, textY, GuiTheme.TEXT);

            String score = fight.hasDetail()
                    ? fight.kills() + "K / " + fight.deaths() + "D / " + fight.assists() + "A"
                    : "...";
            context.drawTextWithShadow(this.textRenderer, score,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(score), textY,
                    fight.hasDetail() && fight.kills() >= fight.deaths() ? GuiTheme.GOOD : GuiTheme.MUTED);

            StringBuilder detail = new StringBuilder(
                    label(fight.victory() ? "betterloka.finder.victory" : "betterloka.finder.defeat"));
            if (fight.ownTown() != null) {
                detail.append(" · ").append(fight.ownTown());
            }
            if (fight.enemyTown() != null) {
                detail.append(" vs ").append(fight.enemyTown());
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(detail.toString(), inner - 40), textX, textY + ROW_HEIGHT,
                    fight.victory() ? GuiTheme.GOOD : GuiTheme.BAD);

            context.drawTextWithShadow(this.textRenderer, fight.date(),
                    left + width - CARD_PADDING - this.textRenderer.getWidth(fight.date()), textY + ROW_HEIGHT,
                    GuiTheme.MUTED);

            y += height + CARD_GAP;
        }
        return y;
    }

    /** Draws a card of {@code rows} rows and returns the y just past it. */
    private int card(DrawContext context, int left, int y, int width, int rows, CardBody body) {
        int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
        GuiTheme.panel(context, left, y, width, height);
        body.draw(left + CARD_PADDING, y + CARD_PADDING);
        return y + height + CARD_GAP;
    }

    @FunctionalInterface
    private interface CardBody {
        void draw(int x, int y);
    }

    /** Two label/value pairs side by side, each right-aligned in its half. */
    private void twoColumns(DrawContext context, int x, int y, int inner,
                            String leftLabel, String leftValue, int leftColor,
                            String rightLabel, String rightValue, int rightColor) {
        int columnWidth = (inner - 10) / 2;
        GuiTheme.statRow(context, this.textRenderer, x, y, columnWidth, leftLabel, leftValue, leftColor);
        GuiTheme.statRow(context, this.textRenderer, x + columnWidth + 10, y, columnWidth,
                rightLabel, rightValue, rightColor);
    }

    private static String label(String key) {
        return Text.translatable(key).getString();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
