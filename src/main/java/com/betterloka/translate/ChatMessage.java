package com.betterloka.translate;

import java.util.EnumMap;
import java.util.Map;

/**
 * A chat line with the colour the server gave each of its characters.
 *
 * <p>Loka marks town and alliance chat by colour and nothing else, so the plain string is not enough
 * to tell which conversation a message came from. Holding the colours per character means the
 * channel can be read off the part that matters — the message body — rather than off the whole line,
 * whose tags and timestamp are coloured to their own scheme.
 *
 * <p>Plain Java on purpose: {@link ChatChannels} turns a Minecraft {@code Text} into one of these, so
 * the parsing below stays testable outside the game.
 */
public final class ChatMessage {
    /** Per character, the colour the server set, or {@code -1} where it left it to the client. */
    private static final int NO_COLOR = -1;

    /**
     * Below this share of the region, a colour is incidental — a coloured rank inside an otherwise
     * white line — rather than the channel the message was written in.
     */
    private static final double MINIMUM_SHARE = 0.4;

    private final String text;
    private final int[] colors;

    public ChatMessage(String text, int[] colors) {
        this.text = text == null ? "" : text;
        this.colors = colors != null && colors.length == this.text.length()
                ? colors
                : uncolored(this.text.length());
    }

    /** A message with no colour information — what the tests and the plain-string path use. */
    public static ChatMessage plain(String text) {
        return new ChatMessage(text, null);
    }

    /** A message written entirely in one colour, as team chat arrives. */
    public static ChatMessage colored(String text, int rgb) {
        int[] colors = new int[text == null ? 0 : text.length()];
        java.util.Arrays.fill(colors, rgb);
        return new ChatMessage(text, colors);
    }

    private static int[] uncolored(int length) {
        int[] colors = new int[length];
        java.util.Arrays.fill(colors, NO_COLOR);
        return colors;
    }

    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    /**
     * The channel the characters in {@code [from, to)} were written in.
     *
     * @return the dominant team channel over that range, or {@link ChatChannel#PUBLIC} when no team
     * colour holds enough of it to be the channel rather than decoration.
     */
    public ChatChannel channelOf(int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(text.length(), to);
        if (start >= end) {
            return ChatChannel.PUBLIC;
        }
        Map<ChatChannel, Integer> counts = new EnumMap<>(ChatChannel.class);
        int visible = 0;
        for (int i = start; i < end; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                continue;
            }
            visible++;
            if (colors[i] != NO_COLOR) {
                ChatChannel channel = ChatChannel.ofColor(colors[i]);
                if (channel.isTeamChannel()) {
                    counts.merge(channel, 1, Integer::sum);
                }
            }
        }
        if (visible == 0) {
            return ChatChannel.PUBLIC;
        }
        ChatChannel best = ChatChannel.PUBLIC;
        int bestCount = 0;
        for (Map.Entry<ChatChannel, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return bestCount >= visible * MINIMUM_SHARE ? best : ChatChannel.PUBLIC;
    }

    /** A view of this message with the first {@code offset} characters dropped. */
    public ChatMessage substring(int offset) {
        int start = Math.max(0, Math.min(offset, text.length()));
        int[] rest = new int[text.length() - start];
        System.arraycopy(colors, start, rest, 0, rest.length);
        return new ChatMessage(text.substring(start), rest);
    }
}
