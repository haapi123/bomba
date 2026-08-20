package com.betterloka.translate;

/**
 * Which conversation a chat message belongs to.
 *
 * <p>Loka does not tag its channels in the text — it colours them. Town chat is green and alliance
 * chat is light blue, so the channel is read back off the colour the server sent.
 */
public enum ChatChannel {
    /** Ordinary server chat, and anything whose channel could not be worked out. */
    PUBLIC,
    /** Green: your town. */
    TOWN,
    /** Light blue: your alliance. */
    ALLIANCE;

    /**
     * Classifies one colour.
     *
     * <p>Matched by hue rather than by exact code: servers use the vanilla formatting colours
     * ({@code §a}, {@code §b}) but also hand-picked RGB shades of the same two colours, and a green
     * a few points off {@code 0x55FF55} is still town chat.
     *
     * @param rgb a 24-bit colour, as {@code TextColor} reports it
     */
    public static ChatChannel ofColor(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        // Cyan first: it is green with the blue turned up, so a green test alone would claim it.
        if (green >= 128 && blue >= 128 && red < green * 0.7 && red < blue * 0.7) {
            return ALLIANCE;
        }
        // Plain blue counts too: servers differ over whether alliance chat is aqua or blue.
        if (blue >= 170 && blue > red * 1.4 && blue > green * 1.4) {
            return ALLIANCE;
        }
        if (green >= 96 && green > red * 1.4 && green > blue * 1.4) {
            return TOWN;
        }
        return PUBLIC;
    }

    /** Whether this is one of the two channels worth calling out in the Translator. */
    public boolean isTeamChannel() {
        return this != PUBLIC;
    }
}
