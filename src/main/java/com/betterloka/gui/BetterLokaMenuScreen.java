package com.betterloka.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/** The root menu, opened by the BetterLoka keybind. One button per module. */
public class BetterLokaMenuScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 24;

    private final Screen parent;

    public BetterLokaMenuScreen(Screen parent) {
        super(Text.translatable("betterloka.menu.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = this.height / 4 + 12;

        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.module.player_finder"),
                        button -> this.client.setScreen(new PlayerFinderScreen(this)))
                .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        y += BUTTON_SPACING;
        addDrawableChild(placeholderModule("betterloka.module.translator", x, y));

        y += BUTTON_SPACING;
        addDrawableChild(placeholderModule("betterloka.module.fight_manager", x, y));

        y += BUTTON_SPACING;
        addDrawableChild(placeholderModule("betterloka.module.loka_helper", x, y));

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(x, this.height - 32, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /** A module that is not built yet: still listed, so the menu shows where BetterLoka is heading. */
    private ButtonWidget placeholderModule(String translationKey, int x, int y) {
        Text label = Text.translatable(translationKey);
        return ButtonWidget.builder(label,
                        button -> this.client.setScreen(new ModulePlaceholderScreen(this, label)))
                .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, this.height / 4 - 20, GuiTheme.TEXT);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("betterloka.menu.subtitle"),
                this.width / 2, this.height / 4 - 8, GuiTheme.MUTED);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
