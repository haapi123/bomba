package com.betterloka.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Stand-in for the modules that are still to be written. */
public class ModulePlaceholderScreen extends Screen {
    private final Screen parent;

    public ModulePlaceholderScreen(Screen parent, Component moduleName) {
        super(moduleName);
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 32, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, this.height / 3, GuiTheme.TEXT);
        context.centeredText(this.font, Component.translatable("betterloka.module.not_ready"),
                this.width / 2, this.height / 3 + 16, GuiTheme.MUTED);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
