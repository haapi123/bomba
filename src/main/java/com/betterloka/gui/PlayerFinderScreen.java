package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.ArenaApi;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.LokaTown;
import com.betterloka.stats.ArenaService;
import com.betterloka.stats.BattleIndex;
import com.betterloka.stats.FightBreakdown;
import com.betterloka.stats.FightSummary;
import com.betterloka.stats.PlayerProfile;
import com.betterloka.stats.PlayerStatsService;
import com.betterloka.stats.PlayerTrait;
import com.betterloka.stats.EloService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Looks a Loka player up and shows their Conquest record.
 *
 * <p>Laid out like a market item's profile: the player on a stand to the left — their own skin, in
 * 3D, turning slowly and turnable by dragging — with who they are under it and their K/D on a tag at
 * its foot, and everything else in a scrolling column of tiles and cards to the right.
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

    /** One number in a tile: big value, small label under it, the tile edged in its colour. */
    private record Tile(String value, String label, int color) {
    }

    private static final int MAX_CONTENT_WIDTH = 470;
    private static final int SEARCH_BUTTON_WIDTH = 60;
    private static final int TITLE_Y = 8;
    private static final int SEARCH_Y = 21;
    private static final int SEARCH_HEIGHT = 20;
    private static final int TOP = SEARCH_Y + SEARCH_HEIGHT + 6;
    private static final int TAB_HEIGHT = 16;
    private static final int VIEWPORT_TOP = TOP + TAB_HEIGHT + 6;

    private static final int STAND_WIDTH = 132;
    private static final int TAG_HEIGHT = 38;

    private static final int LINE = 11;
    private static final int CARD_PADDING = 6;
    private static final int TILE_HEIGHT = 30;
    private static final int TILE_GAP = 4;
    private static final int SECTION_GAP = 10;

    private static final int CHIP_HEIGHT = 14;
    private static final int CHIP_PADDING = 5;
    private static final int CHIP_GAP = 4;

    private static final int CARD_BORDER = 0xFF2C2C38;
    private static final int SEPARATOR = 0xFF33333F;

    /** How wide a chip's explanation is allowed to get before it wraps. */
    private static final int TOOLTIP_WIDTH = 190;

    /** How fast the player turns on the stand, in degrees a second, and how far they lean when tilted. */
    private static final float SPIN_DEGREES_PER_SECOND = 40f;
    private static final float MAX_TILT = 40f;

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private EditBox nameField;
    private Button searchButton;
    private final List<Button> tabButtons = new ArrayList<>();

    private PlayerProfile profile;
    private Tab tab = Tab.PROFILE;
    private Component error;
    private boolean searching;

    /** Ranked 1v1: this season's standings, and the best rank of any season. */
    private List<ArenaService.Standing> arenaCurrent = List.of();
    private List<ArenaService.Standing> arenaBest = List.of();
    private boolean arenaLoading;
    private boolean arenaHistoryLoading;

    // The player on the stand: their skin, the two shapes it can come in, and how they are turned.
    private Supplier<PlayerSkin> skin;
    private Model.Simple wideModel;
    private Model.Simple slimModel;
    private float spin = 30f;
    private float tilt = -5f;
    private long lastFrame;
    private boolean turning;
    private int skinX0;
    private int skinY0;
    private int skinX1;
    private int skinY1;

    /** The chip under the pointer this frame, and where the pointer is; both reset every frame. */
    private PlayerTrait hoveredTrait;
    private int lastMouseX;
    private int lastMouseY;
    /** Guards against a stale request overwriting the results of a newer one. */
    private int searchGeneration;

    public PlayerFinderScreen(Screen parent) {
        super(Component.translatable("betterloka.finder.title"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int bottom() {
        return this.height - 31;
    }

    private int detailsX() {
        return contentLeft() + STAND_WIDTH + 10;
    }

    private int detailsWidth() {
        return contentLeft() + contentWidth() - detailsX();
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int fieldWidth = width - SEARCH_BUTTON_WIDTH - 4;

        scrollPanel.setViewport(detailsX(), VIEWPORT_TOP, detailsWidth(),
                Math.max(20, bottom() - VIEWPORT_TOP));

        String previous = nameField != null ? nameField.getValue() : "";
        nameField = new EditBox(this.font, left, SEARCH_Y, fieldWidth, SEARCH_HEIGHT,
                Component.translatable("betterloka.finder.field"));
        nameField.setMaxLength(16);
        nameField.setHint(Component.translatable("betterloka.finder.placeholder").withStyle(ChatFormatting.DARK_GRAY));
        nameField.setValue(previous);
        addRenderableWidget(nameField);

        searchButton = Button.builder(Component.translatable("betterloka.finder.search"), button -> search())
                .bounds(left + fieldWidth + 4, SEARCH_Y, SEARCH_BUTTON_WIDTH, SEARCH_HEIGHT)
                .build();
        addRenderableWidget(searchButton);

        tabButtons.clear();
        Tab[] tabs = Tab.values();
        int share = (detailsWidth() - 4 * (tabs.length - 1)) / tabs.length;
        int tabX = detailsX();
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            // The last one takes whatever the division left over, so the row ends flush.
            int thisWidth = i == tabs.length - 1 ? detailsX() + detailsWidth() - tabX : share;
            tabButtons.add(addRenderableWidget(Button.builder(tabLabel(target), button -> selectTab(target))
                    .bounds(tabX, TOP, thisWidth, TAB_HEIGHT).build()));
            tabX += share + 4;
        }

        // The two nameplate switches and Back, in one row under everything.
        int third = Math.min(160, (width - 8) / 3);
        int rowLeft = this.width / 2 - (third * 3 + 8) / 2;
        addRenderableWidget(Button.builder(kdToggleLabel(), button -> {
                    BetterLokaClient.config().setShowNameplateKd(!BetterLokaClient.config().showNameplateKd());
                    button.setMessage(kdToggleLabel());
                })
                .bounds(rowLeft, this.height - 25, third, 20)
                .build());
        addRenderableWidget(Button.builder(eloToggleLabel(), button -> {
                    BetterLokaClient.config().setShowNameplateElo(!BetterLokaClient.config().showNameplateElo());
                    button.setMessage(eloToggleLabel());
                })
                .bounds(rowLeft + third + 4, this.height - 25, third, 20)
                .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(rowLeft + (third + 4) * 2, this.height - 25, third, 20).build());

        if (wideModel == null) {
            EntityModelSet models = this.minecraft.getEntityModels();
            wideModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
            slimModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
        }

        setInitialFocus(nameField);
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
        scrollPanel.reset();
        for (int i = 0; i < tabButtons.size(); i++) {
            tabButtons.get(i).setMessage(tabLabel(Tab.values()[i]));
        }
    }

    private Component eloToggleLabel() {
        boolean on = BetterLokaClient.config().showNameplateElo();
        return Component.translatable("betterloka.finder.elo_toggle",
                Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    /** The player's Conquest Elo, if they are rated; looked up locally, so it costs nothing. */
    private EloService.Entry rating() {
        EloService service = BetterLokaClient.elo();
        if (service == null || profile == null) {
            return null;
        }
        EloService.Entry entry = service.byUuid(profile.uuid());
        return entry != null || profile.uuid() != null ? entry : service.byName(profile.name());
    }

    private Component kdToggleLabel() {
        boolean on = BetterLokaClient.config().showNameplateKd();
        return Component.translatable("betterloka.finder.kd_toggle",
                Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    private void search() {
        String name = nameField.getValue().trim();
        if (name.isEmpty() || searching) {
            return;
        }
        searching = true;
        error = null;
        profile = null;
        skin = null;
        arenaCurrent = List.of();
        arenaBest = List.of();
        arenaLoading = true;
        arenaHistoryLoading = true;
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
        BetterLokaClient.battleIndex().startIndexing(BetterLokaClient.backgroundExecutor());

        PlayerStatsService stats = BetterLokaClient.stats();
        stats.lookup(name, headline -> applyOnClientThread(generation, () -> {
            // Career totals are in; the fight rows below fill in as their pages land.
            searching = false;
            error = null;
            showProfile(headline);
            // Names change between seasons, so the historical index is searched by UUID once Loka's
            // record of the account has supplied one.
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
                showProfile(result);
                error = null;
            }
        }));
    }

    /** Puts a profile up, and the player on the stand facing the viewer a little from the side. */
    private void showProfile(PlayerProfile loaded) {
        boolean samePlayer = profile != null && loaded != null && profile.uuid() != null
                && profile.uuid().equals(loaded.uuid());
        profile = loaded;
        if (loaded != null && (skin == null || !samePlayer)) {
            skin = PlayerSkins.of(loaded.uuid());
            if (!samePlayer) {
                spin = 30f;
                tilt = -5f;
            }
        }
    }

    /** Runs {@code action} on the render thread, unless the search has been superseded or the screen closed. */
    private void applyOnClientThread(int generation, Runnable action) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.execute(() -> {
            if (generation == searchGeneration && this.minecraft.gui.screen() == this) {
                action.run();
            }
        });
    }

    private static Component describe(String name, Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        if (cause instanceof ApiException apiException && apiException.notFound()) {
            return Component.translatable("betterloka.finder.not_found", name).withStyle(ChatFormatting.RED);
        }
        return Component.translatable("betterloka.finder.unreachable").withStyle(ChatFormatting.RED);
    }

    private boolean showingProfile() {
        return profile != null && !searching && error == null;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.isConfirmation() && nameField.isFocused()) {
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
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (showingProfile() && click.button() == InputConstants.MOUSE_BUTTON_LEFT
                && click.x() >= skinX0 && click.x() < skinX1 && click.y() >= skinY0 && click.y() < skinY1) {
            turning = true;
            return true;
        }
        return scrollPanel.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (turning) {
            // As the game's own skin preview turns: sideways spins, up and down leans.
            spin += (float) deltaX * 2.5f;
            tilt = Mth.clamp(tilt - (float) deltaY * 2.5f, -MAX_TILT, MAX_TILT);
            return true;
        }
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        turning = false;
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        boolean showing = showingProfile();
        for (Button button : tabButtons) {
            button.visible = showing;
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
        searchButton.active = !searching && !nameField.getValue().trim().isEmpty();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        hoveredTrait = null;

        context.centeredText(this.font, this.title, this.width / 2, TITLE_Y, GuiTheme.TEXT);

        long now = System.currentTimeMillis();
        float seconds = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (!turning) {
            spin = (spin + seconds * SPIN_DEGREES_PER_SECOND) % 360f;
        }

        if (!showing) {
            drawMessage(context);
            return;
        }

        drawStand(context, mouseX, mouseY);
        drawDetails(context, mouseX, mouseY);

        // Drawn last and outside the scissor, or the panel the chip sits in would clip its own tooltip.
        if (hoveredTrait != null) {
            // Wrapped by hand: Minecraft draws a tooltip line as given, so a whole sentence would run
            // off the side of the screen.
            List<FormattedCharSequence> lines = new ArrayList<>();
            lines.add(chipLabel(hoveredTrait).copy()
                    .withColor(GuiTheme.traitColor(hoveredTrait.level()) & 0xFFFFFF).getVisualOrderText());
            lines.addAll(this.font.split(
                    Component.translatable(hoveredTrait.kind().reasonKey(hoveredTrait.level()), hoveredTrait.detail())
                            .withStyle(ChatFormatting.GRAY),
                    TOOLTIP_WIDTH));
            context.setTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        }
    }

    /** Before a search, while one runs, or when it failed: one card where the profile would be. */
    private void drawMessage(GuiGraphicsExtractor context) {
        int left = contentLeft();
        int width = contentWidth();
        Component message = searching ? Component.translatable("betterloka.finder.searching")
                : error != null ? error : Component.translatable("betterloka.finder.hint");
        int color = searching ? GuiTheme.MUTED : error != null ? GuiTheme.BAD : GuiTheme.MUTED;
        List<FormattedCharSequence> lines = this.font.split(message, width - 24);
        int height = 20 + lines.size() * LINE;
        MapStyle.card(context, left, TOP, width, height, CARD_BORDER);
        int y = TOP + 10;
        for (FormattedCharSequence line : lines) {
            context.centeredText(this.font, line, this.width / 2, y, color);
            y += LINE;
        }
    }

    // --- the stand ---

    /**
     * The player on their stand: their skin turning slowly over a glow in their K/D's colour, who
     * they are under it, and the K/D itself on a tag at the foot, as a stall hangs a price.
     */
    private void drawStand(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int left = contentLeft();
        int top = TOP;
        int bottom = bottom();
        EldritchStats stats = profile.stats();
        double ratio = stats.killDeathRatio();
        int glow = GuiTheme.ratioColor(ratio);
        MapStyle.card(context, left, top, STAND_WIDTH, bottom - top, CARD_BORDER);

        int centreX = left + STAND_WIDTH / 2;
        // As tall as the stand allows once the name, badge, dates, town and tag below have their room.
        int skinHeight = Mth.clamp(bottom - top - TAG_HEIGHT - 122, 54, 150);
        int tagY = bottom - TAG_HEIGHT - 6;
        int skinWidth = skinHeight * 3 / 4;
        skinX0 = centreX - skinWidth / 2;
        skinX1 = skinX0 + skinWidth;
        skinY0 = top + 10;
        skinY1 = skinY0 + skinHeight;

        // A soft round glow behind them, stronger towards the middle.
        int glowY = skinY0 + skinHeight / 2;
        for (int ring = 0; ring < 5; ring++) {
            int radius = skinHeight / 2 + 4 - ring * (skinHeight / 12);
            disc(context, centreX, glowY, radius, (0x0E + ring * 0x06) << 24 | (glow & 0xFFFFFF));
        }

        // The stand under their feet.
        int plinthY = skinY1 - 2;
        context.fill(centreX - 30, plinthY, centreX + 30, plinthY + 4, 0xFF4A4A5A);
        context.fill(centreX - 26, plinthY + 4, centreX + 26, plinthY + 10, 0xFF33333F);
        context.fill(centreX - 30, plinthY, centreX + 30, plinthY + 1, 0xFF6E6E82);

        PlayerSkin look = skin != null ? skin.get() : null;
        if (look != null) {
            Model.Simple model = look.model() == PlayerModelType.SLIM ? slimModel : wideModel;
            float scale = 0.97f * skinHeight / 2.125f;
            context.skin(model, look.body().texturePath(), scale, tilt, spin, -1.0625f,
                    skinX0, skinY0, skinX1, skinY1);
        }

        // Who they are.
        int y = plinthY + 16;
        Component name = Component.literal(this.font.plainSubstrByWidth(profile.name(), STAND_WIDTH - 12))
                .withStyle(ChatFormatting.BOLD);
        context.centeredText(this.font, name, centreX, y, GuiTheme.ACCENT);
        y += LINE + 3;

        String badge = profile.inFightNow()
                ? Component.translatable("betterloka.finder.live").getString()
                : (profile.rank() == null ? null : profile.rank().toUpperCase(Locale.ROOT));
        if (badge != null) {
            int badgeColor = profile.inFightNow() ? GuiTheme.LIVE : 0xFFB8B8C8;
            int badgeWidth = this.font.width(badge) + CHIP_PADDING * 2;
            GuiTheme.chip(context, centreX - badgeWidth / 2, y, badgeWidth, CHIP_HEIGHT, badgeColor);
            context.centeredText(this.font, badge, centreX, y + 3, badgeColor);
            y += CHIP_HEIGHT + 6;
        }

        boolean haveFirstSeen = profile.firstSeen() != null;
        String when = haveFirstSeen
                ? TimeFormat.date(profile.firstSeen())
                : (stats.lastFight() != null ? stats.lastFight() : "—");
        context.centeredText(this.font,
                Component.translatable(haveFirstSeen ? "betterloka.finder.first_seen" : "betterloka.finder.last_fight"),
                centreX, y, GuiTheme.MUTED);
        y += LINE - 1;
        context.centeredText(this.font, this.font.plainSubstrByWidth(when, STAND_WIDTH - 12), centreX, y,
                GuiTheme.TEXT);
        y += LINE + 3;

        // Their town, while there is room above the tag for it.
        String townName = profile.displayTown();
        if (y + LINE * 2 < tagY) {
            context.centeredText(this.font, Component.translatable("betterloka.finder.town"), centreX, y,
                    GuiTheme.MUTED);
            y += LINE - 1;
            context.centeredText(this.font,
                    this.font.plainSubstrByWidth(townName != null ? townName : label("betterloka.finder.townless"),
                            STAND_WIDTH - 12),
                    centreX, y, townName != null ? GuiTheme.TEXT : GuiTheme.MUTED);
            y += LINE;
        }

        // The K/D and the Elo on two tags at the foot of the stand, when there is room for them.
        if (tagY > y + 2) {
            int tagW = (STAND_WIDTH - 20) / 2;
            int leftTag = left + 8;
            int rightTag = left + STAND_WIDTH - 8 - tagW;
            tag(context, leftTag, tagY, tagW, String.format(Locale.ROOT, "%.2f", ratio),
                    Component.translatable("betterloka.finder.stat.kd"), glow);
            EloService.Entry rated = rating();
            tag(context, rightTag, tagY, tagW, rated == null ? "—" : String.valueOf(rated.shown()),
                    Component.translatable("betterloka.finder.elo"),
                    rated == null ? GuiTheme.MUTED : EloColors.argb(rated.elo()));
        }

        if (!turning && mouseX >= skinX0 && mouseX < skinX1 && mouseY >= skinY0 && mouseY < skinY1) {
            context.setTooltipForNextFrame(this.font,
                    List.of(Component.translatable("betterloka.finder.skin_hint").getVisualOrderText()),
                    mouseX, mouseY);
        }
    }

    /** A tag hung at the foot of the stand: one number, large, and what it is under it. */
    private void tag(GuiGraphicsExtractor context, int x, int y, int width, String value, Component label, int color) {
        MapStyle.card(context, x, y, width, TAG_HEIGHT, color);
        int scale = this.font.width(value) * 2 <= width - 6 ? 2 : 1;
        context.pose().pushMatrix();
        context.pose().translate(x + width / 2f - this.font.width(value) * scale / 2f, y + (scale == 2 ? 6 : 10));
        context.pose().scale(scale, scale);
        context.text(this.font, value, 0, 0, color);
        context.pose().popMatrix();
        context.centeredText(this.font, label, x + width / 2, y + 25, GuiTheme.MUTED);
    }

    /** A filled circle, a row at a time. */
    private static void disc(GuiGraphicsExtractor context, int centreX, int centreY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            context.fill(centreX - half, centreY + dy, centreX + half, centreY + dy + 1, color);
        }
    }

    // --- the column of details ---

    private void drawDetails(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int x = detailsX();
        int width = scrollPanel.contentWidth();
        context.enableScissor(x, VIEWPORT_TOP, x + detailsWidth(), bottom());
        int top = scrollPanel.contentTop();
        int end = switch (tab) {
            case MONTH -> renderMonth(context, x, top, width);
            case PROFILE -> renderProfile(context, x, top, width);
        };
        context.disableScissor();
        scrollPanel.setContentHeight(end - top);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /** @return the y coordinate just past the rendered content. */
    private int renderProfile(GuiGraphicsExtractor context, int x, int top, int width) {
        EldritchStats stats = profile.stats();
        int y = renderTraits(context, x, top, width);

        y = section(context, x, y, width, Component.translatable("betterloka.finder.section.elo"));
        y = renderElo(context, x, y, width);
        y += SECTION_GAP - TILE_GAP;

        y = section(context, x, y, width, Component.translatable("betterloka.finder.section.combat"));
        double ratio = stats.killDeathRatio();
        y = tiles(context, x, y, width, 3, List.of(
                new Tile(number(stats.kills()), label("betterloka.finder.stat.kills"), GuiTheme.GOOD),
                new Tile(number(stats.deaths()), label("betterloka.finder.stat.deaths"), GuiTheme.BAD),
                new Tile(String.format(Locale.ROOT, "%.2f", ratio), label("betterloka.finder.stat.kd"),
                        GuiTheme.ratioColor(ratio)),
                new Tile(number(stats.assists()), label("betterloka.finder.stat.assists"), GuiTheme.ACCENT),
                new Tile(number(stats.firstBloods()), label("betterloka.finder.stat.first_bloods"), 0xFFE0605C),
                new Tile(number(stats.closeCalls()), label("betterloka.finder.stat.close_calls"), GuiTheme.LIVE)));
        if (stats.nemesisName() != null) {
            y = card(context, x, y, width, 1, (cardX, rowY, inner) ->
                    GuiTheme.statRow(context, this.font, cardX, rowY, inner,
                            label("betterloka.finder.nemesis"),
                            stats.nemesisName() + " (" + stats.nemesisDeaths() + ")", GuiTheme.BAD));
        }
        y += SECTION_GAP - TILE_GAP;

        y = section(context, x, y, width, Component.translatable("betterloka.finder.section.conquest"));
        double winRate = stats.winRate();
        y = tiles(context, x, y, width, 4, List.of(
                new Tile(number(stats.wins()), label("betterloka.finder.stat.wins"), GuiTheme.GOOD),
                new Tile(number(stats.losses()), label("betterloka.finder.stat.losses"), GuiTheme.BAD),
                new Tile(number(stats.totalFights()), label("betterloka.finder.stat.fights"), GuiTheme.TEXT),
                new Tile(String.format(Locale.ROOT, "%.0f%%", winRate * 100), label("betterloka.finder.stat.winrate"),
                        rateColor(winRate))));
        y = winBar(context, x, y, width, stats.wins(), stats.losses());
        y += SECTION_GAP;

        y = section(context, x, y, width, Component.translatable("betterloka.finder.section.supplies"));
        y = tiles(context, x, y, width, 3, List.of(
                new Tile(number(stats.golems()), label("betterloka.finder.stat.golems"), 0xFFC9B48A),
                new Tile(number(stats.lamps()), label("betterloka.finder.stat.lamps"), 0xFFFFD46B),
                new Tile(number(stats.potions()), label("betterloka.finder.stat.potions"), 0xFFE07BD8),
                new Tile(number(stats.pearls()), label("betterloka.finder.stat.pearls"), 0xFF4FD1B5),
                new Tile(number(stats.food()), label("betterloka.finder.stat.food"), 0xFFE8A85F),
                new Tile(number(stats.ancientIngots()), label("betterloka.finder.stat.ingots"), 0xFFB9A0FF)));
        y += SECTION_GAP - TILE_GAP;

        y = section(context, x, y, width, Component.translatable("betterloka.finder.town"));
        y = renderTownCard(context, x, y, width);
        y += SECTION_GAP - TILE_GAP;

        y = renderFormatSplit(context, x, y, width);
        y += SECTION_GAP - TILE_GAP;

        y = section(context, x, y, width, Component.translatable("betterloka.finder.ranked"));
        y = renderRankedCards(context, x, y, width);

        if (!profile.recentFights().isEmpty()) {
            y += SECTION_GAP - TILE_GAP;
            y = section(context, x, y, width,
                    Component.translatable("betterloka.finder.recent", shownFights().size()));
            y = renderFights(context, x, y, width);
        }
        return y;
    }

    /**
     * The trait chips: a centred, wrapping row of coloured pills, each explaining itself on hover.
     *
     * <p>The hovered chip is only remembered here — the tooltip is drawn at the end of {@code render}
     * so it is not clipped by the scissor the content is drawn inside.
     */
    private int renderTraits(GuiGraphicsExtractor context, int left, int y, int width) {
        List<PlayerTrait> traits = PlayerTrait.of(profile, arenaCurrent, LocalDate.now());
        if (traits.isEmpty()) {
            return y;
        }

        int inner = width - CARD_PADDING * 2;
        List<List<PlayerTrait>> rows = wrapChips(traits, inner);
        int height = CARD_PADDING * 2 + rows.size() * CHIP_HEIGHT + (rows.size() - 1) * CHIP_GAP;
        MapStyle.card(context, left, y, width, height, CARD_BORDER);

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
                context.text(this.font, chipLabel(trait), chipX + CHIP_PADDING,
                        rowY + (CHIP_HEIGHT - 8) / 2, color);
                if (hovering(chipX, rowY, chipW, CHIP_HEIGHT)) {
                    hoveredTrait = trait;
                }
                chipX += chipW + CHIP_GAP;
            }
            rowY += CHIP_HEIGHT + CHIP_GAP;
        }
        return y + height + SECTION_GAP;
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
        return this.font.width(chipLabel(trait)) + CHIP_PADDING * 2;
    }

    private static Component chipLabel(PlayerTrait trait) {
        return Component.translatable(trait.kind().labelKey(), trait.detail());
    }

    private boolean hovering(int x, int y, int width, int height) {
        return lastMouseX >= x && lastMouseX < x + width && lastMouseY >= y && lastMouseY < y + height
                && lastMouseY >= VIEWPORT_TOP && lastMouseY < bottom();
    }

    /**
     * Conquest Elo, from loka-elo.pages.dev: the rating, the place on the leaderboard, the best it
     * has been, and how many rated fights it rests on.
     */
    private int renderElo(GuiGraphicsExtractor context, int x, int y, int width) {
        EloService service = BetterLokaClient.elo();
        EloService.Entry rated = rating();
        if (rated == null) {
            boolean loading = service == null || !service.ready();
            return card(context, x, y, width, 1, (textX, textY, inner) ->
                    context.text(this.font, Component.translatable(loading
                            ? "betterloka.finder.elo_loading" : "betterloka.finder.elo_unrated"),
                            textX, textY, GuiTheme.MUTED));
        }
        y = tiles(context, x, y, width, 4, List.of(
                new Tile(number((int) rated.shown()), label("betterloka.finder.elo"), EloColors.argb(rated.elo())),
                new Tile("#" + number(rated.rank()), label("betterloka.finder.elo_rank"), GuiTheme.LIVE),
                new Tile(number((int) Math.round(rated.peakElo())), label("betterloka.finder.elo_peak"),
                        EloColors.argb(rated.peakElo())),
                new Tile(number(rated.games()), label("betterloka.finder.elo_games"), GuiTheme.TEXT)));
        String source = Component.translatable("betterloka.finder.elo_source").getString()
                + (rated.lastActive() == null ? ""
                : "  ·  " + Component.translatable("betterloka.finder.elo_active", rated.lastActive()).getString());
        context.text(this.font, this.font.plainSubstrByWidth(source, width), x + 2, y, GuiTheme.MUTED);
        return y + LINE + TILE_GAP;
    }

    /** A heading: an accent mark, the title in bold, and a rule under it. */
    private int section(GuiGraphicsExtractor context, int x, int y, int width, Component title) {
        context.fill(x, y, x + 2, y + 8, GuiTheme.ACCENT);
        context.text(this.font, title.copy().withStyle(ChatFormatting.BOLD), x + 6, y, GuiTheme.TEXT);
        context.fill(x, y + 11, x + width, y + 12, SEPARATOR);
        return y + 16;
    }

    /** Tiles, {@code perRow} to a row; returns the y just past the last row and one gap. */
    private int tiles(GuiGraphicsExtractor context, int x, int y, int width, int perRow, List<Tile> tiles) {
        int tileWidth = (width - TILE_GAP * (perRow - 1)) / perRow;
        int rows = (tiles.size() + perRow - 1) / perRow;
        for (int i = 0; i < tiles.size(); i++) {
            int column = i % perRow;
            int row = i / perRow;
            int tileX = x + column * (tileWidth + TILE_GAP);
            // The last column takes whatever the division left over, so the row ends flush.
            int thisWidth = column == perRow - 1 ? x + width - tileX : tileWidth;
            tile(context, tileX, y + row * (TILE_HEIGHT + TILE_GAP), thisWidth, tiles.get(i));
        }
        return y + rows * (TILE_HEIGHT + TILE_GAP);
    }

    private void tile(GuiGraphicsExtractor context, int x, int y, int width, Tile tile) {
        MapStyle.card(context, x, y, width, TILE_HEIGHT, CARD_BORDER);
        // Its colour along the top edge, and faintly through the tile.
        context.fill(x + 2, y + 1, x + width - 2, y + 3, tile.color());
        context.fill(x + 2, y + 3, x + width - 2, y + TILE_HEIGHT - 2, 0x14000000 | (tile.color() & 0xFFFFFF));
        Component value = Component.literal(tile.value()).withStyle(ChatFormatting.BOLD);
        context.centeredText(this.font, value, x + width / 2, y + 7, tile.color());
        context.centeredText(this.font, this.font.plainSubstrByWidth(tile.label(), width - 6), x + width / 2,
                y + 18, GuiTheme.MUTED);
    }

    /** Wins against losses as one bar: green for the wins, red for the rest. */
    private int winBar(GuiGraphicsExtractor context, int x, int y, int width, int wins, int losses) {
        int total = wins + losses;
        context.fill(x, y, x + width, y + 4, 0xFF23232B);
        if (total > 0) {
            int split = (int) Math.round((double) width * wins / total);
            context.fill(x, y, x + split, y + 4, GuiTheme.GOOD);
            context.fill(x + split, y, x + width, y + 4, GuiTheme.BAD);
        }
        return y + 4;
    }

    private static int rateColor(double rate) {
        return rate >= 0.55 ? GuiTheme.GOOD : rate <= 0.45 ? GuiTheme.BAD : GuiTheme.LIVE;
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private int renderTownCard(GuiGraphicsExtractor context, int left, int y, int width) {
        LokaTown town = profile.town();
        int rows = town == null ? 2 : 3;
        return card(context, left, y, width, rows, (textX, textY, inner) -> {
            String townName = profile.displayTown();
            GuiTheme.statRow(context, this.font, textX, textY, inner, label("betterloka.finder.town"),
                    townName != null ? townName : label("betterloka.finder.townless"),
                    townName != null ? GuiTheme.TEXT : GuiTheme.MUTED);
            int rowY = textY + LINE;
            if (town != null) {
                StringBuilder details = new StringBuilder("lvl ").append((int) town.townLevel());
                if (town.continentName() != null) {
                    details.append(" · ").append(town.continentName());
                }
                details.append(" · ").append(Component.translatable("betterloka.finder.members", town.memberCount()).getString());
                if (town.recruiting()) {
                    details.append(" · ").append(label("betterloka.finder.recruiting"));
                }
                context.text(this.font, this.font.plainSubstrByWidth(details.toString(), inner), textX, rowY,
                        GuiTheme.MUTED);
                rowY += LINE;
            }
            String fightingFor = profile.fightingFor();
            boolean known = fightingFor != null;
            GuiTheme.statRow(context, this.font, textX, rowY, inner,
                    label("betterloka.finder.fighting_for"),
                    known ? fightingFor : (profile.fightsState() == PlayerProfile.FightsState.LOADING ? "..." : "—"),
                    known ? GuiTheme.TEXT : GuiTheme.MUTED);
        });
    }

    /** One row per fight, edged green for a win and red for a loss. */
    private int renderFights(GuiGraphicsExtractor context, int left, int y, int width) {
        int inner = width - CARD_PADDING * 2 - 3;
        for (FightSummary fight : shownFights()) {
            int height = CARD_PADDING * 2 + LINE * 2;
            int edge = fight.victory() ? GuiTheme.GOOD : GuiTheme.BAD;
            MapStyle.card(context, left, y, width, height, CARD_BORDER);
            context.fill(left + 1, y + 2, left + 3, y + height - 2, edge);

            int textX = left + CARD_PADDING + 3;
            int textY = y + CARD_PADDING;
            int right = left + width - CARD_PADDING;

            StringBuilder headline = new StringBuilder(fight.location() != null ? fight.location() : fight.date());
            if (fight.hasDetail()) {
                headline.append("  ·  ").append(fight.ownSideCount()).append(" v ").append(fight.enemySideCount());
            }
            context.text(this.font,
                    this.font.plainSubstrByWidth(headline.toString(), inner - 70), textX, textY, GuiTheme.TEXT);

            String score = fight.hasDetail()
                    ? fight.kills() + "K / " + fight.deaths() + "D / " + fight.assists() + "A"
                    : "...";
            context.text(this.font, score, right - this.font.width(score), textY,
                    fight.hasDetail() && fight.kills() >= fight.deaths() ? GuiTheme.GOOD : GuiTheme.MUTED);

            StringBuilder detail = new StringBuilder(
                    label(fight.victory() ? "betterloka.finder.victory" : "betterloka.finder.defeat"));
            if (fight.ownTown() != null) {
                detail.append(" · ").append(fight.ownTown());
            }
            if (fight.enemyTown() != null) {
                detail.append(" vs ").append(fight.enemyTown());
            }
            context.text(this.font,
                    this.font.plainSubstrByWidth(detail.toString(), inner - this.font.width(fight.date()) - 8),
                    textX, textY + LINE, edge);
            context.text(this.font, fight.date(), right - this.font.width(fight.date()), textY + LINE,
                    GuiTheme.MUTED);

            y += height + TILE_GAP;
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
     * <p>Loka's battle archive carries every battle ever fought with a line per player, so the split
     * is the whole career rather than a sample of it — and the heading says how far the index has
     * read when it is still reading.
     *
     * <p>No wins here: Loka's battle records name no winner, so the career win rate stays above,
     * where it comes from EldritchBot and covers both formats together.
     */
    private int renderFormatSplit(GuiGraphicsExtractor context, int left, int y, int width) {
        BattleIndex index = BetterLokaClient.battleIndex();
        BattleIndex.PlayerRecord record = index.of(profile.uuid());
        BattleIndex.Progress progress = index.progress();
        BattleIndex.Bucket rivi = record.riviTotal();
        BattleIndex.Bucket conquest = record.conquestTotal();

        y = section(context, left, y, width, splitHeader(rivi, conquest, progress));
        if (rivi.isEmpty() && conquest.isEmpty()) {
            return card(context, left, y, width, 1, (textX, textY, inner) ->
                    context.text(this.font,
                            Component.translatable(progress.sweeping()
                                    ? "betterloka.finder.indexing_wait" : "betterloka.finder.no_battles"),
                            textX, textY, GuiTheme.MUTED));
        }
        return renderSplitCard(context, left, y, width, rivi, conquest);
    }

    /** How many battles the split is over, and how much of the archive it has been taken from. */
    private Component splitHeader(BattleIndex.Bucket rivi, BattleIndex.Bucket conquest,
                                  BattleIndex.Progress progress) {
        int fights = rivi.fights() + conquest.fights();
        if (progress.sweeping() && !progress.complete()) {
            return Component.translatable("betterloka.finder.split_indexing", fights, progress.percent());
        }
        return Component.translatable("betterloka.finder.split_all", fights);
    }

    /** The two-column card: Rivi on the left, Conquest on the right, a rule between them. */
    private int renderSplitCard(GuiGraphicsExtractor context, int left, int y, int width,
                                BattleIndex.Bucket rivi, BattleIndex.Bucket conquest) {
        return card(context, left, y, width, 5, (textX, textY, inner) -> {
            int column = (inner - 12) / 2;
            int rightX = textX + column + 12;
            context.fill(textX + column + 5, textY, textX + column + 6, textY + LINE * 5 - 2, SEPARATOR);

            context.text(this.font,
                    Component.translatable("betterloka.finder.rivi", rivi.fights()).copy().withStyle(ChatFormatting.BOLD),
                    textX, textY, GuiTheme.ACCENT);
            context.text(this.font,
                    Component.translatable("betterloka.finder.conquest", conquest.fights()).copy()
                            .withStyle(ChatFormatting.BOLD),
                    rightX, textY, GuiTheme.LIVE);

            splitRow(context, textX, rightX, textY + LINE, column, "betterloka.finder.stat.kills",
                    value(rivi.isEmpty(), rivi.kills()), value(conquest.isEmpty(), conquest.kills()), GuiTheme.GOOD);
            splitRow(context, textX, rightX, textY + LINE * 2, column, "betterloka.finder.stat.deaths",
                    value(rivi.isEmpty(), rivi.deaths()), value(conquest.isEmpty(), conquest.deaths()), GuiTheme.BAD);
            splitRow(context, textX, rightX, textY + LINE * 3, column, "betterloka.finder.stat.fights",
                    value(rivi.isEmpty(), rivi.fights()), value(conquest.isEmpty(), conquest.fights()), GuiTheme.TEXT);

            GuiTheme.statRow(context, this.font, textX, textY + LINE * 4, column,
                    label("betterloka.finder.stat.kd"), rivi.isEmpty() ? "—" : rivi.killDeathText(),
                    rivi.isEmpty() ? GuiTheme.MUTED : GuiTheme.ratioColor(rivi.killDeathRatio()));
            GuiTheme.statRow(context, this.font, rightX, textY + LINE * 4, column,
                    label("betterloka.finder.stat.kd"), conquest.isEmpty() ? "—" : conquest.killDeathText(),
                    conquest.isEmpty() ? GuiTheme.MUTED : GuiTheme.ratioColor(conquest.killDeathRatio()));
        });
    }

    /** A dash rather than a zero where there is nothing to report at all. */
    private static String value(boolean empty, int number) {
        return empty ? "—" : String.valueOf(number);
    }

    private void splitRow(GuiGraphicsExtractor context, int leftX, int rightX, int y, int column, String key,
                          String leftValue, String rightValue, int color) {
        GuiTheme.statRow(context, this.font, leftX, y, column, label(key), leftValue, color);
        GuiTheme.statRow(context, this.font, rightX, y, column, label(key), rightValue, color);
    }

    /**
     * This calendar month: every battle of it from Loka's archive, and the detail only a fight page
     * carries over the fights EldritchBot still lists.
     */
    private int renderMonth(GuiGraphicsExtractor context, int left, int top, int width) {
        int y = top;

        BattleIndex index = BetterLokaClient.battleIndex();
        BattleIndex.PlayerRecord record = index.of(profile.uuid());
        BattleIndex.Progress progress = index.progress();
        String month = BattleIndex.monthKey(LocalDate.now());
        BattleIndex.Bucket rivi = record.rivi(month);
        BattleIndex.Bucket conquest = record.conquest(month);
        BattleIndex.Bucket totals = rivi.plus(conquest);

        y = section(context, left, y, width, Component.translatable("betterloka.finder.month_of", monthName()));

        if (totals.isEmpty()) {
            return card(context, left, y, width, 1, (textX, textY, inner) ->
                    context.text(this.font,
                            Component.translatable(progress.sweeping() && !progress.complete()
                                    ? "betterloka.finder.indexing_wait" : "betterloka.finder.no_month_fights"),
                            textX, textY, GuiTheme.MUTED));
        }

        // Every battle this month, from the archive rather than from the handful EldritchBot lists.
        y = tiles(context, left, y, width, 4, List.of(
                new Tile(number(totals.fights()), label("betterloka.finder.stat.fights"), GuiTheme.TEXT),
                new Tile(totals.killDeathText(), label("betterloka.finder.stat.kd"),
                        GuiTheme.ratioColor(totals.killDeathRatio())),
                new Tile(number(totals.kills()), label("betterloka.finder.stat.kills"), GuiTheme.GOOD),
                new Tile(number(totals.deaths()), label("betterloka.finder.stat.deaths"), GuiTheme.BAD)));
        y = renderSplitCard(context, left, y, width, rivi, conquest);

        // What a fight page carries and a battle record does not — the consumables and the wins.
        // Only the fights EldritchBot still lists have it, so it is a separate section that says so.
        List<FightSummary> detailed = FightBreakdown.thisMonth(profile.recentFights(), LocalDate.now());
        FightBreakdown sample = FightBreakdown.of(detailed);
        if (sample.isEmpty()) {
            return y;
        }
        y += SECTION_GAP - TILE_GAP;
        y = section(context, left, y, width,
                Component.translatable("betterloka.finder.month_detail", sample.fights()));
        return tiles(context, left, y, width, 3, List.of(
                new Tile(number(sample.wins()), label("betterloka.finder.stat.wins"), GuiTheme.GOOD),
                new Tile(number(sample.losses()), label("betterloka.finder.stat.losses"), GuiTheme.BAD),
                new Tile(number(sample.assists()), label("betterloka.finder.stat.assists"), GuiTheme.ACCENT),
                new Tile(number(sample.golems()), label("betterloka.finder.stat.golems"), 0xFFC9B48A),
                new Tile(number(sample.potions()), label("betterloka.finder.stat.potions"), 0xFFE07BD8),
                new Tile(number(sample.pearls()), label("betterloka.finder.stat.pearls"), 0xFF4FD1B5)));
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
    private int renderRankedCards(GuiGraphicsExtractor context, int left, int y, int width) {
        boolean any = false;
        for (ArenaService.Standing standing : arenaCurrent) {
            ArenaEntry current = standing.current();
            if (current == null) {
                continue;
            }
            any = true;
            ArenaService.Best best = bestFor(standing.ladder());
            y = card(context, left, y, width, 5, (textX, textY, inner) -> {
                context.text(this.font,
                        Component.translatable(standing.ladder().translationKey()).copy().withStyle(ChatFormatting.BOLD),
                        textX, textY, GuiTheme.ACCENT);
                String position = "#" + current.position();
                context.text(this.font, position, textX + inner - this.font.width(position), textY, GuiTheme.LIVE);

                twoColumns(context, textX, textY + LINE, inner,
                        label("betterloka.finder.duels"), String.valueOf(current.duels()), GuiTheme.TEXT,
                        label("betterloka.finder.stat.winrate"), current.winRatioText(), GuiTheme.TEXT);
                twoColumns(context, textX, textY + LINE * 2, inner,
                        label("betterloka.finder.stat.wins"), String.valueOf(current.wins()), GuiTheme.GOOD,
                        label("betterloka.finder.stat.losses"), String.valueOf(current.losses()), GuiTheme.BAD);
                // The rank goes in its own row: it is coloured by tier, which a shared column would lose.
                GuiTheme.statRow(context, this.font, textX, textY + LINE * 3, inner,
                        label("betterloka.finder.rank"),
                        current.rank() == null ? "—" : current.rank().label(),
                        current.rank() == null ? GuiTheme.MUTED : current.rank().color());
                context.fill(textX, textY + LINE * 4 - 2, textX + inner, textY + LINE * 4 - 1, SEPARATOR);
                if (best == null) {
                    GuiTheme.statRow(context, this.font, textX, textY + LINE * 4, inner,
                            label("betterloka.finder.best_rank"),
                            label(arenaHistoryLoading ? "betterloka.finder.loading" : "betterloka.finder.unranked"),
                            GuiTheme.MUTED);
                } else {
                    GuiTheme.statRow(context, this.font, textX, textY + LINE * 4, inner,
                            Component.translatable("betterloka.finder.best_rank_season", best.season()).getString(),
                            best.entry().rank().label(), best.entry().rank().color());
                }
            });
        }

        if (!any) {
            return card(context, left, y, width, 1, (textX, textY, inner) ->
                    GuiTheme.statRow(context, this.font, textX, textY, inner,
                            label("betterloka.finder.ranked"),
                            label(arenaLoading ? "betterloka.finder.loading" : "betterloka.finder.unranked"),
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

    /** Draws a card of {@code rows} rows and returns the y just past it and one gap. */
    private int card(GuiGraphicsExtractor context, int left, int y, int width, int rows, CardBody body) {
        int height = CARD_PADDING * 2 + LINE * rows - 2;
        MapStyle.card(context, left, y, width, height, CARD_BORDER);
        body.draw(left + CARD_PADDING, y + CARD_PADDING, width - CARD_PADDING * 2);
        return y + height + TILE_GAP;
    }

    @FunctionalInterface
    private interface CardBody {
        void draw(int x, int y, int inner);
    }

    /** Two label/value pairs side by side, each right-aligned in its half. */
    private void twoColumns(GuiGraphicsExtractor context, int x, int y, int inner,
                            String leftLabel, String leftValue, int leftColor,
                            String rightLabel, String rightValue, int rightColor) {
        int columnWidth = (inner - 12) / 2;
        GuiTheme.statRow(context, this.font, x, y, columnWidth, leftLabel, leftValue, leftColor);
        GuiTheme.statRow(context, this.font, x + columnWidth + 12, y, columnWidth,
                rightLabel, rightValue, rightColor);
    }

    private static String label(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    // --- for the dev harness ---

    /** Switches tab, as its button would. */
    public void devTab(boolean month) {
        selectTab(month ? Tab.MONTH : Tab.PROFILE);
    }
}
