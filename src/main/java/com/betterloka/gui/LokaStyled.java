package com.betterloka.gui;

import net.minecraft.client.Minecraft;

/**
 * A BetterLoka screen. The game's own buttons and text boxes on it are drawn in BetterLoka's style
 * rather than the game's stone, so a screen built from them matches the windows drawn by hand.
 *
 * <p>One convention comes with it: a button whose label is yellow is the chosen one of a row of tabs,
 * and is drawn as chosen.
 */
public interface LokaStyled {
    /** Whether the screen showing now is a BetterLoka one. */
    static boolean active() {
        return Minecraft.getInstance().gui.screen() instanceof LokaStyled;
    }
}
