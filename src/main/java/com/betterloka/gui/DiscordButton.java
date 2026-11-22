package com.betterloka.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.net.URI;

/**
 * The menu's way to the official BetterLoka Discord: a square button with Discord's mark on it. A press
 * goes through the game's own "open this link?" screen, which shows the address before anything opens.
 */
public final class DiscordButton extends Button {
    /** The official BetterLoka Discord. */
    public static final String INVITE = "https://discord.gg/3Wwbq8mrKR";
    static final int SIZE = 20;
    /** How big the mark is drawn, in GUI pixels: there is a picture for each GUI scale at exactly this. */
    private static final int ICON = 14;

    public DiscordButton(Screen returnTo, int x, int y) {
        super(x, y, SIZE, SIZE, Component.translatable("betterloka.menu.discord"),
                ConfirmLinkScreen.confirmLink(returnTo, URI.create(INVITE), true), DEFAULT_NARRATION);
        setTooltip(Tooltip.create(Component.translatable("betterloka.menu.discord.tip")));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // The ordinary button underneath, so it lights up like the others; the mark instead of a label.
        extractDefaultSprite(context);
        int scale = Math.max(1, Math.min(6, Minecraft.getInstance().getWindow().getGuiScale()));
        int pixels = ICON * scale;
        Identifier mark = Identifier.fromNamespaceAndPath("betterloka", "textures/gui/discord_" + scale + ".png");
        int x = getX() + (getWidth() - ICON) / 2;
        int y = getY() + (getHeight() - ICON) / 2;
        context.blit(RenderPipelines.GUI_TEXTURED, mark, x, y, 0f, 0f, ICON, ICON, pixels, pixels, pixels, pixels,
                0xFFFFFFFF);
    }
}
