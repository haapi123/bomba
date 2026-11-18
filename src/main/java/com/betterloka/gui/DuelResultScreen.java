package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.duel.DuelLog;
import com.betterloka.duel.DuelStats;
import com.betterloka.duel.Duelists;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * A finished duel, opened from its summary in chat: each side's hits, crits, sweeps, combo and ping
 * in that duel, with their season, career and ranks under them, and the result and the record
 * between the two in the middle.
 */
public final class DuelResultScreen extends DuelScreen {
    private final DuelLog.Entry duel;

    public DuelResultScreen(DuelLog.Entry duel) {
        super(Component.translatable("betterloka.duelscreen.summary"),
                new Corner(true, duel.selfName(), duel.selfUuid(), GuiTheme.ACCENT),
                new Corner(false, duel.opponentName() == null ? "?" : duel.opponentName(), duel.opponentUuid(), GuiTheme.BAD));
        this.duel = duel;
        // Who won and who lost, in green and red; as before when the mod could not tell.
        DuelStats.Result result = duel.outcome();
        if (result != DuelStats.Result.UNKNOWN) {
            boolean youWon = result == DuelStats.Result.WON;
            mark(you, youWon);
            mark(them, !youWon);
        }
        Duelists duelists = BetterLokaClient.duelists();
        if (duelists != null) {
            you.record = duelists.load(you.name, you.uuid);
            them.record = duelists.load(them.name, them.uuid);
        }
    }

    @Override
    protected int titleColor() {
        return GuiTheme.TEXT;
    }

    @Override
    protected void drawCenter(GuiGraphicsExtractor context, int width, int left) {
        int centre = left + width / 2;
        DuelStats.Result result = duel.outcome();
        Component word = Component.translatable(switch (result) {
            case WON -> "betterloka.duelscreen.won";
            case LOST -> "betterloka.duelscreen.lost";
            default -> "betterloka.duelscreen.over";
        }).withStyle(ChatFormatting.BOLD);
        int color = result == DuelStats.Result.WON ? GuiTheme.GOOD : result == DuelStats.Result.LOST ? GuiTheme.BAD : GuiTheme.MUTED;
        float size = this.font.width(word) * 2 <= width - 6 ? 2f : 1.5f;
        bigText(context, word, centre, 70, size, color);
        context.centeredText(this.font, Component.translatable("betterloka.duelscreen.length", clock(duel.millis())),
                centre, 94, GuiTheme.MUTED);
        context.centeredText(this.font, java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
                        .format(java.time.Instant.ofEpochMilli(duel.at()).atZone(java.time.ZoneId.systemDefault())),
                centre, 105, 0xFF6E6E7A);

        bigText(context, Component.literal("VS").withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC), centre, 126, 3f, GuiTheme.LIVE);

        DuelLog log = BetterLokaClient.duelLog();
        DuelLog.Record record = log == null ? null : log.between(you.uuid, them.uuid, them.name);
        int y = 168;
        if (record != null && !record.matches().isEmpty()) {
            context.centeredText(this.font, Component.translatable("betterloka.duelscreen.head_to_head"), centre, y, GuiTheme.MUTED);
            y += LINE;
            bigText(context, Component.literal(record.wins() + " – " + record.losses()).withStyle(ChatFormatting.BOLD),
                    centre, y, 1.5f, rateColor(record.wins(), record.games()));
            y += 15;
            if (record.matches().size() > record.games()) {
                context.centeredText(this.font, Component.translatable("betterloka.duelscreen.duels_total",
                        record.matches().size()), centre, y, 0xFF6E6E7A);
                y += LINE;
            }
            y += 3;
            int shown = 0;
            for (DuelLog.Entry match : record.matches()) {
                if (shown++ >= 5) {
                    break;
                }
                DuelStats.Result r = match.outcome();
                String mark = r == DuelStats.Result.WON ? "W" : r == DuelStats.Result.LOST ? "L" : "?";
                int c = r == DuelStats.Result.WON ? GuiTheme.GOOD : r == DuelStats.Result.LOST ? GuiTheme.BAD : GuiTheme.MUTED;
                context.centeredText(this.font, Component.literal(mark + "  ").withColor(c & 0xFFFFFF)
                        .append(Component.literal(ago(match.at())).withColor(0x8A8A96)), centre, y, c);
                y += LINE;
            }
        }
        context.centeredText(this.font, Component.translatable("betterloka.duelscreen.esc_only"), centre, H - 22, GuiTheme.MUTED);
    }

    private static void mark(Corner corner, boolean winner) {
        corner.color = winner ? GuiTheme.GOOD : GuiTheme.BAD;
        corner.role = Component.translatable(winner ? "betterloka.duelscreen.winner" : "betterloka.duelscreen.loser");
    }

    @Override
    protected int drawBody(GuiGraphicsExtractor context, Corner corner, int x, int y, int width) {
        DuelLog.Side side = corner.you ? duel.you() : duel.them();
        DuelLog.Ping ping = corner.you ? duel.yourPing() : duel.theirPing();

        y = section(context, x, y, width, Component.translatable("betterloka.duelscreen.this_duel"), corner.color);
        int accuracy = side.accuracy();
        Component hitsTip = Component.translatable("betterloka.duelscreen.hits_tip", side.hits(), side.swings());
        y = tiles(context, x, y, width, List.of(
                new Tile(accuracy < 0 ? "—" : accuracy + "%", Component.translatable("betterloka.duelscreen.accuracy"),
                        accuracy < 0 ? GuiTheme.MUTED : accuracy >= 60 ? GuiTheme.GOOD : accuracy >= 40 ? GuiTheme.LIVE : GuiTheme.BAD, hitsTip),
                new Tile(side.hits() + "/" + (accuracy < 0 ? "?" : side.swings()), Component.translatable("betterloka.duelscreen.hits"),
                        GuiTheme.TEXT, hitsTip),
                new Tile(String.valueOf(side.combo()), Component.translatable("betterloka.duelscreen.combo"), GuiTheme.ACCENT,
                        Component.translatable("betterloka.duelscreen.combo_tip"))));
        Component pingTip = ping.known()
                ? Component.translatable("betterloka.duelscreen.ping_duel_tip", ping.max(), ping.readings())
                : Component.translatable("betterloka.duelscreen.ping_duel_none");
        y = tiles(context, x, y, width, List.of(
                new Tile(side.critShare() + "%", Component.translatable("betterloka.duelscreen.crits"), GuiTheme.LIVE,
                        Component.translatable("betterloka.duelscreen.crits_tip", side.crits())),
                new Tile(side.sweepShare() + "%", Component.translatable("betterloka.duelscreen.sweeps"), GuiTheme.LIVE,
                        Component.translatable("betterloka.duelscreen.sweeps_tip", side.sweeps())),
                new Tile(ping.known() ? ping.average() + " ms" : "—", Component.translatable("betterloka.duelscreen.ping"),
                        ping.known() ? PingColors.color(ping.average()) | 0xFF000000 : GuiTheme.MUTED, pingTip)));
        if (ping.known()) {
            Component spikes = ping.spikes() == 0
                    ? Component.translatable("betterloka.duelscreen.spikes_none")
                    : Component.translatable(ping.every() > 0 ? "betterloka.duelscreen.spikes_every" : "betterloka.duelscreen.spikes",
                    ping.spikes(), ping.up(), ping.down(), (ping.biggest() > 0 ? "+" : "") + ping.biggest(),
                    Math.round(ping.every()));
            List<FormattedCharSequence> lines = this.font.split(spikes, width);
            hover(x, y - 1, width, LINE * lines.size(), Component.translatable("betterloka.duelscreen.spikes_tip"));
            for (FormattedCharSequence line : lines) {
                context.text(this.font, line, x, y, GuiTheme.MUTED);
                y += LINE;
            }
            y += 2;
        }

        y = section(context, x, y + 2, width, Component.translatable("betterloka.duelscreen.pots"), corner.color);
        y = pots(context, side, x, y, width);

        y = section(context, x, y + 4, width, Component.translatable("betterloka.duelscreen.ranked"), corner.color);
        y = ranked(context, corner, x, y, width);

        y = section(context, x, y + 2, width, Component.translatable("betterloka.duelscreen.ranks"), corner.color);
        y = rankTiles(context, corner, x, y, width);

        y = section(context, x, y + 4, width, Component.translatable("betterloka.duelscreen.names"), corner.color);
        return previousNames(context, corner, x, y, width);
    }
}
