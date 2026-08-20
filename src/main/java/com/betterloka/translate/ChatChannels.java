package com.betterloka.translate;

import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Works out which channel a chat message arrived on by looking at how the server coloured it.
 *
 * <p>Kept apart from {@link ChatLog} so the log itself stays free of Minecraft types and can be
 * tested outside the game.
 */
public final class ChatChannels {
    /**
     * How much of a message has to carry the channel colour before it counts. Town and alliance chat
     * are coloured throughout, while ordinary chat only ever has a coloured word or two — a rank, a
     * town tag, a highlighted name — so a low bar would misfile those.
     */
    private static final double MINIMUM_SHARE = 0.30;

    private ChatChannels() {
    }

    /** @return the channel the message belongs to, or {@link ChatChannel#PUBLIC} if it is not team chat. */
    public static ChatChannel detect(Text message) {
        if (message == null) {
            return ChatChannel.PUBLIC;
        }
        Map<ChatChannel, Integer> byChannel = new EnumMap<>(ChatChannel.class);
        int[] total = {0};

        // Returning empty keeps the walk going; the whole message has to be measured.
        message.visit((style, run) -> {
            int visible = countVisible(run);
            if (visible > 0) {
                total[0] += visible;
                TextColor color = style.getColor();
                if (color != null) {
                    ChatChannel channel = ChatChannel.ofColor(color.getRgb());
                    if (channel.isTeamChannel()) {
                        byChannel.merge(channel, visible, Integer::sum);
                    }
                }
            }
            return Optional.empty();
        }, Style.EMPTY);

        if (total[0] == 0) {
            return ChatChannel.PUBLIC;
        }
        ChatChannel best = ChatChannel.PUBLIC;
        int bestCount = 0;
        for (Map.Entry<ChatChannel, Integer> entry : byChannel.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestCount >= total[0] * MINIMUM_SHARE ? best : ChatChannel.PUBLIC;
    }

    /** Spaces and punctuation carry no colour signal worth weighing. */
    private static int countVisible(String run) {
        int count = 0;
        for (int i = 0; i < run.length(); i++) {
            if (!Character.isWhitespace(run.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
