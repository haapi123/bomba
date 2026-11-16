package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.duel.DuelLog;
import com.betterloka.duel.Duelists;
import com.betterloka.stats.ArenaService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.UUID;

/**
 * At the arena, waiting for the fight: the player and their opponent side by side — this season,
 * their career, best rank, last season, ping and latest matches. It goes by itself the moment the
 * fight starts ({@link com.betterloka.duel.DuelTracker} watches for that); Esc closes it sooner.
 */
public final class DuelVersusScreen extends DuelScreen {
    /** @param opponentUuid the opponent's account if the server lists them, else {@code null} */
    public DuelVersusScreen(String selfName, UUID self, String opponentName, UUID opponentUuid) {
        super(Component.translatable("betterloka.duelscreen.found"),
                new Corner(true, selfName, self, GuiTheme.ACCENT),
                new Corner(false, opponentName, opponentUuid, GuiTheme.BAD));
        Duelists duelists = BetterLokaClient.duelists();
        if (duelists != null) {
            you.record = duelists.load(selfName, self);
            if (opponentName != null) {
                them.record = duelists.load(opponentName, opponentUuid);
            }
        }
    }

    /** Who this screen shows as the opponent. */
    public String opponentName() {
        return them.name;
    }

    @Override
    protected void drawCenter(GuiGraphicsExtractor context, int width, int left) {
        int centre = left + width / 2;
        bigText(context, Component.literal("VS").withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC), centre, 96, 3f, GuiTheme.LIVE);

        int y = 138;
        // The arena's countdown, which the game would draw under this screen.
        Component title = com.betterloka.duel.DuelTracker.arenaTitle();
        if (title != null) {
            bigText(context, title, centre, y, this.font.width(title) * 3 <= width - 8 ? 3f : 1.5f, 0xFFFFFFFF);
            y += 32;
        } else {
            // A slow pulse: waiting.
            int pulse = (int) (System.currentTimeMillis() / 400 % 4);
            Component waiting = Component.translatable("betterloka.duelscreen.waiting").withStyle(ChatFormatting.BOLD)
                    .append(".".repeat(pulse));
            for (FormattedCharSequence line : this.font.split(waiting, width - 12)) {
                context.centeredText(this.font, line, centre, y, GuiTheme.LIVE);
                y += LINE;
            }
            y += 4;
        }
        for (FormattedCharSequence line : this.font.split(Component.translatable("betterloka.duelscreen.closes_itself"), width - 8)) {
            context.centeredText(this.font, line, centre, y, GuiTheme.MUTED);
            y += LINE;
        }
        y += 2;
        context.centeredText(this.font, Component.translatable("betterloka.duelscreen.esc_only"), centre, y, GuiTheme.MUTED);
        y += LINE;

        // The two of you, if you have met before.
        if (them.known()) {
            DuelLog log = BetterLokaClient.duelLog();
            DuelLog.Record record = log == null ? null : log.between(you.uuid, them.uuid, them.name);
            if (record != null && !record.matches().isEmpty()) {
                y += 8;
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
}
