package com.betterloka.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.function.Function;

/** The root menu, opened by the BetterLoka keybind. One button per module. */
public class BetterLokaMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 23;

    private final Screen parent;

    public BetterLokaMenuScreen(Screen parent) {
        super(Text.translatable("betterloka.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = Math.max(24, this.height / 2 - 103);

        y = addModule(x, y, "betterloka.module.player_finder", PlayerFinderScreen::new);
        y = addModule(x, y, "betterloka.module.translator", TranslatorScreen::new);
        y = addModule(x, y, "betterloka.module.loka_market", LokaMarketScreen::new);
        y = addModule(x, y, "betterloka.module.town_finder", TownFinderScreen::new);
        y = addModule(x, y, "betterloka.module.town_logger", TownLoggerScreen::new);
        y = addModule(x, y, "betterloka.module.fight_manager", FightManagerScreen::new);
        y = addModule(x, y, "betterloka.module.loka_grinder", LokaGrinderScreen::new);
        addModule(x, y, "betterloka.module.loka_helper", null);

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(x, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /**
     * @param open how to build the module's screen, or {@code null} for one that is not written yet —
     *             those still get a button so the menu shows where BetterLoka is heading.
     * @return the y for the next button.
     */
    private int addModule(int x, int y, String translationKey, Function<Screen, Screen> open) {
        Text label = Text.translatable(translationKey);
        addDrawableChild(ButtonWidget.builder(label, button -> this.client.setScreen(
                        open != null ? open.apply(this) : new ModulePlaceholderScreen(this, label)))
                .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        return y + BUTTON_SPACING;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int titleY = Math.max(6, this.height / 2 - 103 - 26);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, titleY, GuiTheme.TEXT);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("betterloka.menu.subtitle"),
                this.width / 2, titleY + 12, GuiTheme.MUTED);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
