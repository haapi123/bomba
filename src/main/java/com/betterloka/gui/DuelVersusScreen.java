package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.duel.DuelLog;
import com.betterloka.duel.Duelists;
import com.betterloka.stats.ArenaService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.UUID;

/**
 * "Match Ready!": the player and their opponent side by side — this season, their career, best rank,
 * last season, ping and latest matches — and a green button in the middle that joins the duel. Esc
 * closes it without joining.
 *
 * <p>Loka's line does not say who the opponent is, as far as is known; when it does (in its text or
 * tooltip) they are shown, otherwise their side says they are known once the duel starts.
 */
public final class DuelVersusScreen extends DuelScreen {
    private static final int BUTTON_W = 112;
    private static final int BUTTON_H = 26;
    private static final int GREEN = 0xFF3FAF55;
    private static final int GREEN_HOVER = 0xFF52C96A;

    private final Runnable join;
    private int buttonX;
    private int buttonY;

    /**
     * @param opponentName the opponent if the line named them, else {@code null}
     * @param join         sends the join command
     */
    public DuelVersusScreen(String selfName, UUID self, String opponentName, Runnable join) {
        super(Component.translatable("betterloka.duelscreen.found"),
                new Corner(true, selfName, self, GuiTheme.ACCENT),
                new Corner(false, opponentName, null, GuiTheme.BAD));
        this.join = join;
        Duelists duelists = BetterLokaClient.duelists();
        if (duelists != null) {
            you.record = duelists.load(selfName, self);
            if (opponentName != null) {
                them.record = duelists.load(opponentName, null);
            }
        }
    }

    @Override
    protected void drawCenter(GuiGraphicsExtractor context, int width, int left) {
        int centre = left + width / 2;
        bigText(context, Component.literal("VS").withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC), centre, 96, 3f, GuiTheme.LIVE);

        buttonX = centre - BUTTON_W / 2;
        buttonY = 140;
        boolean hot = over(buttonX, buttonY, BUTTON_W, BUTTON_H);
        int fill = hot ? GREEN_HOVER : GREEN;
        context.fill(buttonX + 1, buttonY, buttonX + BUTTON_W - 1, buttonY + BUTTON_H, fill);
        context.fill(buttonX, buttonY + 1, buttonX + BUTTON_W, buttonY + BUTTON_H - 1, fill);
        context.fill(buttonX + 1, buttonY + BUTTON_H - 2, buttonX + BUTTON_W - 1, buttonY + BUTTON_H, 0xFF2A7D3B);
        context.fill(buttonX + 1, buttonY, buttonX + BUTTON_W - 1, buttonY + 1, 0xFF8BE39D);
        Component label = Component.translatable("betterloka.duelscreen.join").withStyle(ChatFormatting.BOLD);
        context.centeredText(this.font, label, centre, buttonY + 9, 0xFFFFFFFF);

        int y = buttonY + BUTTON_H + 8;
        for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.duelscreen.esc"), width - 8)) {
            context.centeredText(this.font, line, centre, y, GuiTheme.MUTED);
            y += LINE;
        }

        // The two of you, if you have met before.
        if (them.known()) {
            DuelLog log = BetterLokaClient.duelLog();
            DuelLog.Record record = log == null ? null : log.between(you.uuid, null, them.name);
            if (record != null && !record.matches().isEmpty()) {
                y += 6;
                context.centeredText(this.font, Component.translatable("betterloka.duelscreen.head_to_head"), centre, y, GuiTheme.MUTED);
                y += LINE;
                bigText(context, Component.literal(record.wins() + " – " + record.losses()).withStyle(ChatFormatting.BOLD),
                        centre, y, 1.5f, rateColor(record.wins(), record.games()));
                if (record.matches().size() > record.games()) {
                    // Some ended with no winner the mod could see.
                    context.centeredText(this.font, Component.translatable("betterloka.duelscreen.duels_total",
                            record.matches().size()), centre, y + 15, 0xFF6E6E7A);
                }
            }
        }
    }

    @Override
    protected void drawBody(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        if (!corner.known()) {
            y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.who"), corner.color);
            for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.duelscreen.unknown"), width)) {
                context.text(this.font, line, x, y, GuiTheme.MUTED);
                y += LINE;
            }
            return;
        }
        Duelists.Duelist record = corner.duelist();
        int seasonNumber = record == null ? -1 : record.seasonNumber();

        ArenaService.Totals season = record == null ? null : record.season();
        y = section(context, x, y, width, seasonNumber > 0
                ? Component.translatable("betterloka.duelscreen.this_season_n", seasonNumber)
                : Component.translatable("betterloka.duelscreen.this_season"), corner.color);
        y = totals(context, x, y, width, season, Component.translatable("betterloka.duelscreen.season_tip"));

        ArenaService.Totals career = record == null ? null : record.career();
        y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.career"), corner.color);
        y = totals(context, x, y, width, career, career == null ? null
                : Component.translatable("betterloka.duelscreen.career_tip", career.seasons()));

        y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.ranks"), corner.color);
        y = rankTable(context, corner, x, y, width);

        DuelLog log = BetterLokaClient.duelLog();
        if (corner.you) {
            y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.recent"), corner.color);
            matchList(context, log == null ? List.of() : log.recent(corner.uuid, 5), x, y, width, 5,
                    Component.translatable("betterloka.duelscreen.recent_none"));
        } else {
            y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.between"), corner.color);
            matchList(context, log == null ? List.of() : log.between(you.uuid, corner.uuid, corner.name).matches(),
                    x, y, width, 5, Component.translatable("betterloka.duelscreen.between_none"));
        }
    }

    /** Games, wins–losses and the win rate, as three tiles. */
    private int totals(GuiGraphicsExtractor context, int x, int y, int width, ArenaService.Totals totals, Component tip) {
        if (totals == null) {
            return tiles(context, x, y, width, List.of(
                    new Tile("…", Component.translatable("betterloka.duelscreen.duels"), GuiTheme.MUTED),
                    new Tile("…", Component.translatable("betterloka.duelscreen.wl"), GuiTheme.MUTED),
                    new Tile("…", Component.translatable("betterloka.duelscreen.winrate"), GuiTheme.MUTED)));
        }
        int games = totals.games();
        return tiles(context, x, y, width, List.of(
                new Tile(number(games), Component.translatable("betterloka.duelscreen.duels"), GuiTheme.TEXT, tip),
                new Tile(totals.wins() + "–" + totals.losses(),
                        Component.translatable("betterloka.duelscreen.wl"), GuiTheme.ACCENT, tip),
                new Tile(percent(totals.wins(), games), Component.translatable("betterloka.duelscreen.winrate"),
                        rateColor(totals.wins(), games), tip)));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        int[] at = canvas(click);
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && at[0] >= buttonX && at[0] < buttonX + BUTTON_W
                && at[1] >= buttonY && at[1] < buttonY + BUTTON_H) {
            onClose();
            join.run();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == InputConstants.KEY_RETURN || input.key() == InputConstants.KEY_NUMPADENTER) {
            onClose();
            join.run();
            return true;
        }
        return super.keyPressed(input);
    }
}
