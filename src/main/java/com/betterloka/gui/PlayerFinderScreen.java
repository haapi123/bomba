package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.ArenaApi;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.LokaTown;
import com.betterloka.stats.ArenaService;
import com.betterloka.stats.BattleIndex;
import com.betterloka.stats.NemesisIndex;
import com.betterloka.stats.FightBreakdown;
import com.betterloka.stats.FightSummary;
import com.betterloka.stats.PlayerProfile;
import com.betterloka.stats.PlayerStatsService;
import com.betterloka.stats.PlayerTrait;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * Looks a Loka player up and shows their Conquest record.
 *
 * <p>Career totals come from EldritchBot in a single request, so the card fills in as fast as the
 * network allows; the per-fight breakdown at the bottom arrives a moment later.
 */
public class PlayerFinderScreen extends Screen {
    /** Which view of the same loaded profile is showing. */
    private enum Tab {
        PROFILE("betterloka.finder.tab.profile"),
        MONTH("betterloka.finder.tab.month");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int ROW_HEIGHT = 11;
    private static final int CARD_PADDING = 6;
    private static final int CARD_GAP = 6;

    private static final int CHIP_HEIGHT = 14;
    private static final int CHIP_PADDING = 5;
    private static final int CHIP_GAP = 4;

    /** Beyond a handful, old names stop being a summary. */
    private static final int MAX_PREVIOUS_NAMES = 8;

    /** How wide a chip's explanation is allowed to get before it wraps. */
    private static final int TOOLTIP_WIDTH = 190;

    private static final int SEARCH_ROW_Y = 32;
    private static final int SEARCH_ROW_HEIGHT = 20;
    private static final int TOGGLE_ROW_Y = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 4;
    private static final int TOGGLE_ROW_HEIGHT = 16;
    private static final int TAB_ROW_Y = TOGGLE_ROW_Y + TOGGLE_ROW_HEIGHT + 4;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = TAB_ROW_Y + TAB_ROW_HEIGHT + 6;

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private TextFieldWidget nameField;
    private ButtonWidget searchButton;

    private PlayerProfile profile;
    private Tab tab = Tab.PROFILE;
    private Text error;
    private boolean searching;

    /** Ranked 1v1: this season's standings, and the best rank of any season. */
    private List<ArenaService.Standing> arenaCurrent = List.of();
    private List<ArenaService.Standing> arenaBest = List.of();
    private boolean arenaLoading;
    private boolean arenaHistoryLoading;

    /** Who has killed whom this conquest month, for the Nemesis tab. */
    private NemesisIndex.Result nemesis = NemesisIndex.Result.EMPTY;
    private boolean nemesisLoading;

    /** Names this player has gone by, newest first. */
    private List<com.betterloka.stats.NameHistoryService.FormerName> previousNames = List.of();
    private boolean identityLoading;

    /** The chip under the pointer this frame, and where the pointer is; both reset every frame. */
    private PlayerTrait hoveredTrait;
    private int lastMouseX;
    private int lastMouseY;
    /** Guards against a stale request overwriting the results of a newer one. */
    private int searchGeneration;

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

        scrollPanel.setViewport(left, VIEWPORT_TOP, width, Math.max(20, viewportBottom() - VIEWPORT_TOP));

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

        Tab[] tabs = Tab.values();
        int share = (width - 4 * (tabs.length - 1)) / tabs.length;
        int tabX = left;
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            // The last one takes whatever the division left over, so the row ends flush.
            int thisWidth = i == tabs.length - 1 ? left + width - tabX : share;
            addDrawableChild(ButtonWidget.builder(tabLabel(target), button -> selectTab(target))
                    .dimensions(tabX, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            tabX += share + 4;
        }

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());

        setInitialFocus(nameField);
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
        scrollPanel.reset();
        clearAndInit();
    }

    /**
     * Reads the kill tables for this player's fights in the current conquest month.
     *
     * <p>Off the profile's critical path: it is a fight page each, and the headline should not wait
     * on them. Whatever has been read before is already counted, so a second look at the same
     * player is instant and a look at somebody else deepens the same month.
     */
    private void loadNemesis(PlayerProfile loaded, int generation) {
        NemesisIndex index = BetterLokaClient.nemesisIndex();
        String name = loaded.name();
        List<FightSummary> fights = loaded.recentFights();
        // What is already known, drawn at once; the fetch fills in behind it.
        nemesis = index.of(name, NemesisIndex.monthKey(LocalDate.now()));
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> index.lookup(name, fights, LocalDate.now()),
                        BetterLokaClient.lokaBulkExecutor())
                .whenComplete((result, throwable) -> applyOnClientThread(generation, () -> {
                    nemesisLoading = false;
                    if (throwable == null && result != null) {
                        nemesis = result;
                    }
                }));
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
        arenaCurrent = List.of();
        arenaBest = List.of();
        arenaLoading = true;
        arenaHistoryLoading = true;
        previousNames = List.of();
        identityLoading = true;
        nemesis = NemesisIndex.Result.EMPTY;
        nemesisLoading = true;
        scrollPanel.reset();
        int generation = ++searchGeneration;

        // The ladders are a separate service from the career pages, so they are asked in parallel
        // rather than lengthening the profile's critical path.
        ArenaService arena = BetterLokaClient.arenaStats();
        arena.current(name, null).whenComplete((standings, throwable) -> applyOnClientThread(generation, () -> {
            arenaLoading = false;
            arenaCurrent = throwable != null ? List.of() : standings;
        }));

        // The battle archive is what the format split and the month are taken from, and it is built
        // on demand: a search is the first sign anybody wants it.
        BetterLokaClient.battleIndex().startIndexing(BetterLokaClient.lokaBulkExecutor());

        PlayerStatsService stats = BetterLokaClient.stats();
        stats.lookup(name, headline -> applyOnClientThread(generation, () -> {
            // Career totals are in; the fight rows below fill in as their pages land.
            searching = false;
            error = null;
            profile = headline;
            // Names change between seasons, so the historical index is searched by UUID once Loka's
            // record of the account has supplied one.
            // The career name is a former name whenever Loka reports a newer one, and it is the only
            // source for a player who has never duelled — the ladders index nobody else.
            // Laby.net rather than the ranked ladders: those only knew a name if the player had
            // been on a ladder under it, which for a first test account meant no history at all
            // while the game's own /find listed two older names.
            BetterLokaClient.nameHistory().previousNames(headline.name(), headline.uuid())
                    .whenComplete((names, throwable) -> applyOnClientThread(generation, () -> {
                        identityLoading = false;
                        if (throwable == null && names != null) {
                            previousNames = merge(headline.formerName(), names);
                        }
                    }));
            arena.best(headline.name(), headline.uuid())
                    .whenComplete((standings, throwable) -> applyOnClientThread(generation, () -> {
                        arenaHistoryLoading = false;
                        if (throwable == null) {
                            arenaBest = standings;
                        }
                    }));
        })).whenComplete((result, throwable) -> applyOnClientThread(generation, () -> {
            searching = false;
            if (throwable != null) {
                profile = null;
                error = describe(name, throwable);
            } else {
                profile = result;
                error = null;
                // The fight rows are in by now, and their ids are what the kill tables are read
                // from — one fight page names the killer of every death in it.
                loadNemesis(result, generation);
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
        searchButton.active = !searching && !nameField.getText().trim().isEmpty();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        hoveredTrait = null;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used;
        if (searching) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.searching"),
                    left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else if (error != null) {
            context.drawTextWithShadow(this.textRenderer, error, left, y + 4, GuiTheme.BAD);
            used = 20;
        } else if (profile != null) {
            used = (switch (tab) {
                case MONTH -> renderMonth(context, left, y, cardWidth);
                case PROFILE -> renderProfile(context, left, y, cardWidth);
            }) - y;
        } else {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.finder.hint"),
                    left, y + 4, GuiTheme.MUTED);
            used = 20;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);

        // Drawn last and outside the scissor, or the panel the chip sits in would clip its own tooltip.
        if (hoveredTrait != null) {
            // Wrapped by hand: Minecraft draws a tooltip line as given, so a whole sentence would run
            // off the side of the screen.
            List<OrderedText> lines = new ArrayList<>();
            lines.add(chipLabel(hoveredTrait).copy()
                    .withColor(GuiTheme.traitColor(hoveredTrait.level()) & 0xFFFFFF).asOrderedText());
            lines.addAll(this.textRenderer.wrapLines(
                    Text.translatable(hoveredTrait.kind().reasonKey(hoveredTrait.level()), hoveredTrait.detail())
                            .formatted(Formatting.GRAY),
                    TOOLTIP_WIDTH));
            context.drawOrderedTooltip(this.textRenderer, lines, mouseX, mouseY);
        }
    }

    /** @return the y coordinate just past the rendered content. */
    private int renderProfile(DrawContext context, int left, int top, int width) {
        int inner = width - CARD_PADDING * 2;
        int y = top;
        EldritchStats stats = profile.stats();

        y = renderIdentityCard(context, left, y, width, inner);
        y = renderTraits(context, left, y, width);

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
        y = renderFormatSplit(context, left, y, width, inner);
        y = renderRankedCards(context, left, y, width, inner);

        if (stats.nemesisName() != null) {
            y = card(context, left, y, width, 1, (x, rowY) ->
                    GuiTheme.statRow(context, this.textRenderer, x, rowY, inner,
                            label("betterloka.finder.nemesis"),
                            stats.nemesisName() + " (" + stats.nemesisDeaths() + ")", GuiTheme.BAD));
        }

        y = renderFights(context, left, y, width, inner);
        y = renderNemesis(context, left, y, width, inner);
        return renderIdentity(context, left, y, width, inner);
    }

    /**
     * Older names, at the bottom of the profile.
     *
     * <p>Names come from Laby.net, which kept crawling after Mojang withdrew its own history in
     * 2022 and has more of them than the game's {@code /find} does.
     */
    private int renderIdentity(DrawContext context, int left, int y, int width, int inner) {
        // Drawn empty or not: most players have no rename on record, and a section that vanishes in
        // that case looks exactly like a section that is broken.
        context.drawTextWithShadow(this.textRenderer,
                previousNames.isEmpty()
                        ? Text.translatable("betterloka.finder.previous_names")
                        : Text.translatable("betterloka.finder.previous_count", previousNames.size()),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        if (previousNames.isEmpty()) {
            return card(context, left, y, width, 1, (x, rowY) ->
                    context.drawTextWithShadow(this.textRenderer,
                            label(identityLoading ? "betterloka.finder.loading"
                                    : "betterloka.finder.previous_none"), x, rowY, GuiTheme.MUTED));
        }

        int rows = Math.min(previousNames.size(), MAX_PREVIOUS_NAMES);
        int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
        GuiTheme.panel(context, left, y, width, height);
        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        for (var entry : previousNames.subList(0, rows)) {
            // The name, and when it was dropped. A tilde marks a date Laby estimated rather than
            // one Mojang published, which is everything since they closed the history in 2022.
            String when = entry.changedAt() == null ? ""
                    : (entry.accurate() ? "" : "~") + TimeFormat.monthAndYear(entry.changedAt());
            GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                    this.textRenderer.trimToWidth(entry.name(), inner - 60), when, GuiTheme.MUTED);
            textY += ROW_HEIGHT;
        }
        return y + height + CARD_GAP;
    }

    /**
     * Puts the name EldritchBot last saw at the head of the list, if it is not there already.
     *
     * <p>Its record is the name at the player's last fight, which can be newer than anything Laby
     * has crawled — so it is worth keeping even though it carries no date.
     */
    private static List<com.betterloka.stats.NameHistoryService.FormerName> merge(
            String first, List<com.betterloka.stats.NameHistoryService.FormerName> rest) {
        if (first == null) {
            return rest;
        }
        for (var entry : rest) {
            if (entry.name().equalsIgnoreCase(first)) {
                return rest;
            }
        }
        List<com.betterloka.stats.NameHistoryService.FormerName> names = new java.util.ArrayList<>();
        names.add(new com.betterloka.stats.NameHistoryService.FormerName(first, null, false));
        names.addAll(rest);
        return List.copyOf(names);
    }

    /**
     * The trait chips: a centred, wrapping row of coloured pills, each explaining itself on hover.
     *
     * <p>The hovered chip is only remembered here — the tooltip is drawn at the end of {@code render}
     * so it is not clipped by the scissor the content is drawn inside.
     */
    private int renderTraits(DrawContext context, int left, int y, int width) {
        List<PlayerTrait> traits = PlayerTrait.of(profile, arenaCurrent, LocalDate.now());
        if (traits.isEmpty()) {
            return y;
        }

        int inner = width - CARD_PADDING * 2;
        List<List<PlayerTrait>> rows = wrapChips(traits, inner);
        int height = CARD_PADDING * 2 + rows.size() * CHIP_HEIGHT + (rows.size() - 1) * CHIP_GAP;
        GuiTheme.panel(context, left, y, width, height);

        int rowY = y + CARD_PADDING;
        for (List<PlayerTrait> row : rows) {
            int rowWidth = 0;
            for (PlayerTrait trait : row) {
                rowWidth += chipWidth(trait) + CHIP_GAP;
            }
            rowWidth -= CHIP_GAP;

            int chipX = left + CARD_PADDING + (inner - rowWidth) / 2;
            for (PlayerTrait trait : row) {
                int chipW = chipWidth(trait);
                int color = GuiTheme.traitColor(trait.level());
                GuiTheme.chip(context, chipX, rowY, chipW, CHIP_HEIGHT, color);
                context.drawTextWithShadow(this.textRenderer, chipLabel(trait), chipX + CHIP_PADDING,
                        rowY + (CHIP_HEIGHT - 8) / 2, color);
                if (hovering(chipX, rowY, chipW, CHIP_HEIGHT)) {
                    hoveredTrait = trait;
                }
                chipX += chipW + CHIP_GAP;
            }
            rowY += CHIP_HEIGHT + CHIP_GAP;
        }
        return y + height + CARD_GAP;
    }

    /** Greedy wrap: chips keep their order, and a row is broken as soon as the next one would not fit. */
    private List<List<PlayerTrait>> wrapChips(List<PlayerTrait> traits, int inner) {
        List<List<PlayerTrait>> rows = new ArrayList<>();
        List<PlayerTrait> row = new ArrayList<>();
        int used = 0;
        for (PlayerTrait trait : traits) {
            int chipW = chipWidth(trait);
            if (!row.isEmpty() && used + CHIP_GAP + chipW > inner) {
                rows.add(row);
                row = new ArrayList<>();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : CHIP_GAP) + chipW;
            row.add(trait);
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private int chipWidth(PlayerTrait trait) {
        return this.textRenderer.getWidth(chipLabel(trait)) + CHIP_PADDING * 2;
    }

    private static Text chipLabel(PlayerTrait trait) {
        return Text.translatable(trait.kind().labelKey(), trait.detail());
    }

    private boolean hovering(int x, int y, int width, int height) {
        return lastMouseX >= x && lastMouseX < x + width && lastMouseY >= y && lastMouseY < y + height
                && lastMouseY >= VIEWPORT_TOP && lastMouseY < viewportBottom();
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
                Text.translatable("betterloka.finder.recent", shownFights().size()),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        for (FightSummary fight : shownFights()) {
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

    /** The fights shown as rows; the rest are fetched but only counted. */
    private List<FightSummary> shownFights() {
        List<FightSummary> fights = profile.recentFights();
        return fights.size() > PlayerStatsService.RECENT_FIGHT_COUNT
                ? fights.subList(0, PlayerStatsService.RECENT_FIGHT_COUNT)
                : fights;
    }

    /**
     * RIVI against ordinary Conquest, over every battle either side has ever fought.
     *
     * <p>This used to be a sample of the last nine fights, because that is all EldritchBot lists and
     * which map a fight was on lives on the fight's own page. Loka's battle archive carries every
     * battle ever fought with a line per player, so the split is now the whole career rather than a
     * sample of it — and the header says how far the index has read when it is still reading.
     *
     * <p>No wins here: Loka's battle records name no winner, so the career win rate stays on the
     * card above, where it comes from EldritchBot and covers both formats together.
     */
    private int renderFormatSplit(DrawContext context, int left, int y, int width, int inner) {
        BattleIndex index = BetterLokaClient.battleIndex();
        BattleIndex.PlayerRecord record = index.of(profile.uuid());
        BattleIndex.Progress progress = index.progress();
        BattleIndex.Bucket rivi = record.riviTotal();
        BattleIndex.Bucket conquest = record.conquestTotal();

        context.drawTextWithShadow(this.textRenderer, splitHeader(rivi, conquest, progress),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        if (rivi.isEmpty() && conquest.isEmpty()) {
            int height = CARD_PADDING * 2 + ROW_HEIGHT;
            GuiTheme.panel(context, left, y, width, height);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(progress.sweeping()
                            ? "betterloka.finder.indexing_wait" : "betterloka.finder.no_battles"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            return y + height + CARD_GAP;
        }

        return renderSplitCard(context, left, y, width, inner, rivi, conquest);
    }

    /** How many battles the split is over, and how much of the archive it has been taken from. */
    private Text splitHeader(BattleIndex.Bucket rivi, BattleIndex.Bucket conquest,
                             BattleIndex.Progress progress) {
        int fights = rivi.fights() + conquest.fights();
        if (progress.sweeping() && !progress.complete()) {
            return Text.translatable("betterloka.finder.split_indexing", fights, progress.percent());
        }
        return Text.translatable("betterloka.finder.split_all", fights);
    }

    /** The two-column card: Rivi on the left, Conquest on the right. */
    private int renderSplitCard(DrawContext context, int left, int y, int width, int inner,
                                BattleIndex.Bucket rivi, BattleIndex.Bucket conquest) {
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 5;
        GuiTheme.panel(context, left, y, width, height);
        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        int column = (inner - 10) / 2;

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.rivi", rivi.fights()).copy().formatted(Formatting.BOLD),
                textX, textY, GuiTheme.ACCENT);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.conquest", conquest.fights()).copy().formatted(Formatting.BOLD),
                textX + column + 10, textY, GuiTheme.TEXT);

        splitRow(context, textX, textY + ROW_HEIGHT, column, "betterloka.finder.stat.kills",
                value(rivi.isEmpty(), rivi.kills()), value(conquest.isEmpty(), conquest.kills()),
                GuiTheme.GOOD);
        splitRow(context, textX, textY + ROW_HEIGHT * 2, column, "betterloka.finder.stat.deaths",
                value(rivi.isEmpty(), rivi.deaths()), value(conquest.isEmpty(), conquest.deaths()),
                GuiTheme.BAD);
        splitRow(context, textX, textY + ROW_HEIGHT * 3, column, "betterloka.finder.stat.fights",
                value(rivi.isEmpty(), rivi.fights()), value(conquest.isEmpty(), conquest.fights()),
                GuiTheme.TEXT);

        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 4, column,
                label("betterloka.finder.stat.kd"), rivi.isEmpty() ? "—" : rivi.killDeathText(),
                rivi.isEmpty() ? GuiTheme.MUTED : GuiTheme.ratioColor(rivi.killDeathRatio()));
        GuiTheme.statRow(context, this.textRenderer, textX + column + 10, textY + ROW_HEIGHT * 4, column,
                label("betterloka.finder.stat.kd"), conquest.isEmpty() ? "—" : conquest.killDeathText(),
                conquest.isEmpty() ? GuiTheme.MUTED : GuiTheme.ratioColor(conquest.killDeathRatio()));

        return y + height + CARD_GAP;
    }

    /** A dash rather than a zero where there is nothing to report at all. */
    private static String value(boolean empty, int number) {
        return empty ? "—" : String.valueOf(number);
    }

    private void splitRow(DrawContext context, int x, int y, int column, String key,
                          String leftValue, String rightValue, int color) {
        GuiTheme.statRow(context, this.textRenderer, x, y, column, label(key), leftValue, color);
        GuiTheme.statRow(context, this.textRenderer, x + column + 10, y, column, label(key), rightValue, color);
    }

    /**
     * This calendar month, from the fights EldritchBot lists.
     *
     * <p>Same cap as everywhere else: nine fights are all it publishes, so a busy month shows the
     * nine most recent of it and the header says so.
     */
    private int renderMonth(DrawContext context, int left, int top, int width) {
        int inner = width - CARD_PADDING * 2;
        int y = top;

        BattleIndex index = BetterLokaClient.battleIndex();
        BattleIndex.PlayerRecord record = index.of(profile.uuid());
        BattleIndex.Progress progress = index.progress();
        String month = BattleIndex.monthKey(LocalDate.now());
        BattleIndex.Bucket rivi = record.rivi(month);
        BattleIndex.Bucket conquest = record.conquest(month);
        BattleIndex.Bucket totals = rivi.plus(conquest);

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.month_of", monthName()), left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        if (totals.isEmpty()) {
            int height = CARD_PADDING * 2 + ROW_HEIGHT;
            GuiTheme.panel(context, left, y, width, height);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(progress.sweeping() && !progress.complete()
                            ? "betterloka.finder.indexing_wait" : "betterloka.finder.no_month_fights"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            return y + height + CARD_GAP;
        }

        // Every battle this month, from the archive rather than from the handful EldritchBot lists.
        y = card(context, left, y, width, 2, (x, rowY) -> {
            twoColumns(context, x, rowY, inner,
                    label("betterloka.finder.stat.fights"), String.valueOf(totals.fights()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.kd"), totals.killDeathText(),
                    GuiTheme.ratioColor(totals.killDeathRatio()));
            twoColumns(context, x, rowY + ROW_HEIGHT, inner,
                    label("betterloka.finder.stat.kills"), String.valueOf(totals.kills()), GuiTheme.GOOD,
                    label("betterloka.finder.stat.deaths"), String.valueOf(totals.deaths()), GuiTheme.BAD);
        });

        y = renderSplitCard(context, left, y, width, inner, rivi, conquest);

        // What a fight page carries and a battle record does not — the consumables and the wins.
        // Only the fights EldritchBot still lists have it, so it is a separate card that says so.
        List<FightSummary> detailed = FightBreakdown.thisMonth(profile.recentFights(), LocalDate.now());
        FightBreakdown sample = FightBreakdown.of(detailed);
        if (sample.isEmpty()) {
            return y;
        }
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.month_detail", sample.fights()),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        y = card(context, left, y, width, 3, (x, rowY) -> {
            twoColumns(context, x, rowY, inner,
                    label("betterloka.finder.stat.wins"), String.valueOf(sample.wins()), GuiTheme.GOOD,
                    label("betterloka.finder.stat.losses"), String.valueOf(sample.losses()), GuiTheme.BAD);
            twoColumns(context, x, rowY + ROW_HEIGHT, inner,
                    label("betterloka.finder.stat.assists"), String.valueOf(sample.assists()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.golems"), String.valueOf(sample.golems()), GuiTheme.TEXT);
            twoColumns(context, x, rowY + ROW_HEIGHT * 2, inner,
                    label("betterloka.finder.stat.potions"), String.valueOf(sample.potions()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.pearls"), String.valueOf(sample.pearls()), GuiTheme.TEXT);
        });

        return y;
    }

    /**
     * Who this player is the nemesis of, this conquest month.
     *
     * <p>A nemesis is whoever has killed you most this month. Every count comes from the fight pages
     * read so far: EldritchBot lists a player's last ten fights and publishes no index of all of
     * them, so the header says how many fights it is over rather than letting the list read as a
     * complete tally.
     */
    private int renderNemesis(DrawContext context, int left, int top, int width, int inner) {
        int y = top;
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.nemesis_of", monthName()),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        if (nemesis.nemesisOf().isEmpty()) {
            return card(context, left, y, width, 1, (x, rowY) ->
                    context.drawTextWithShadow(this.textRenderer,
                            label(nemesisLoading ? "betterloka.finder.loading"
                                    : "betterloka.finder.nemesis_none"), x, rowY, GuiTheme.MUTED));
        }

        int rows = nemesis.nemesisOf().size();
        int height = CARD_PADDING * 2 + ROW_HEIGHT * (rows + 1);
        GuiTheme.panel(context, left, y, width, height);
        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        for (NemesisIndex.Tally tally : nemesis.nemesisOf()) {
            GuiTheme.statRow(context, this.textRenderer, textX, textY, inner, tally.name(),
                    Text.translatable("betterloka.finder.nemesis_kills", tally.kills()).getString(),
                    GuiTheme.GOOD);
            textY += ROW_HEIGHT;
        }
        // How deep the month was read. Without it the list reads as the whole month, and it is not.
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.finder.nemesis_source", nemesis.fightsRead()),
                textX, textY, GuiTheme.MUTED);
        return y + height + CARD_GAP;
    }

    private static String monthName() {
        return LocalDate.now().getMonth()
                .getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Locale.getDefault());
    }

    /**
     * The ranked 1v1 record, one card per ladder.
     *
     * <p>This season's standings arrive with the rest of the profile; the best rank ever needs every
     * past season's final table, so that line says it is loading until the index is built and then
     * fills in behind the card.
     */
    private int renderRankedCards(DrawContext context, int left, int y, int width, int inner) {
        if (arenaCurrent.isEmpty()) {
            return card(context, left, y, width, 1, (x, rowY) ->
                    GuiTheme.statRow(context, this.textRenderer, x, rowY, inner,
                            label("betterloka.finder.ranked"),
                            label(arenaLoading ? "betterloka.finder.loading" : "betterloka.finder.unranked"),
                            GuiTheme.MUTED));
        }

        boolean any = false;
        for (ArenaService.Standing standing : arenaCurrent) {
            ArenaEntry current = standing.current();
            if (current == null) {
                continue;
            }
            any = true;
            ArenaService.Best best = bestFor(standing.ladder());
            int rows = 4;
            int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
            GuiTheme.panel(context, left, y, width, height);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(standing.ladder().translationKey()).copy().formatted(Formatting.BOLD),
                    textX, textY, GuiTheme.ACCENT);
            String position = "#" + current.position();
            context.drawTextWithShadow(this.textRenderer, position,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(position), textY, GuiTheme.MUTED);

            twoColumns(context, textX, textY + ROW_HEIGHT, inner,
                    label("betterloka.finder.duels"), String.valueOf(current.duels()), GuiTheme.TEXT,
                    label("betterloka.finder.stat.winrate"), current.winRatioText(), GuiTheme.TEXT);
            twoColumns(context, textX, textY + ROW_HEIGHT * 2, inner,
                    label("betterloka.finder.stat.wins"), String.valueOf(current.wins()), GuiTheme.GOOD,
                    label("betterloka.finder.stat.losses"), String.valueOf(current.losses()), GuiTheme.BAD);

            // The rank goes in its own row: it is coloured by tier, which a shared column would lose.
            GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 3, inner,
                    label("betterloka.finder.rank"),
                    current.rank() == null ? "—" : current.rank().label(),
                    current.rank() == null ? GuiTheme.MUTED : current.rank().color());
            y += height + CARD_GAP;

            y = card(context, left, y, width, 1, (x, rowY) -> {
                if (best == null) {
                    GuiTheme.statRow(context, this.textRenderer, x, rowY, inner,
                            label("betterloka.finder.best_rank"),
                            label(arenaHistoryLoading ? "betterloka.finder.loading" : "betterloka.finder.unranked"),
                            GuiTheme.MUTED);
                    return;
                }
                GuiTheme.statRow(context, this.textRenderer, x, rowY, inner,
                        Text.translatable("betterloka.finder.best_rank_season", best.season()).getString(),
                        best.entry().rank().label(), best.entry().rank().color());
            });
        }

        if (!any) {
            return card(context, left, y, width, 1, (x, rowY) ->
                    GuiTheme.statRow(context, this.textRenderer, x, rowY, inner,
                            label("betterloka.finder.ranked"), label("betterloka.finder.unranked"),
                            GuiTheme.MUTED));
        }
        return y;
    }

    private ArenaService.Best bestFor(ArenaApi.Ladder ladder) {
        for (ArenaService.Standing standing : arenaBest) {
            if (standing.ladder() == ladder) {
                return standing.best();
            }
        }
        return null;
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
