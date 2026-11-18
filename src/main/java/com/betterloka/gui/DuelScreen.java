package com.betterloka.gui;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.model.ArenaRank;
import com.betterloka.duel.DuelLog;
import com.betterloka.duel.DuelStats;
import com.betterloka.duel.Duelists;
import com.betterloka.stats.ArenaService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The two duel screens' common ground: the player on the left, the opponent on the right, each on a
 * stand with their skin turning, and their numbers under them; the middle is the screen's own.
 *
 * <p>Everything is laid out on a fixed {@value #W}×{@value #H} canvas, shrunk to fit when the window
 * is smaller — at the usual GUI scales a 1080p window is only about 480×270. Each panel's numbers
 * scroll under its header with the mouse wheel or the bar beside them. The skins are drawn by the
 * game outside the canvas transform, so their boxes are worked out in screen coordinates by hand.
 */
public abstract class DuelScreen extends Screen {
    protected static final int W = 600;
    protected static final int H = 336;
    protected static final int PANEL_W = 234;
    protected static final int PANEL_TOP = 24;
    protected static final int PANEL_H = H - PANEL_TOP - 6;
    protected static final int LEFT_X = 6;
    protected static final int RIGHT_X = W - 6 - PANEL_W;
    protected static final int PAD = 8;
    protected static final int LINE = 10;
    protected static final int TILE_H = 24;
    private static final int SKIN_W = 66;
    private static final int SKIN_H = 86;
    private static final float SPIN_DEGREES_PER_SECOND = 40f;
    /** How far one notch of the mouse wheel scrolls a panel. */
    private static final float WHEEL_STEP = 26f;
    private static final int BAR_W = 3;
    /** The kit's heal pots, drawn one pip each. */
    private static final int POT_RED = 0xFFF8423F;

    /** One side of the screen. */
    protected static final class Corner {
        final boolean you;
        String name;
        UUID uuid;
        int color;
        /** Above the name: "you" and "opponent", or who won and who lost. */
        Component role;
        CompletableFuture<Duelists.Duelist> record;
        Supplier<PlayerSkin> skin;
        /** The body's scroll: where it is, where the wheel sent it, and how much there is to see. */
        float scroll;
        float target;
        int content;
        int view;
        int viewTop;
        /** What BetterLoka players' mods have seen of them, and for which account it was asked. */
        CompletableFuture<com.betterloka.assistant.AssistantClient.SharedStats> shared;
        UUID sharedFor;

        Corner(boolean you, String name, UUID uuid, int color) {
            this.you = you;
            this.name = name;
            this.uuid = uuid;
            this.color = color;
            this.role = Component.translatable(you ? "betterloka.duelscreen.you" : "betterloka.duelscreen.opponent");
        }

        int maxScroll() {
            return Math.max(0, content - view);
        }

        boolean known() {
            return name != null;
        }

        /** The record if it is in, else {@code null}. */
        Duelists.Duelist duelist() {
            return record != null && record.isDone() && !record.isCompletedExceptionally() ? record.join() : null;
        }
    }

    protected final Corner you;
    protected final Corner them;

    private Model.Simple wideModel;
    private Model.Simple slimModel;
    private float spin = 25f;
    private long lastFrame;

    /** The canvas's place on the screen: its corner and how much it is shrunk. */
    private float scale = 1f;
    private float originX;
    private float originY;
    /** The pointer on the canvas, and what it is over this frame. */
    protected int mouseX;
    protected int mouseY;
    private List<Component> tooltip;
    /** While a panel's body is drawn: the part of it on screen, and how far it is scrolled. */
    private int clipTop = Integer.MIN_VALUE;
    private int clipBottom = Integer.MAX_VALUE;
    private int shiftY;
    /** The panel whose scroll bar is being dragged. */
    private Corner dragging;

    protected DuelScreen(Component title, Corner you, Corner them) {
        super(title);
        this.you = you;
        this.them = them;
    }

    @Override
    protected void init() {
        if (wideModel == null) {
            EntityModelSet models = this.minecraft.getEntityModels();
            wideModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
            slimModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
        }
        scale = Math.min(1f, Math.min(this.width / (float) W, this.height / (float) H));
        originX = (this.width - W * scale) / 2f;
        originY = (this.height - H * scale) / 2f;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int realMouseX, int realMouseY, float delta) {
        super.extractRenderState(context, realMouseX, realMouseY, delta);
        refresh();
        long now = System.currentTimeMillis();
        float seconds = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        spin = (spin + seconds * SPIN_DEGREES_PER_SECOND) % 360f;
        for (Corner corner : List.of(you, them)) {
            corner.target = Math.max(0, Math.min(corner.maxScroll(), corner.target));
            corner.scroll += (corner.target - corner.scroll) * Math.min(1f, seconds * 16f);
            if (Math.abs(corner.target - corner.scroll) < 0.5f) {
                corner.scroll = corner.target;
            }
        }
        mouseX = (int) Math.floor((realMouseX - originX) / scale);
        mouseY = (int) Math.floor((realMouseY - originY) / scale);
        tooltip = null;

        context.pose().pushMatrix();
        context.pose().translate(originX, originY);
        context.pose().scale(scale, scale);
        context.centeredText(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), W / 2, 8, titleColor());
        drawPanel(context, you, LEFT_X);
        drawPanel(context, them, RIGHT_X);
        drawCenter(context, RIGHT_X - (LEFT_X + PANEL_W), LEFT_X + PANEL_W);
        context.pose().popMatrix();

        if (tooltip != null) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (Component line : tooltip) {
                lines.addAll(this.font.split(line, 220));
            }
            context.setTooltipForNextFrame(lines, realMouseX, realMouseY);
        }
    }

    /** Before each frame: whatever the screen shows that can change while it is open. */
    protected void refresh() {
    }

    /** The duel statistics asked for lately, by account: a rematch or a screen opened again asks nothing. */
    private static final java.util.Map<UUID, CompletableFuture<com.betterloka.assistant.AssistantClient.SharedStats>> SHARED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> SHARED_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SHARED_KEEP_MILLIS = 2 * 60_000L;

    /** Forgets what was asked about {@code player}: a duel with them was just reported. */
    public static void forgetShared(UUID player) {
        if (player != null) {
            SHARED.remove(player);
            SHARED_AT.remove(player);
        }
    }

    /** Asks for a side's duel statistics once their account is known. */
    private static void loadShared(Corner corner) {
        if (corner.uuid == null || corner.uuid.equals(corner.sharedFor)) {
            return;
        }
        corner.sharedFor = corner.uuid;
        long now = System.currentTimeMillis();
        Long at = SHARED_AT.get(corner.uuid);
        CompletableFuture<com.betterloka.assistant.AssistantClient.SharedStats> known = SHARED.get(corner.uuid);
        if (known == null || at == null || now - at > SHARED_KEEP_MILLIS || known.isCompletedExceptionally()) {
            known = com.betterloka.BetterLokaClient.assistant().duelStats(corner.uuid);
            SHARED.put(corner.uuid, known);
            SHARED_AT.put(corner.uuid, now);
        }
        corner.shared = known;
    }

    /**
     * What BetterLoka players' mods have seen of this player in duels — their ping, accuracy, crits
     * and normal hits — from the BetterLoka server's duel statistics. @return the next y
     */
    protected int sharedStats(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        loadShared(corner);
        y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.shared"), corner.color);
        Component tip = Component.translatable("betterloka.duelscreen.shared_tip");
        var future = corner.shared;
        if (future == null || !future.isDone()) {
            context.text(this.font, corner.known() && corner.uuid == null ? "—" : "…", x, y, GuiTheme.MUTED);
            return y + LINE + 2;
        }
        if (future.isCompletedExceptionally()) {
            for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.duelscreen.shared_off"), width)) {
                context.text(this.font, line, x, y, 0xFF6E6E7A);
                y += LINE;
            }
            return y + 2;
        }
        var stats = future.join();
        if (stats.observations() == 0) {
            for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.duelscreen.shared_none"), width)) {
                context.text(this.font, line, x, y, 0xFF6E6E7A);
                y += LINE;
            }
            return y + 2;
        }
        y = subheading(context, x, y, width, Component.translatable("betterloka.duelscreen.shared_from",
                stats.observations(), stats.reporters()));
        int crit = stats.critShare();
        y = tiles(context, x, y, width, List.of(
                new Tile(stats.ping() < 0 ? "—" : stats.ping() + " ms", Component.translatable("betterloka.duelscreen.ping"),
                        stats.ping() < 0 ? GuiTheme.MUTED : PingColors.color(stats.ping()) | 0xFF000000, tip),
                new Tile(stats.accuracy() < 0 ? "—" : stats.accuracy() + "%", Component.translatable("betterloka.duelscreen.accuracy"),
                        stats.accuracy() < 0 ? GuiTheme.MUTED : stats.accuracy() >= 60 ? GuiTheme.GOOD
                                : stats.accuracy() >= 40 ? GuiTheme.LIVE : GuiTheme.BAD, tip),
                new Tile(crit < 0 ? "—" : crit + "%", Component.translatable("betterloka.duelscreen.crits"), GuiTheme.LIVE, tip),
                new Tile(crit < 0 ? "—" : (100 - crit) + "%", Component.translatable("betterloka.duelscreen.normal"),
                        GuiTheme.TEXT, tip)));
        return y;
    }

    protected int titleColor() {
        return GuiTheme.LIVE;
    }

    /** What sits between the two panels. */
    protected abstract void drawCenter(GuiGraphicsExtractor context, int width, int left);

    /** The panel's numbers below its header. @return where they end */
    protected abstract int drawBody(GuiGraphicsExtractor context, Corner corner, int x, int y, int width);

    // ---- a panel ----

    private void drawPanel(GuiGraphicsExtractor context, Corner corner, int x) {
        MapStyle.card(context, x, PANEL_TOP, PANEL_W, PANEL_H, 0xFF2C2C38);
        context.fill(x + 2, PANEL_TOP, x + PANEL_W - 2, PANEL_TOP + 2, corner.color);
        int bodyY = drawHeader(context, corner, x);

        // The body scrolls under the header, inside the card.
        int top = bodyY - 4;
        int bottom = PANEL_TOP + PANEL_H - 3;
        corner.viewTop = top;
        corner.view = bottom - top;
        int shift = Math.round(corner.scroll);
        context.fill(x + 4, top, x + PANEL_W - 4, top + 1, 0x30FFFFFF);
        context.enableScissor(x + 1, top + 1, x + PANEL_W - 1, bottom);
        context.pose().pushMatrix();
        context.pose().translate(0, -shift);
        clipTop = top + 1;
        clipBottom = bottom;
        shiftY = shift;
        int end = drawBody(context, corner, x + PAD, bodyY, PANEL_W - PAD * 2 - BAR_W - 2);
        clipTop = Integer.MIN_VALUE;
        clipBottom = Integer.MAX_VALUE;
        shiftY = 0;
        context.pose().popMatrix();
        context.disableScissor();
        corner.content = end + 4 - top;

        if (corner.maxScroll() > 0) {
            // Fades where there is more, and the bar.
            if (shift > 0) {
                context.fillGradient(x + 2, top + 1, x + PANEL_W - 2, top + 11, 0xE60D0D12, 0x000D0D12);
            }
            if (shift < corner.maxScroll()) {
                context.fillGradient(x + 2, bottom - 14, x + PANEL_W - 2, bottom, 0x000D0D12, 0xF00D0D12);
            }
            int barX = x + PANEL_W - PAD - BAR_W + 3;
            context.fill(barX, top + 3, barX + BAR_W, bottom - 3, 0x30FFFFFF);
            int[] thumb = thumb(corner);
            boolean hot = dragging == corner || over(barX - 3, top, BAR_W + 6, corner.view);
            context.fill(barX, thumb[0], barX + BAR_W, thumb[1], hot ? 0xFFE8E8EC : (corner.color & 0xFFFFFF) | 0xC0000000);
        }
    }

    /** The scroll bar's thumb: its top and bottom on the canvas. */
    private static int[] thumb(Corner corner) {
        int track = corner.view - 6;
        int length = Math.max(14, Math.round(track * corner.view / (float) Math.max(corner.content, 1)));
        int travel = track - length;
        int at = corner.viewTop + 3 + (corner.maxScroll() == 0 ? 0 : Math.round(travel * corner.scroll / corner.maxScroll()));
        return new int[]{at, at + length};
    }

    /** The panel under a point on the canvas, or {@code null}. */
    private Corner panelAt(double x, double y) {
        if (y < PANEL_TOP || y >= PANEL_TOP + PANEL_H) {
            return null;
        }
        if (x >= LEFT_X && x < LEFT_X + PANEL_W) {
            return you;
        }
        return x >= RIGHT_X && x < RIGHT_X + PANEL_W ? them : null;
    }

    private static int panelX(Corner corner) {
        return corner.you ? LEFT_X : RIGHT_X;
    }

    @Override
    public boolean mouseScrolled(double realX, double realY, double scrollX, double scrollY) {
        Corner corner = panelAt((realX - originX) / scale, (realY - originY) / scale);
        if (corner != null && corner.maxScroll() > 0 && scrollY != 0) {
            corner.target = Math.max(0, Math.min(corner.maxScroll(), corner.target - (float) scrollY * WHEEL_STEP));
            return true;
        }
        return super.mouseScrolled(realX, realY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        int[] at = canvas(click);
        Corner corner = panelAt(at[0], at[1]);
        if (click.button() == 0 && corner != null && corner.maxScroll() > 0) {
            int barX = panelX(corner) + PANEL_W - PAD - BAR_W + 3;
            if (at[0] >= barX - 3 && at[0] < barX + BAR_W + 3 && at[1] >= corner.viewTop && at[1] < corner.viewTop + corner.view) {
                dragging = corner;
                dragTo(corner, at[1]);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent drag, double dx, double dy) {
        if (dragging != null) {
            dragTo(dragging, canvas(drag)[1]);
            return true;
        }
        return super.mouseDragged(drag, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent release) {
        if (dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(release);
    }

    /** Puts the middle of the bar's thumb where the pointer is. */
    private static void dragTo(Corner corner, int y) {
        int[] thumb = thumb(corner);
        int length = thumb[1] - thumb[0];
        int travel = corner.view - 6 - length;
        float share = travel <= 0 ? 0 : (y - corner.viewTop - 3 - length / 2f) / travel;
        corner.target = Math.max(0, Math.min(1, share)) * corner.maxScroll();
        corner.scroll = corner.target;
    }

    /**
     * The stand and who is on it: the skin on the outer side of the screen, the name, ping and ranks
     * beside it on the inner side.
     *
     * @return where the panel's body starts
     */
    private int drawHeader(GuiGraphicsExtractor context, Corner corner, int x) {
        boolean skinLeft = corner.you;
        int skinX = skinLeft ? x + PAD : x + PANEL_W - PAD - SKIN_W;
        int skinY = PANEL_TOP + 8;
        int infoX = skinLeft ? skinX + SKIN_W + 8 : x + PAD;
        int infoW = PANEL_W - PAD * 2 - SKIN_W - 8;

        // A glow and a plinth, as the Player Finder's stand.
        int glowX = skinX + SKIN_W / 2;
        int glowY = skinY + SKIN_H / 2;
        for (int ring = 0; ring < 4; ring++) {
            int radius = SKIN_H / 2 - ring * 8;
            disc(context, glowX, glowY, radius, (0x10 + ring * 0x07) << 24 | (corner.color & 0xFFFFFF));
        }
        int plinthY = skinY + SKIN_H - 3;
        context.fill(glowX - 26, plinthY, glowX + 26, plinthY + 4, 0xFF4A4A5A);
        context.fill(glowX - 22, plinthY + 4, glowX + 22, plinthY + 8, 0xFF33333F);

        if (corner.known()) {
            Duelists.Duelist loaded = corner.duelist();
            if (corner.uuid == null && loaded != null && loaded.uuid() != null) {
                // Known by name until their profile came back with who they are.
                corner.uuid = loaded.uuid();
                corner.skin = null;
            }
            if (corner.skin == null) {
                corner.skin = PlayerSkins.of(corner.uuid);
            }
            PlayerSkin look = corner.skin.get();
            if (look != null) {
                Model.Simple model = look.model() == PlayerModelType.SLIM ? slimModel : wideModel;
                // The game draws skins outside the canvas transform: the box in screen coordinates.
                int x0 = Math.round(originX + skinX * scale);
                int y0 = Math.round(originY + skinY * scale);
                int x1 = Math.round(originX + (skinX + SKIN_W) * scale);
                int y1 = Math.round(originY + (skinY + SKIN_H - 4) * scale);
                float modelScale = 0.97f * (y1 - y0) / 2.125f;
                float facing = corner.you ? spin : -spin;
                context.skin(model, look.body().texturePath(), modelScale, -5f, facing, -1.0625f, x0, y0, x1, y1);
            }
        } else {
            // Nobody yet: a question mark where they will stand.
            context.pose().pushMatrix();
            context.pose().translate(glowX - this.font.width("?") * 2.5f, glowY - 22);
            context.pose().scale(5f, 5f);
            context.text(this.font, "?", 0, 0, GuiTheme.MUTED);
            context.pose().popMatrix();
        }

        int y = PANEL_TOP + 9;
        Component role = corner.role.copy().withStyle(ChatFormatting.BOLD);
        int roleW = Math.min(infoW, this.font.width(role) + 10);
        GuiTheme.chip(context, infoX, y, roleW, 13, corner.color);
        context.text(this.font, role, infoX + 5, y + 3, corner.color);
        y += 16;
        String name = corner.known() ? (corner.duelist() != null ? corner.duelist().name() : corner.name) : "???";
        context.text(this.font, Component.literal(this.font.plainSubstrByWidth(name, infoW)).withStyle(ChatFormatting.BOLD),
                infoX, y, corner.color);
        y += LINE + 4;
        y = drawPing(context, corner, infoX, y, infoW);
        y = drawRankChips(context, corner, infoX, y + 2, infoW);
        return Math.max(skinY + SKIN_H + 10, y + 4);
    }

    /**
     * The player's usual ping over the duels this mod has seen. Not their ping now: the server's
     * player list only has it while they are on the same server, which before a duel they almost
     * never are.
     */
    protected int drawPing(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        if (!corner.known()) {
            return y;
        }
        int[] usual = usualPing(corner);
        Component average = usual == null
                ? Component.translatable("betterloka.duelscreen.ping_usual_none")
                : Component.translatable("betterloka.duelscreen.ping_usual",
                Component.literal(usual[0] + " ms").withColor(PingColors.color(usual[0])), usual[1]);
        String plain = average.getString();
        context.text(this.font, this.font.width(plain) <= width ? average
                : Component.literal(this.font.plainSubstrByWidth(plain, width)), x, y, GuiTheme.MUTED);
        hover(x, y - 1, width, LINE, Component.translatable("betterloka.duelscreen.ping_tip"));
        return y + LINE;
    }

    /** Where they stand now on each ladder they have played, as chips in the tier's colour. */
    private int drawRankChips(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        Duelists.Duelist record = corner.duelist();
        if (!corner.known()) {
            return y;
        }
        if (record == null) {
            context.text(this.font, Component.translatable("betterloka.duelscreen.loading"), x, y + 3, GuiTheme.MUTED);
            return y + 14;
        }
        boolean any = false;
        for (ArenaApi.Ladder ladder : ArenaApi.Ladder.values()) {
            ArenaRank rank = record.rankNow(ladder);
            if (rank == null) {
                continue;
            }
            any = true;
            String text = ladder.shortName() + " · " + rank;
            int streak = record.streak(ladder);
            int chipW = Math.min(width, this.font.width(text) + 10);
            GuiTheme.chip(context, x, y, chipW, 13, rank.color());
            context.text(this.font, this.font.plainSubstrByWidth(text, chipW - 8), x + 5, y + 3, rank.color());
            if (streak >= 3) {
                hover(x, y, chipW, 13, Component.translatable("betterloka.duelscreen.streak", streak));
            }
            y += 15;
        }
        if (!any) {
            context.text(this.font, Component.translatable("betterloka.duelscreen.unranked"), x, y + 3, GuiTheme.MUTED);
            y += 14;
        }
        return y;
    }

    // ---- pieces for the bodies ----

    /** A section's heading: a short bar in the side's colour and the title. @return the next y */
    protected int section(GuiGraphicsExtractor context, int x, int y, int width, Component title, int color) {
        context.fill(x, y, x + 2, y + 8, color);
        context.text(this.font, title.copy().withStyle(ChatFormatting.BOLD), x + 5, y, GuiTheme.TEXT);
        return y + LINE + 2;
    }

    /** One number in a tile. */
    protected record Tile(String value, Component label, int color, Component tip) {
        Tile(String value, Component label, int color) {
            this(value, label, color, null);
        }
    }

    /** A row of tiles, sharing the width. @return the next y */
    protected int tiles(GuiGraphicsExtractor context, int x, int y, int width, List<Tile> row) {
        int gap = 4;
        int tileW = (width - gap * (row.size() - 1)) / row.size();
        for (int i = 0; i < row.size(); i++) {
            Tile tile = row.get(i);
            int tx = x + i * (tileW + gap);
            MapStyle.card(context, tx, y, tileW, TILE_H, tile.color() & 0x80FFFFFF | 0x80000000);
            Component value = Component.literal(tile.value()).withStyle(ChatFormatting.BOLD);
            int valueW = this.font.width(value);
            if (valueW <= tileW - 6) {
                context.centeredText(this.font, value, tx + tileW / 2, y + 4, tile.color());
            } else {
                // A long number, e.g. a career's wins and losses: smaller rather than cut.
                float shrink = Math.max(0.6f, (tileW - 6) / (float) valueW);
                context.pose().pushMatrix();
                context.pose().translate(tx + tileW / 2f - valueW * shrink / 2f, y + 4 + (1 - shrink) * 4);
                context.pose().scale(shrink, shrink);
                context.text(this.font, value, 0, 0, tile.color());
                context.pose().popMatrix();
            }
            String label = tile.label().getString();
            int labelW = this.font.width(label);
            if (labelW <= tileW - 4) {
                context.centeredText(this.font, label, tx + tileW / 2, y + 15, GuiTheme.MUTED);
            } else {
                float shrink = Math.max(0.7f, (tileW - 4) / (float) labelW);
                String fitted = this.font.plainSubstrByWidth(label, (int) ((tileW - 4) / shrink));
                context.pose().pushMatrix();
                context.pose().translate(tx + tileW / 2f - this.font.width(fitted) * shrink / 2f, y + 15 + (1 - shrink) * 4);
                context.pose().scale(shrink, shrink);
                context.text(this.font, fitted, 0, 0, GuiTheme.MUTED);
                context.pose().popMatrix();
            }
            if (tile.tip() != null) {
                hover(tx, y, tileW, TILE_H, tile.tip());
            }
        }
        return y + TILE_H + 4;
    }

    /** A small heading inside a section, e.g. "Season 24" over its tiles. @return the next y */
    protected int subheading(GuiGraphicsExtractor context, int x, int y, int width, Component text) {
        String shown = this.font.plainSubstrByWidth(text.getString().toUpperCase(Locale.ROOT), width);
        context.text(this.font, shown, x, y, 0xFFB4B4C2);
        int after = x + this.font.width(shown) + 4;
        if (after < x + width) {
            context.fill(after, y + 4, x + width, y + 5, 0x24FFFFFF);
        }
        return y + LINE + 1;
    }

    /**
     * Games, wins, losses and the win ratio as four tiles: the wins green, the losses red, so the
     * record reads at a glance rather than as one run of digits.
     */
    protected int recordTiles(GuiGraphicsExtractor context, int x, int y, int width, ArenaService.Totals totals, Component tip) {
        Component duels = Component.translatable("betterloka.duelscreen.duels");
        Component wins = Component.translatable("betterloka.duelscreen.wins");
        Component losses = Component.translatable("betterloka.duelscreen.losses");
        Component rate = Component.translatable("betterloka.duelscreen.winrate");
        if (totals == null) {
            return tiles(context, x, y, width, List.of(new Tile("…", duels, GuiTheme.MUTED), new Tile("…", wins, GuiTheme.MUTED),
                    new Tile("…", losses, GuiTheme.MUTED), new Tile("…", rate, GuiTheme.MUTED)));
        }
        int games = totals.games();
        return tiles(context, x, y, width, List.of(
                new Tile(number(games), duels, GuiTheme.TEXT, tip),
                new Tile(number(totals.wins()), wins, games == 0 ? GuiTheme.MUTED : GuiTheme.GOOD, tip),
                new Tile(number(totals.losses()), losses, games == 0 ? GuiTheme.MUTED : GuiTheme.BAD, tip),
                new Tile(percent(totals.wins(), games), rate, rateColor(totals.wins(), games), tip)));
    }

    /** This season and the career, each as a row of record tiles. @return the next y */
    protected int ranked(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        Duelists.Duelist record = corner.duelist();
        int seasonNumber = record == null ? -1 : record.seasonNumber();
        y = subheading(context, x, y, width, seasonNumber > 0
                ? Component.translatable("betterloka.duelscreen.this_season_n", seasonNumber)
                : Component.translatable("betterloka.duelscreen.this_season"));
        y = recordTiles(context, x, y, width, record == null ? null : record.season(),
                Component.translatable("betterloka.duelscreen.season_tip"));
        ArenaService.Totals career = record == null ? null : record.career();
        y = subheading(context, x, y, width, career == null || career.seasons() <= 0
                ? Component.translatable("betterloka.duelscreen.career")
                : Component.translatable("betterloka.duelscreen.career_n", career.seasons()));
        return recordTiles(context, x, y, width, career, career == null ? null
                : Component.translatable("betterloka.duelscreen.career_tip", career.seasons()));
    }

    /**
     * The best rank ever and last season's on each ladder, as tiles in the rank's colour: the rank big,
     * the ladder and the season under it. @return the next y
     */
    protected int rankTiles(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        Duelists.Duelist record = corner.duelist();
        int last = record == null ? -1 : record.lastSeasonNumber();
        for (int row = 0; row < 2; row++) {
            y = subheading(context, x, y, width, row == 0
                    ? Component.translatable("betterloka.duelscreen.best_ever")
                    : last > 0 ? Component.translatable("betterloka.duelscreen.last_season_n", last)
                    : Component.translatable("betterloka.duelscreen.last_season"));
            List<Tile> tiles = new ArrayList<>();
            for (ArenaApi.Ladder ladder : ArenaApi.Ladder.values()) {
                ArenaService.Career career = record == null ? null : record.career(ladder);
                ArenaService.Best best = career == null ? null : row == 0 ? career.highest() : career.lastSeason();
                if (best == null || best.entry().rank() == null) {
                    tiles.add(new Tile(record == null ? "…" : "—", Component.literal(ladder.shortName()), GuiTheme.MUTED,
                            record == null ? null : Component.translatable("betterloka.duelscreen.rank_none")));
                    continue;
                }
                ArenaRank rank = best.entry().rank();
                String when = row == 0
                        ? (best.season() == ArenaService.THIS_SEASON
                        ? Component.translatable("betterloka.duelscreen.now").getString() : "S" + best.season())
                        : best.entry().wins() + "–" + best.entry().losses();
                Component tip = row == 0
                        ? Component.literal(ladder.shortName() + ": " + rank + " (" + when + ")")
                        : Component.translatable("betterloka.duelscreen.finished", best.entry().wins(), best.entry().losses());
                tiles.add(new Tile(rank.toString(), Component.literal(ladder.shortName() + " · " + when), rank.color(), tip));
            }
            y = tiles(context, x, y, width, tiles);
        }
        return y;
    }

    /**
     * The splash Instant Health II a side has left of the kit's {@value DuelStats#KIT_POTS}: how many
     * are left and how many were thrown, and a pip for each pot of the kit. @return the next y
     */
    protected int pots(GuiGraphicsExtractor context, DuelLog.Side side, int x, int y, int width) {
        int left = side.potsLeft();
        Component leftLabel = Component.translatable("betterloka.duelscreen.pots_left");
        Component thrownLabel = Component.translatable("betterloka.duelscreen.pots_thrown");
        if (left < 0) {
            Component tip = Component.translatable("betterloka.duelscreen.pots_unknown");
            return tiles(context, x, y, width, List.of(new Tile("—", leftLabel, GuiTheme.MUTED, tip),
                    new Tile("—", thrownLabel, GuiTheme.MUTED, tip)));
        }
        int kit = DuelStats.KIT_POTS;
        int color = left >= kit / 2 ? GuiTheme.GOOD : left >= kit / 4 ? GuiTheme.LIVE : GuiTheme.BAD;
        Component tip = Component.translatable("betterloka.duelscreen.pots_tip", side.pots(), left, kit);
        y = tiles(context, x, y, width, List.of(new Tile(left + "/" + kit, leftLabel, color, tip),
                new Tile(String.valueOf(side.pots()), thrownLabel, GuiTheme.TEXT, tip)));
        int gap = 1;
        int pip = Math.max(2, (width - gap * (kit - 1)) / kit);
        int barW = pip * kit + gap * (kit - 1);
        int px = x + (width - barW) / 2;
        for (int i = 0; i < kit; i++) {
            int fill = i < left ? POT_RED : 0xFF33333D;
            context.fill(px, y, px + pip, y + 6, fill);
            if (i < left) {
                context.fill(px, y, px + pip, y + 1, 0x60FFFFFF);
            }
            px += pip + gap;
        }
        hover(x, y, width, 6, tip);
        return y + 10;
    }

    /** Names the account went by before, as chips that wrap. @return the next y */
    protected int previousNames(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        Duelists.Duelist record = corner.duelist();
        if (record == null) {
            context.text(this.font, corner.known() ? "…" : "—", x, y, GuiTheme.MUTED);
            return y + LINE;
        }
        List<String> names = record.previousNames() == null ? List.of() : record.previousNames();
        if (names.isEmpty()) {
            for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.duelscreen.names_none"), width)) {
                context.text(this.font, line, x, y, 0xFF6E6E7A);
                y += LINE;
            }
            return y;
        }
        int cx = x;
        for (String name : names) {
            int chipW = Math.min(width, this.font.width(name) + 10);
            if (cx > x && cx + chipW > x + width) {
                cx = x;
                y += 15;
            }
            GuiTheme.chip(context, cx, y, chipW, 13, 0xFF6E6E82);
            context.text(this.font, this.font.plainSubstrByWidth(name, chipW - 8), cx + 5, y + 3, GuiTheme.TEXT);
            cx += chipW + 3;
        }
        return y + 15;
    }

    /** Matches from the duel log: result, opponent, when. @return the next y */
    protected int matchList(GuiGraphicsExtractor context, List<DuelLog.Entry> matches, int x, int y, int width,
                            int rows, Component empty) {
        if (matches.isEmpty()) {
            for (FormattedCharSequence line : this.font.split(empty, width)) {
                context.text(this.font, line, x, y, 0xFF6E6E7A);
                y += LINE;
            }
            return y;
        }
        for (int i = 0; i < Math.min(rows, matches.size()); i++) {
            DuelLog.Entry match = matches.get(i);
            DuelStats.Result result = match.outcome();
            String mark = result == DuelStats.Result.WON ? "W" : result == DuelStats.Result.LOST ? "L" : "?";
            int markColor = result == DuelStats.Result.WON ? GuiTheme.GOOD : result == DuelStats.Result.LOST ? GuiTheme.BAD : GuiTheme.MUTED;
            context.fill(x, y - 1, x + 11, y + 9, markColor & 0x40FFFFFF | 0x40000000);
            context.centeredText(this.font, mark, x + 6, y, markColor);
            String when = ago(match.at());
            int whenW = this.font.width(when);
            Component who = Component.translatable("betterloka.duelscreen.vs", match.opponentName() == null ? "?" : match.opponentName());
            context.text(this.font, this.font.plainSubstrByWidth(who.getString(), width - whenW - 22), x + 15, y, GuiTheme.TEXT);
            context.text(this.font, when, x + width - whenW, y, 0xFF6E6E7A);
            int accuracy = match.you().accuracy();
            hover(x, y - 1, width, LINE, Component.translatable("betterloka.duelscreen.match_tip",
                    clock(match.millis()), accuracy < 0 ? "—" : accuracy + "%", match.you().hits(), match.them().hits()));
            y += LINE + 1;
        }
        return y;
    }

    // ---- small things ----

    /** Shows {@code text} as the tooltip while the pointer is over the box (canvas coordinates). */
    protected void hover(int x, int y, int width, int height, Component text) {
        if (text != null && over(x, y, width, height)) {
            tooltip = List.of(text);
        }
    }

    /** Whether the pointer is over the box; inside a panel's body, only over the part on screen. */
    protected boolean over(int x, int y, int width, int height) {
        if (mouseY < clipTop || mouseY >= clipBottom) {
            return false;
        }
        int my = mouseY + shiftY;
        return mouseX >= x && mouseX < x + width && my >= y && my < y + height;
    }

    /** The click on the canvas, or {@code null} off it. */
    protected int[] canvas(MouseButtonEvent click) {
        return new int[]{(int) Math.floor((click.x() - originX) / scale), (int) Math.floor((click.y() - originY) / scale)};
    }

    protected int[] usualPing(Corner corner) {
        var log = com.betterloka.BetterLokaClient.duelLog();
        return log == null || !corner.known() ? null : log.averagePing(corner.uuid, corner.you ? null : corner.name);
    }

    /** A share as a whole percentage, or "—" with no games. */
    protected static String percent(int part, int whole) {
        return whole == 0 ? "—" : Math.round(part * 100f / whole) + "%";
    }

    protected static int rateColor(int wins, int games) {
        if (games == 0) {
            return GuiTheme.MUTED;
        }
        double rate = wins / (double) games;
        return rate >= 0.55 ? GuiTheme.GOOD : rate <= 0.45 ? GuiTheme.BAD : GuiTheme.LIVE;
    }

    protected static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    protected static String clock(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    protected static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) {
            return Component.translatable("betterloka.duelscreen.ago_now").getString();
        }
        if (minutes < 60) {
            return Component.translatable("betterloka.duelscreen.ago_m", minutes).getString();
        }
        if (minutes < 60 * 24) {
            return Component.translatable("betterloka.duelscreen.ago_h", minutes / 60).getString();
        }
        return Component.translatable("betterloka.duelscreen.ago_d", minutes / (60 * 24)).getString();
    }

    /** A filled circle, a row at a time. */
    protected static void disc(GuiGraphicsExtractor context, int centreX, int centreY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            context.fill(centreX - half, centreY + dy, centreX + half, centreY + dy + 1, color);
        }
    }

    /** A big word, centred, {@code size} times the normal font. */
    protected void bigText(GuiGraphicsExtractor context, Component text, int centreX, int y, float size, int color) {
        context.pose().pushMatrix();
        context.pose().translate(centreX - this.font.width(text) * size / 2f, y);
        context.pose().scale(size, size);
        context.text(this.font, text, 0, 0, color);
        context.pose().popMatrix();
    }
}
