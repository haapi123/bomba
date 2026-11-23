package com.betterloka.gui;

import com.betterloka.BetterLoka;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.util.List;
import java.util.function.Function;

/**
 * The root menu, opened by the BetterLoka keybind: a window with a tile for every module, each with
 * its icon, its name and what it does in a line, and the way to the official Discord along the bottom.
 *
 * <p>The tiles are real buttons, so the keyboard moves between them and the narrator reads them.
 */
public class BetterLokaMenuScreen extends Screen implements LokaStyled {
    private static final int GAP = 5;
    private static final int FOOTER = 20;

    /** A module: what opens it, its icon, and the colour its tile is touched with. */
    private record Module(String key, String id, Item icon, int accent, Function<Screen, Screen> open) {
    }

    private static final List<Module> MODULES = List.of(
            new Module("betterloka.module.player_finder", "player_finder", Items.PLAYER_HEAD, 0xFF4FC3F7, PlayerFinderScreen::new),
            new Module("betterloka.module.translator", "translator", Items.WRITABLE_BOOK, 0xFF62D8E8, TranslatorScreen::new),
            new Module("betterloka.module.loka_market", "loka_market", Items.EMERALD, 0xFF5FD37A, LokaMarketScreen::new),
            new Module("betterloka.module.town_logger", "town_logger", Items.BELL, 0xFFE06C75, TownLoggerScreen::new),
            new Module("betterloka.module.loka_grinder", "loka_grinder", Items.CLOCK, 0xFFFFB454, LokaGrinderScreen::new),
            new Module("betterloka.module.loka_map", "loka_map", Items.FILLED_MAP, 0xFF6FB6FF, LokaMapScreen::new),
            new Module("betterloka.module.loka_helper", "loka_helper", Items.COMPASS, HelperUi.GOLD, LokaHelperScreen::new),
            new Module("betterloka.module.loka_calculator", "loka_calculator", Items.COMPARATOR, 0xFFB9A0FF, CalculatorTabs::open),
            new Module("betterloka.module.assistant", "assistant", Items.ENCHANTED_BOOK, 0xFFE58CFF, AssistantScreen::new));

    private final Screen parent;
    private final LokaWindow window;

    public BetterLokaMenuScreen(Screen parent) {
        super(Component.translatable("betterloka.menu.title"));
        this.parent = parent;
        this.window = new LokaWindow(this.title, Component.translatable("betterloka.menu.subtitle"), this::onClose);
    }

    @Override
    protected void init() {
        // Three columns where they fit, two or one on a narrow screen.
        int columns = this.width >= 400 ? 3 : this.width >= 260 ? 2 : 1;
        int rows = (MODULES.size() + columns - 1) / columns;
        int tileW = columns == 3 ? 156 : columns == 2 ? 156 : 200;
        // As tall as the screen allows, up to a tile with two lines of description.
        int chrome = LokaWindow.HEADER + 2 * LokaWindow.PAD + GAP + FOOTER + 12;
        int room = this.height - 12 - chrome - (rows - 1) * GAP;
        int tileH = Math.max(20, Math.min(44, room / rows));
        window.content(columns * tileW + (columns - 1) * GAP, rows * tileH + (rows - 1) * GAP + GAP + FOOTER);
        window.layout(this.width, this.height);
        tileW = (window.innerWidth() - (columns - 1) * GAP) / columns;

        for (int i = 0; i < MODULES.size(); i++) {
            Module module = MODULES.get(i);
            int col = i % columns;
            int row = i / columns;
            int x = window.left() + col * (tileW + GAP);
            int y = window.top() + row * (tileH + GAP);
            addRenderableWidget(new Tile(module, x, y, tileW, tileH,
                    () -> this.minecraft.gui.setScreen(module.open().apply(this))));
        }

        int footerY = window.bottom() - FOOTER;
        Component join = Component.translatable("betterloka.menu.discord.join");
        int discordW = Math.min(window.innerWidth() / 2, this.font.width(join) + 34);
        addRenderableWidget(new DiscordPill(this, window.left(), footerY, discordW, FOOTER, join));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        window.draw(context, this.font, mouseX, mouseY);
        // The line between the tiles and the footer.
        int y = window.bottom() - FOOTER - GAP;
        context.fill(window.left(), y + GAP / 2, window.right(), y + GAP / 2 + 1, 0x1EFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        String version = "v" + BetterLoka.VERSION;
        context.text(this.font, version, window.right() - this.font.width(version) - 2,
                window.bottom() - FOOTER + (FOOTER - 8) / 2, HelperUi.FAINT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return window.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    /** A module's tile: its icon on a tile of its colour, its name, and what it does. */
    private static final class Tile extends Button {
        private final Module module;
        private final ItemStack icon;
        private float hover;
        private long lastFrame;

        Tile(Module module, int x, int y, int width, int height, Runnable open) {
            super(x, y, width, height, Component.translatable(module.key()), button -> open.run(), DEFAULT_NARRATION);
            this.module = module;
            this.icon = new ItemStack(module.icon());
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 1f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
            lastFrame = now;
            hover += ((isHoveredOrFocused() ? 1f : 0f) - hover) * (1 - (float) Math.exp(-16 * dt));

            Font font = Minecraft.getInstance().font;
            int x = getX();
            int y = getY() - Math.round(hover);
            int w = getWidth();
            int h = getHeight();
            int accent = module.accent();
            if (hover > 0.01f) {
                HelperUi.round(context, x, getY() + 2, w, h, 5, HelperUi.alpha(0x000000, Math.round(70 * hover)));
            }
            HelperUi.framed(context, x, y, w, h, 5, HelperUi.mix(HelperUi.CARD, HelperUi.CARD_HOVER, hover),
                    HelperUi.mix(HelperUi.CARD_EDGE, HelperUi.mix(HelperUi.CARD_EDGE_HOVER, accent, 0.55f), hover));
            // The module's colour along the left, growing as the pointer comes.
            int stripe = Math.round(6 + (h - 16) * hover);
            HelperUi.round(context, x + 1, y + (h - stripe) / 2, 2, stripe, 1, HelperUi.alpha(accent, 0x90 + Math.round(0x6F * hover)));

            int tile = Math.min(24, h - 8);
            int tx = x + 7;
            int ty = y + (h - tile) / 2;
            HelperUi.round(context, tx, ty, tile, tile, 4, HelperUi.mix(HelperUi.ICON_TILE, accent, 0.16f + 0.14f * hover));
            context.item(icon, tx + (tile - 16) / 2, ty + (tile - 16) / 2);

            int textX = tx + tile + 7;
            int textW = x + w - 6 - textX;
            Component name = getMessage().copy().withStyle(ChatFormatting.BOLD);
            Component desc = Component.translatable("betterloka.module." + module.id() + ".desc");
            List<FormattedCharSequence> lines = h >= 34 ? font.split(desc, textW) : List.of();
            int shown = Math.min(lines.size(), h >= 42 ? 2 : 1);
            int blockH = 9 + shown * 10;
            int ny = y + (h - blockH) / 2;
            context.text(font, font.width(name) <= textW ? name.getVisualOrderText()
                    : Component.literal(font.plainSubstrByWidth(name.getString(), textW)).withStyle(ChatFormatting.BOLD)
                    .getVisualOrderText(), textX, ny, HelperUi.mix(HelperUi.TEXT, 0xFFFFFFFF, hover), false);
            for (int i = 0; i < shown; i++) {
                context.text(font, lines.get(i), textX, ny + 11 + i * 10, HelperUi.mix(HelperUi.MUTED, 0xFFB4BED2, hover),
                        false);
            }
            if (lines.size() > shown && isHovered()) {
                context.setTooltipForNextFrame(font, desc, mouseX, mouseY);
            }
        }
    }

    /**
     * The way to the official BetterLoka Discord, with Discord's mark. A press goes through the game's
     * own "open this link?" screen, which shows the address before anything opens.
     */
    private static final class DiscordPill extends Button {
        private static final int BLURPLE = 0xFF5865F2;
        private static final int ICON = 14;
        private float hover;
        private long lastFrame;

        DiscordPill(Screen returnTo, int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label,
                    ConfirmLinkScreen.confirmLink(returnTo, URI.create(DiscordButton.INVITE), true), DEFAULT_NARRATION);
            setTooltip(Tooltip.create(Component.translatable("betterloka.menu.discord.tip")));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 1f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
            lastFrame = now;
            hover += ((isHoveredOrFocused() ? 1f : 0f) - hover) * (1 - (float) Math.exp(-16 * dt));
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            HelperUi.framed(context, x, y, w, h, h / 2, HelperUi.mix(0xFF1B2140, 0xFF2A3270, hover),
                    HelperUi.mix(0xFF3B4590, BLURPLE, hover));
            int scale = Math.max(1, Math.min(6, Minecraft.getInstance().getWindow().getGuiScale()));
            int pixels = ICON * scale;
            Identifier mark = Identifier.fromNamespaceAndPath("betterloka", "textures/gui/discord_" + scale + ".png");
            context.blit(RenderPipelines.GUI_TEXTURED, mark, x + 7, y + (h - ICON) / 2, 0f, 0f, ICON, ICON, pixels, pixels,
                    pixels, pixels, 0xFFFFFFFF);
            Font font = Minecraft.getInstance().font;
            String text = font.plainSubstrByWidth(getMessage().getString(), w - 30);
            context.text(font, text, x + 25, y + (h - 8) / 2, HelperUi.mix(0xFFC9CEF8, 0xFFFFFFFF, hover), false);
        }
    }
}
