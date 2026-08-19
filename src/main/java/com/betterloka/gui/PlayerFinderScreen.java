package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.LokaApiException;
import com.betterloka.api.model.LokaTown;
import com.betterloka.data.BattleSyncService;
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
 * <p>Everything on this screen comes from the public Loka API: identity and town from the
 * {@code /players} and {@code /towns} endpoints, and all combat numbers aggregated from the battle
 * history the mod keeps in sync locally.
 */
public class PlayerFinderScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int ROW_HEIGHT = 11;
    private static final int CARD_PADDING = 6;
    private static final int CARD_GAP = 6;

    private static final int SEARCH_ROW_Y = 40;
    private static final int SEARCH_ROW_HEIGHT = 20;
    private static final int SEARCH_ROW_BOTTOM = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT;
    /** Vertical space the sync banner claims below the search row while a sync runs. */
    private static final int SYNC_STATUS_HEIGHT = 16;

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

    private boolean syncStatusVisible() {
        return BetterLokaClient.sync().progress().busy();
    }

    private int viewportTop() {
        // The sync banner only claims space while a sync is actually running, so an idle screen
        // gets the full height for results.
        return SEARCH_ROW_BOTTOM + 6 + (syncStatusVisible() ? SYNC_STATUS_HEIGHT : 0);
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

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());

        setInitialFocus(nameField);

        // Warm the battle history up as soon as the screen opens, so the first search does not sit
        // waiting for a sync it could have started a few seconds earlier.
        BetterLokaClient.sync().ensureSynced().exceptionally(throwable -> null);
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
        stats.lookup(name, partial -> applyOnClientThread(generation, () -> {
            // Identity and town are in; the combat card stays in its loading state until the
            // battle history sync finishes.
            searching = false;
            error = null;
            profile = partial;
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
        if (cause instanceof LokaApiException apiException && apiException.notFound()) {
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
        if (mouseY >= viewportTop() && mouseY <= viewportBottom()) {
            int max = Math.max(0, contentHeight - (viewportBottom() - viewportTop()));
            scroll = Math.max(0, Math.min(max, scroll - (int) (verticalAmount * 12)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        searchButton.active = !searching && !nameField.getText().trim().isEmpty();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        if (syncStatusVisible()) {
            renderSyncStatus(context, left, width, BetterLokaClient.sync().progress());
        }

        context.enableScissor(left, viewportTop(), left + width, viewportBottom());
        int y = viewportTop() - scroll;
        if (searching) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.searching"),
                    left, y + 4, GuiTheme.MUTED);
            contentHeight = 20;
        } else if (error != null) {
            context.drawTextWithShadow(this.textRenderer, error, left, y + 4, GuiTheme.BAD);
            contentHeight = 20;
        } else if (profile != null) {
            contentHeight = renderProfile(context, left, y, width) - (viewportTop() - scroll);
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.hint"),
                    left, y + 4, GuiTheme.MUTED);
            contentHeight = 20;
        }
        context.disableScissor();
    }

    private void renderSyncStatus(DrawContext context, int left, int width, BattleSyncService.Progress progress) {
        int y = SEARCH_ROW_BOTTOM + 4;
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(progress.message(), width), left, y, GuiTheme.MUTED);
        GuiTheme.progressBar(context, left, y + ROW_HEIGHT, width, 3, progress.fraction());
    }

    /** @return the y coordinate just past the rendered content. */
    private int renderProfile(DrawContext context, int left, int top, int width) {
        int y = top;
        int inner = width - CARD_PADDING * 2;

        y = renderIdentityCard(context, left, y, width, inner);
        y = renderCombatCard(context, left, y, width, inner);
        y = renderTownCard(context, left, y, width, inner);
        y = renderFights(context, left, y, width, inner);
        return y;
    }

    private int renderIdentityCard(DrawContext context, int left, int y, int width, int inner) {
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(profile.player().name()).formatted(Formatting.BOLD), textX, textY, GuiTheme.ACCENT);

        String rank = profile.player().rank();
        if (rank != null && !rank.isEmpty()) {
            String label = rank.toUpperCase(Locale.ROOT);
            context.drawTextWithShadow(this.textRenderer, label,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(label), textY, GuiTheme.MUTED);
        }

        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                Text.translatable("betterloka.finder.first_seen").getString(),
                TimeFormat.date(profile.firstSeen()), GuiTheme.TEXT);

        return y + height + CARD_GAP;
    }

    private int renderCombatCard(DrawContext context, int left, int y, int width, int inner) {
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, height);

        if (!profile.statsReady()) {
            boolean pending = profile.statsState() == PlayerProfile.StatsState.PENDING;
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(pending ? "betterloka.finder.stats_loading" : "betterloka.finder.stats_unavailable"),
                    left + CARD_PADDING, y + CARD_PADDING, pending ? GuiTheme.MUTED : GuiTheme.BAD);
            if (pending) {
                GuiTheme.progressBar(context, left + CARD_PADDING, y + CARD_PADDING + ROW_HEIGHT + 2, inner, 4,
                        BetterLokaClient.sync().progress().fraction());
            }
            return y + height + CARD_GAP;
        }

        int columnWidth = (inner - 10) / 2;
        int leftColumn = left + CARD_PADDING;
        int rightColumn = leftColumn + columnWidth + 10;
        int textY = y + CARD_PADDING;

        GuiTheme.statRow(context, this.textRenderer, leftColumn, textY, columnWidth,
                Text.translatable("betterloka.finder.stat.kills").getString(),
                String.valueOf(profile.kills()), GuiTheme.GOOD);
        GuiTheme.statRow(context, this.textRenderer, rightColumn, textY, columnWidth,
                Text.translatable("betterloka.finder.stat.deaths").getString(),
                String.valueOf(profile.deaths()), GuiTheme.BAD);

        double ratio = profile.killDeathRatio();
        GuiTheme.statRow(context, this.textRenderer, leftColumn, textY + ROW_HEIGHT, columnWidth,
                Text.translatable("betterloka.finder.stat.kd").getString(),
                String.format(Locale.ROOT, "%.2f", ratio), GuiTheme.ratioColor(ratio));
        GuiTheme.statRow(context, this.textRenderer, rightColumn, textY + ROW_HEIGHT, columnWidth,
                Text.translatable("betterloka.finder.stat.fights").getString(),
                String.valueOf(profile.battlesFought()), GuiTheme.TEXT);

        return y + height + CARD_GAP;
    }

    private int renderTownCard(DrawContext context, int left, int y, int width, int inner) {
        LokaTown town = profile.town();
        int rows = town == null ? 2 : 3;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;

        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                Text.translatable("betterloka.finder.town").getString(),
                town != null ? town.name() : Text.translatable("betterloka.finder.townless").getString(),
                town != null ? GuiTheme.TEXT : GuiTheme.MUTED);

        if (town != null) {
            StringBuilder details = new StringBuilder();
            details.append("lvl ").append((int) town.townLevel());
            if (town.continentName() != null) {
                details.append(" · ").append(town.continentName());
            }
            details.append(" · ").append(Text.translatable("betterloka.finder.members", town.memberCount()).getString());
            if (town.recruiting()) {
                details.append(" · ").append(Text.translatable("betterloka.finder.recruiting").getString());
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(details.toString(), inner), textX, textY + ROW_HEIGHT, GuiTheme.MUTED);
            textY += ROW_HEIGHT;
        }

        String fightingFor = profile.fightingFor();
        boolean known = fightingFor != null;
        String shown = known
                ? fightingFor
                : (profile.statsState() == PlayerProfile.StatsState.PENDING ? "..." : "—");
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                Text.translatable("betterloka.finder.fighting_for").getString(),
                shown, known ? GuiTheme.TEXT : GuiTheme.MUTED);

        return y + height + CARD_GAP;
    }

    private int renderFights(DrawContext context, int left, int y, int width, int inner) {
        if (!profile.statsReady()) {
            return y;
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.recent", PlayerStatsService.RECENT_FIGHT_COUNT),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        if (profile.recentFights().isEmpty()) {
            int height = CARD_PADDING * 2 + ROW_HEIGHT;
            GuiTheme.panel(context, left, y, width, height);
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.no_fights"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            return y + height;
        }

        for (FightSummary fight : profile.recentFights()) {
            int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
            GuiTheme.panel(context, left, y, width, height);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            String headline = (fight.territory() != null ? fight.territory() : "?")
                    + "  ·  " + fight.ownSideCount() + " v " + fight.enemySideCount();
            context.drawTextWithShadow(this.textRenderer, headline, textX, textY, GuiTheme.TEXT);

            String score = fight.kills() + "K / " + fight.deaths() + "D";
            context.drawTextWithShadow(this.textRenderer, score,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(score), textY,
                    fight.kills() >= fight.deaths() ? GuiTheme.GOOD : GuiTheme.BAD);

            StringBuilder detail = new StringBuilder();
            detail.append(fight.playerAttacked() ? "ATK" : "DEF");
            if (fight.foughtForTown() != null) {
                detail.append(" for ").append(fight.foughtForTown());
            }
            if (fight.enemyTown() != null) {
                detail.append(" vs ").append(fight.enemyTown());
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(detail.toString(), inner - 44), textX, textY + ROW_HEIGHT, GuiTheme.MUTED);

            String when = fight.live() ? Text.translatable("betterloka.finder.live").getString() : TimeFormat.ago(fight.timeEnded());
            context.drawTextWithShadow(this.textRenderer, when,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(when), textY + ROW_HEIGHT,
                    fight.live() ? GuiTheme.LIVE : GuiTheme.MUTED);

            y += height + CARD_GAP;
        }
        return y;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
