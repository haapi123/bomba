package com.betterloka.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

/** The root menu, opened by the BetterLoka keybind. One button per module. */
public class BetterLokaMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 23;
    /** How big the logo is drawn, in GUI pixels: there is a picture for each GUI scale at exactly this. */
    private static final int LOGO = 26;
    private static final int LOGO_GAP = 6;

    private final Screen parent;

    public BetterLokaMenuScreen(Screen parent) {
        super(Component.translatable("betterloka.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = Math.max(24, this.height / 2 - 103);

        y = addModule(x, y, "betterloka.module.player_finder", PlayerFinderScreen::new);
        y = addModule(x, y, "betterloka.module.translator", TranslatorScreen::new);
        y = addModule(x, y, "betterloka.module.loka_market", LokaMarketScreen::new);
        y = addModule(x, y, "betterloka.module.town_logger", TownLoggerScreen::new);
        y = addModule(x, y, "betterloka.module.loka_grinder", LokaGrinderScreen::new);
        y = addModule(x, y, "betterloka.module.loka_map", LokaMapScreen::new);
        y = addModule(x, y, "betterloka.module.loka_helper", LokaHelperScreen::new);
        y = addModule(x, y, "betterloka.module.loka_calculator", CalculatorTabs::open);
        addModule(x, y, "betterloka.module.assistant", AssistantScreen::new);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /**
     * @param open how to build the module's screen, or {@code null} for one that is not written yet —
     *             those still get a button so the menu shows where BetterLoka is heading.
     * @return the y for the next button.
     */
    private int addModule(int x, int y, String translationKey, Function<Screen, Screen> open) {
        Component label = Component.translatable(translationKey);
        addRenderableWidget(Button.builder(label, button -> this.minecraft.gui.setScreen(
                        open != null ? open.apply(this) : new ModulePlaceholderScreen(this, label)))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        return y + BUTTON_SPACING;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        int titleY = Math.max(6, this.height / 2 - 103 - 26);
        int buttonsTop = Math.max(24, this.height / 2 - 103);
        // The logo beside the name, the two together centred over the buttons.
        Component subtitle = Component.translatable("betterloka.menu.subtitle");
        int size = Math.max(0, Math.min(LOGO, buttonsTop - 2 - (titleY - 3)));
        int textWidth = Math.max(this.font.width(this.title), this.font.width(subtitle));
        int left = (this.width - (size + LOGO_GAP + textWidth)) / 2;
        drawLogo(context, left, titleY - 3, size);
        int textX = left + size + LOGO_GAP;
        context.text(this.font, this.title, textX, titleY, GuiTheme.TEXT);
        context.text(this.font, subtitle, textX, titleY + 12, GuiTheme.MUTED);
    }

    /**
     * The logo, from the picture made for the current GUI scale, so it is drawn pixel for pixel
     * rather than squeezed from a bigger one.
     */
    private void drawLogo(GuiGraphicsExtractor context, int x, int y, int size) {
        if (size <= 0) {
            return;
        }
        int scale = Math.max(1, Math.min(6, this.minecraft.getWindow().getGuiScale()));
        int pixels = LOGO * scale;
        Identifier logo = Identifier.fromNamespaceAndPath("betterloka", "textures/gui/logo_" + scale + ".png");
        context.blit(RenderPipelines.GUI_TEXTURED, logo, x, y, 0f, 0f, size, size, pixels, pixels, pixels, pixels,
                0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
