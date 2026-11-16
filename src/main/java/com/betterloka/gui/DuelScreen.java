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
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
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
 * is smaller — at the usual GUI scales a 1080p window is only about 480×270 — so nothing has to be
 * left out or scrolled to. The skins are drawn by the game outside that transform, so their boxes are
 * worked out in screen coordinates by hand.
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

    /** One side of the screen. */
    protected static final class Corner {
        final boolean you;
        String name;
        UUID uuid;
        int color;
        CompletableFuture<Duelists.Duelist> record;
        Supplier<PlayerSkin> skin;

        Corner(boolean you, String name, UUID uuid, int color) {
            this.you = you;
            this.name = name;
            this.uuid = uuid;
            this.color = color;
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
        long now = System.currentTimeMillis();
        float seconds = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1000f);
        lastFrame = now;
        spin = (spin + seconds * SPIN_DEGREES_PER_SECOND) % 360f;
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

    protected int titleColor() {
        return GuiTheme.LIVE;
    }

    /** What sits between the two panels. */
    protected abstract void drawCenter(GuiGraphicsExtractor context, int width, int left);

    /** The panel's numbers below its header. */
    protected abstract void drawBody(GuiGraphicsExtractor context, Corner corner, int x, int y, int width);

    // ---- a panel ----

    private void drawPanel(GuiGraphicsExtractor context, Corner corner, int x) {
        MapStyle.card(context, x, PANEL_TOP, PANEL_W, PANEL_H, 0xFF2C2C38);
        context.fill(x + 2, PANEL_TOP, x + PANEL_W - 2, PANEL_TOP + 2, corner.color);
        int bodyY = drawHeader(context, corner, x);
        drawBody(context, corner, x + PAD, bodyY, PANEL_W - PAD * 2);
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

        int y = PANEL_TOP + 10;
        Component role = Component.translatable(corner.you ? "betterloka.duelscreen.you" : "betterloka.duelscreen.opponent");
        context.text(this.font, role, infoX, y, GuiTheme.MUTED);
        y += LINE + 1;
        String name = corner.known() ? (corner.duelist() != null ? corner.duelist().name() : corner.name) : "???";
        context.text(this.font, Component.literal(this.font.plainSubstrByWidth(name, infoW)).withStyle(ChatFormatting.BOLD),
                infoX, y, corner.color);
        y += LINE + 4;
        y = drawPing(context, corner, infoX, y, infoW);
        y = drawRankChips(context, corner, infoX, y + 2, infoW);
        return Math.max(skinY + SKIN_H + 10, y + 4);
    }

    /** The player's ping: on this server now, and their usual one over the duels this mod has seen. */
    protected int drawPing(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        if (!corner.known()) {
            return y;
        }
        int live = livePing(corner);
        int[] usual = usualPing(corner);
        Component now = live >= 0
                ? Component.translatable("betterloka.duelscreen.ping_now", Component.literal(live + " ms").withColor(PingColors.color(live)))
                : Component.translatable("betterloka.duelscreen.ping_now", Component.literal("—").withStyle(ChatFormatting.DARK_GRAY));
        context.text(this.font, now, x, y, GuiTheme.MUTED);
        y += LINE;
        Component average = usual == null
                ? Component.translatable("betterloka.duelscreen.ping_usual_none")
                : Component.translatable("betterloka.duelscreen.ping_usual",
                Component.literal(usual[0] + " ms").withColor(PingColors.color(usual[0])), usual[1]);
        context.text(this.font, average, x, y, GuiTheme.MUTED);
        hover(x, y - LINE, width, LINE * 2, Component.translatable("betterloka.duelscreen.ping_tip"));
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
            context.centeredText(this.font, this.font.plainSubstrByWidth(tile.label().getString(), tileW - 4),
                    tx + tileW / 2, y + 15, GuiTheme.MUTED);
            if (tile.tip() != null) {
                hover(tx, y, tileW, TILE_H, tile.tip());
            }
        }
        return y + TILE_H + 4;
    }

    /** The best rank ever and last season's, per ladder, as a small table. @return the next y */
    protected int rankTable(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        Duelists.Duelist record = corner.duelist();
        int labelW = 64;
        int colW = (width - labelW) / 2;
        ArenaApi.Ladder[] ladders = ArenaApi.Ladder.values();
        for (int i = 0; i < ladders.length; i++) {
            context.text(this.font, ladders[i].shortName(), x + labelW + i * colW, y, GuiTheme.LIVE);
        }
        y += LINE;
        int last = record == null ? -1 : record.lastSeasonNumber();
        Component[] labels = {
                Component.translatable("betterloka.duelscreen.best_ever"),
                last > 0 ? Component.translatable("betterloka.duelscreen.season_n", last)
                        : Component.translatable("betterloka.duelscreen.last_season")
        };
        for (int row = 0; row < labels.length; row++) {
            context.text(this.font, this.font.plainSubstrByWidth(labels[row].getString(), labelW - 4), x, y, GuiTheme.MUTED);
            for (int i = 0; i < ladders.length; i++) {
                ArenaService.Career career = record == null ? null : record.career(ladders[i]);
                ArenaService.Best best = career == null ? null : row == 0 ? career.highest() : career.lastSeason();
                int cx = x + labelW + i * colW;
                if (best == null || best.entry().rank() == null) {
                    context.text(this.font, record == null ? "…" : "—", cx, y, 0xFF55555F);
                    continue;
                }
                ArenaRank rank = best.entry().rank();
                String rankText = rank.toString();
                context.text(this.font, this.font.plainSubstrByWidth(rankText, colW - 4), cx, y, rank.color());
                if (row == 0) {
                    String season = best.season() == ArenaService.THIS_SEASON
                            ? Component.translatable("betterloka.duelscreen.now").getString() : "S" + best.season();
                    int after = cx + this.font.width(rankText) + 3;
                    if (after + this.font.width(season) <= cx + colW) {
                        context.text(this.font, season, after, y, 0xFF6E6E7A);
                    } else {
                        hover(cx, y - 1, colW, LINE, Component.literal(rankText + " (" + season + ")"));
                    }
                } else {
                    hover(cx, y - 1, colW, LINE, Component.translatable("betterloka.duelscreen.finished",
                            best.entry().wins(), best.entry().losses()));
                }
            }
            y += LINE;
        }
        return y + 2;
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
        if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height) {
            tooltip = List.of(text);
        }
    }

    protected boolean over(int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** The click on the canvas, or {@code null} off it. */
    protected int[] canvas(MouseButtonEvent click) {
        return new int[]{(int) Math.floor((click.x() - originX) / scale), (int) Math.floor((click.y() - originY) / scale)};
    }

    protected int livePing(Corner corner) {
        ClientPacketListener connection = this.minecraft == null ? null : this.minecraft.getConnection();
        if (connection == null || !corner.known()) {
            return -1;
        }
        PlayerInfo info = corner.uuid != null ? connection.getPlayerInfo(corner.uuid) : connection.getPlayerInfo(corner.name);
        return info == null || info.getLatency() <= 0 ? -1 : info.getLatency();
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
