package com.betterloka.translate;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a Minecraft chat component into a {@link ChatMessage} — the plain string plus the colour the
 * server set for each character.
 *
 * <p>Kept apart from {@link ChatLog} so the log itself stays free of Minecraft types and can be
 * tested outside the game.
 */
public final class ChatChannels {
    private record Run(String text, int color) {
    }

    private ChatChannels() {
    }

    /** @return the message text with its colours, ready for {@link ChatLog#record}. */
    public static ChatMessage read(Text message) {
        if (message == null) {
            return ChatMessage.plain("");
        }
        List<Run> runs = new ArrayList<>();
        int[] total = {0};

        // Returning empty keeps the walk going; every run has to be collected.
        message.visit((style, run) -> {
            TextColor color = style.getColor();
            runs.add(new Run(run, color == null ? -1 : color.getRgb()));
            total[0] += run.length();
            return Optional.empty();
        }, Style.EMPTY);

        StringBuilder text = new StringBuilder(total[0]);
        int[] colors = new int[total[0]];
        int at = 0;
        for (Run run : runs) {
            text.append(run.text());
            for (int i = 0; i < run.text().length(); i++) {
                colors[at++] = run.color();
            }
        }
        return new ChatMessage(text.toString(), colors);
    }
}
