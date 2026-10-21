package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.helper.GuiScaleController;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The odds and ends that make playing Loka easier.
 *
 * <p>Right now that is the GUI scale: an inventory big enough to hit a slot without aiming, and a
 * hotbar that stays the size it was. Minecraft has one scale for both, which is why picking a
 * comfortable inventory has always cost you a third of the screen while you play.
 */
public class LokaHelperScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int ROW_HEIGHT = 11;
    private static final int CARD_PADDING = 6;
    private static final int CARD_GAP = 6;

    private static final int BUTTON_HEIGHT = 18;
    private static final int CONTENT_TOP = 30;

    private final Screen parent;

    private Button toggleButton;
    private Button inventoryButton;
    private Button hotbarButton;

    public LokaHelperScreen(Screen parent) {
        super(Component.translatable("betterloka.module.loka_helper"));
        this.parent = parent;
    }

    private static BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int y = CONTENT_TOP + ROW_HEIGHT + CARD_PADDING;

        toggleButton = Button.builder(toggleLabel(), button -> {
                    config().setGuiScaleEnabled(!config().guiScaleEnabled());
                    refreshLabels();
                })
                .bounds(left, y, width, BUTTON_HEIGHT).build();
        addRenderableWidget(toggleButton);
        y += BUTTON_HEIGHT + CARD_GAP;

        int half = (width - 4) / 2;
        inventoryButton = Button.builder(inventoryLabel(), button -> {
                    config().setInventoryGuiScale(GuiScaleController.next(config().inventoryGuiScale()));
                    refreshLabels();
                })
                .bounds(left, y, half, BUTTON_HEIGHT).build();
        addRenderableWidget(inventoryButton);

        hotbarButton = Button.builder(hotbarLabel(), button -> {
                    config().setHotbarGuiScale(GuiScaleController.next(config().hotbarGuiScale()));
                    refreshLabels();
                })
                .bounds(left + half + 4, y, width - half - 4, BUTTON_HEIGHT).build();
        addRenderableWidget(hotbarButton);

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    /** Rebuilt in place rather than through {@code clearAndInit}, so a press does not rebuild all. */
    private void refreshLabels() {
        toggleButton.setMessage(toggleLabel());
        inventoryButton.setMessage(inventoryLabel());
        hotbarButton.setMessage(hotbarLabel());
        boolean on = config().guiScaleEnabled();
        inventoryButton.active = on;
        hotbarButton.active = on;
    }

    private Component toggleLabel() {
        boolean on = config().guiScaleEnabled();
        return Component.translatable("betterloka.helper.gui_scale_toggle",
                Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    private Component inventoryLabel() {
        return Component.translatable("betterloka.helper.inventory_scale",
                GuiScaleController.label(config().inventoryGuiScale()));
    }

    private Component hotbarLabel() {
        return Component.translatable("betterloka.helper.hotbar_scale",
                GuiScaleController.label(config().hotbarGuiScale()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        boolean on = config().guiScaleEnabled();
        inventoryButton.active = on;
        hotbarButton.active = on;

        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.text(this.font,
                Component.translatable("betterloka.helper.gui_scale"), left, CONTENT_TOP, GuiTheme.MUTED);

        // Below the controls it describes, not above them.
        int y = CONTENT_TOP + ROW_HEIGHT + CARD_PADDING + (BUTTON_HEIGHT + CARD_GAP) * 2;
        GuiTheme.panel(context, left, y, width, CARD_PADDING * 2 + ROW_HEIGHT);
        context.text(this.font,
                Component.translatable(on ? "betterloka.helper.gui_scale_hint"
                        : "betterloka.helper.gui_scale_off"),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
