package com.betterloka.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/** Stand-in for the modules that are still to be written. */
public class ModulePlaceholderScreen extends Screen {
    private final Screen parent;

    public ModulePlaceholderScreen(Screen parent, Text moduleName) {
        super(moduleName);
        this.parent = parent;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 32, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 3, GuiTheme.TEXT);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable("betterloka.module.not_ready"),
                this.width / 2, this.height / 3 + 16, GuiTheme.MUTED);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
