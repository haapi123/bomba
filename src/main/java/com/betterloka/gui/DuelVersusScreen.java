package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.duel.DuelLog;
import com.betterloka.duel.Duelists;
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

        // The arena's countdown, in a box of its own at the top: the game would draw it under this
        // screen, in the middle of everything (BetterLoka keeps it from doing so while this is open).
        int boxX = left + 5;
        int boxW = width - 10;
        int boxY = PANEL_TOP;
        int boxH = 58;
        Component title = com.betterloka.duel.DuelTracker.arenaTitle();
        MapStyle.card(context, boxX, boxY, boxW, boxH, title != null ? GuiTheme.LIVE : 0xFF3A3A48);
        context.centeredText(this.font, Component.translatable("betterloka.duelscreen.countdown"), centre, boxY + 5, GuiTheme.MUTED);
        int inner = boxW - 10;
        if (title != null) {
            int textW = Math.max(1, this.font.width(title));
            float size = Math.min(3f, inner / (float) textW);
            if (size >= 1f) {
                bigText(context, title, centre, boxY + 18 + Math.round((27 - 9 * size) / 2f), size, 0xFFFFFFFF);
            } else {
                List<FormattedCharSequence> lines = this.font.split(title, inner);
                int y = boxY + 17 + Math.max(0, (36 - lines.size() * LINE) / 2);
                for (int i = 0; i < Math.min(3, lines.size()); i++) {
                    context.centeredText(this.font, lines.get(i), centre, y, 0xFFFFFFFF);
                    y += LINE;
                }
            }
        } else {
            // A slow pulse: waiting.
            int pulse = (int) (System.currentTimeMillis() / 400 % 4);
            Component waiting = Component.translatable("betterloka.duelscreen.waiting").withStyle(ChatFormatting.BOLD)
                    .append(".".repeat(pulse));
            List<FormattedCharSequence> lines = this.font.split(waiting, inner);
            int y = boxY + 18 + Math.max(0, (34 - lines.size() * LINE) / 2);
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                context.centeredText(this.font, lines.get(i), centre, y, GuiTheme.LIVE);
                y += LINE;
            }
        }

        bigText(context, Component.literal("VS").withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC), centre, 100, 3f, GuiTheme.LIVE);

        int y = 138;
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
                y += 10;
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
    protected int drawBody(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.ranked"), corner.color);
        y = ranked(context, corner, x, y, width);

        y = section(context, x, y + 2, width, Component.translatable("betterloka.duelscreen.ranks"), corner.color);
        y = rankTiles(context, corner, x, y, width);

        DuelLog log = BetterLokaClient.duelLog();
        if (corner.you) {
            y = section(context, x, y + 2, width, Component.translatable("betterloka.duelscreen.recent"), corner.color);
            y = matchList(context, log == null ? List.of() : log.recent(corner.uuid, 5), x, y, width, 5,
                    Component.translatable("betterloka.duelscreen.recent_none"));
        } else {
            y = section(context, x, y + 2, width, Component.translatable("betterloka.duelscreen.between"), corner.color);
            y = matchList(context, log == null ? List.of() : log.between(you.uuid, corner.uuid, corner.name).matches(),
                    x, y, width, 5, Component.translatable("betterloka.duelscreen.between_none"));
        }

        y = section(context, x, y + 4, width, Component.translatable("betterloka.duelscreen.names"), corner.color);
        return previousNames(context, corner, x, y, width);
    }
}
