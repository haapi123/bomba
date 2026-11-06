package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.helper.GuiScaleController;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The odds and ends that make playing Loka easier.
 *
 * <ul>
 *   <li>The GUI scale: an inventory big enough to hit a slot without aiming, and a hotbar that stays
 *       the size it was.</li>
 *   <li>Coordinates and facing on screen, wherever the player puts them — P moves them.</li>
 *   <li>Clear lava: lava seen through rather than as a wall of fog. Off until turned on.</li>
 *   <li>A key for /tl. Off, and with no key, until the player sets both.</li>
 *   <li>Important waypoints: each continent's docks, resource island and boss, and everything at
 *       spawn, marked on the continent being stood on. Every continent starts off.</li>
 * </ul>
 */
public class LokaHelperScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int ROW_HEIGHT = 11;
    private static final int CARD_PADDING = 6;
    private static final int CARD_GAP = 6;

    private static final int BUTTON_HEIGHT = 18;
    private static final int CONTENT_TOP = 26;
    private static final int SECTION_GAP = 8;

    private final Screen parent;

    private Button toggleButton;
    private Button inventoryButton;
    private Button hotbarButton;
    private Button coordsButton;
    private Button lavaButton;
    private Button tlButton;
    private Button tlKeyButton;
    private Button importantButton;

    /** Waiting for the key to bind /tl to. */
    private boolean listeningForKey;

    // Where each section's heading goes, worked out with the buttons.
    private int coordsLabelY;
    private int lavaLabelY;
    private int tlLabelY;
    private int importantLabelY;
    private int scaleHintY;

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
        int half = (width - 4) / 2;
        int y = CONTENT_TOP + ROW_HEIGHT;

        // GUI scale.
        toggleButton = addRenderableWidget(Button.builder(toggleLabel(), button -> {
                    config().setGuiScaleEnabled(!config().guiScaleEnabled());
                    refreshLabels();
                })
                .bounds(left, y, width, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + 3;
        inventoryButton = addRenderableWidget(Button.builder(inventoryLabel(), button -> {
                    config().setInventoryGuiScale(GuiScaleController.next(config().inventoryGuiScale()));
                    refreshLabels();
                })
                .bounds(left, y, half, BUTTON_HEIGHT).build());
        hotbarButton = addRenderableWidget(Button.builder(hotbarLabel(), button -> {
                    config().setHotbarGuiScale(GuiScaleController.next(config().hotbarGuiScale()));
                    refreshLabels();
                })
                .bounds(left + half + 4, y, width - half - 4, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + 2;
        scaleHintY = y;
        y += ROW_HEIGHT + SECTION_GAP;

        // Coordinates.
        coordsLabelY = y;
        y += ROW_HEIGHT;
        coordsButton = addRenderableWidget(Button.builder(coordsLabel(), button -> {
                    config().setCoordsHudEnabled(!config().coordsHudEnabled());
                    refreshLabels();
                })
                .bounds(left, y, half, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(positionLabel(),
                        button -> this.minecraft.gui.setScreen(new CoordsHudPositionScreen(this)))
                .bounds(left + half + 4, y, width - half - 4, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + SECTION_GAP;

        // Clear lava.
        lavaLabelY = y;
        y += ROW_HEIGHT;
        lavaButton = addRenderableWidget(Button.builder(lavaLabel(), button -> {
                    config().setClearLava(!config().clearLava());
                    refreshLabels();
                })
                .bounds(left, y, width, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + SECTION_GAP;

        // The /tl key.
        tlLabelY = y;
        y += ROW_HEIGHT;
        tlButton = addRenderableWidget(Button.builder(tlLabel(), button -> {
                    config().setTlKeyEnabled(!config().tlKeyEnabled());
                    refreshLabels();
                })
                .bounds(left, y, half, BUTTON_HEIGHT).build());
        tlKeyButton = addRenderableWidget(Button.builder(tlKeyLabel(), button -> {
                    listeningForKey = !listeningForKey;
                    refreshLabels();
                })
                .bounds(left + half + 4, y, width - half - 4, BUTTON_HEIGHT).build());
        y += BUTTON_HEIGHT + SECTION_GAP;

        // Important waypoints: the places worth a marker, per continent.
        importantLabelY = y;
        y += ROW_HEIGHT;
        importantButton = addRenderableWidget(Button.builder(importantLabel(),
                        button -> this.minecraft.gui.setScreen(new ImportantWaypointsScreen(this)))
                .bounds(left, y, width, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
        refreshLabels();
    }

    /** Rebuilt in place rather than through {@code clearAndInit}, so a press does not rebuild all. */
    private void refreshLabels() {
        toggleButton.setMessage(toggleLabel());
        inventoryButton.setMessage(inventoryLabel());
        hotbarButton.setMessage(hotbarLabel());
        boolean on = config().guiScaleEnabled();
        inventoryButton.active = on;
        hotbarButton.active = on;
        coordsButton.setMessage(coordsLabel());
        lavaButton.setMessage(lavaLabel());
        tlButton.setMessage(tlLabel());
        tlKeyButton.setMessage(tlKeyLabel());
        importantButton.setMessage(importantLabel());
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private Component toggleLabel() {
        return Component.translatable("betterloka.helper.gui_scale_toggle", onOff(config().guiScaleEnabled()));
    }

    private Component inventoryLabel() {
        return Component.translatable("betterloka.helper.inventory_scale",
                GuiScaleController.label(config().inventoryGuiScale()));
    }

    private Component hotbarLabel() {
        return Component.translatable("betterloka.helper.hotbar_scale",
                GuiScaleController.label(config().hotbarGuiScale()));
    }

    private Component coordsLabel() {
        return Component.translatable("betterloka.helper.coords_toggle", onOff(config().coordsHudEnabled()));
    }

    private Component positionLabel() {
        KeyMapping key = BetterLokaClient.coordsPositionKey();
        return key == null || key.isUnbound()
                ? Component.translatable("betterloka.helper.coords_move")
                : Component.translatable("betterloka.helper.coords_move_key", key.getTranslatedKeyMessage());
    }

    private Component importantLabel() {
        int on = 0;
        for (com.betterloka.helper.ImportantPlaces.Region region : com.betterloka.helper.ImportantPlaces.Region.values()) {
            if (config().importantRegionEnabled(region)) {
                on++;
            }
        }
        return Component.translatable("betterloka.helper.important_open",
                Component.literal(on + "/" + com.betterloka.helper.ImportantPlaces.Region.values().length)
                        .withStyle(on > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    private Component lavaLabel() {
        return Component.translatable("betterloka.helper.lava_toggle", onOff(config().clearLava()));
    }

    private Component tlLabel() {
        return Component.translatable("betterloka.helper.tl_toggle", onOff(config().tlKeyEnabled()));
    }

    private Component tlKeyLabel() {
        if (listeningForKey) {
            return Component.translatable("betterloka.helper.tl_press").withStyle(ChatFormatting.YELLOW);
        }
        KeyMapping key = BetterLokaClient.tlKey();
        Component name = key == null || key.isUnbound()
                ? Component.translatable("betterloka.helper.tl_unbound").withStyle(ChatFormatting.GRAY)
                : key.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.AQUA);
        return Component.translatable("betterloka.helper.tl_key", name);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (listeningForKey) {
            listeningForKey = false;
            KeyMapping key = BetterLokaClient.tlKey();
            if (key != null) {
                // Escape clears it: no key is how it starts, and how it can always be put back.
                key.setKey(input.key() == InputConstants.KEY_ESCAPE
                        ? InputConstants.UNKNOWN : InputConstants.getKey(input));
                KeyMapping.resetMapping();
                this.minecraft.options.save();
            }
            refreshLabels();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        boolean on = config().guiScaleEnabled();
        inventoryButton.active = on;
        hotbarButton.active = on;

        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        context.text(this.font, Component.translatable("betterloka.helper.gui_scale"), left, CONTENT_TOP,
                GuiTheme.MUTED);
        context.text(this.font, this.font.plainSubstrByWidth(Component.translatable(on
                        ? "betterloka.helper.gui_scale_hint" : "betterloka.helper.gui_scale_off").getString(),
                contentWidth()), left, scaleHintY + 1, 0xFF6E6E7A);

        context.text(this.font, Component.translatable("betterloka.helper.coords"), left, coordsLabelY,
                GuiTheme.MUTED);
        context.text(this.font, Component.translatable("betterloka.helper.lava"), left, lavaLabelY,
                GuiTheme.MUTED);
        context.text(this.font, Component.translatable("betterloka.helper.tl"), left, tlLabelY, GuiTheme.MUTED);
        context.text(this.font, Component.translatable("betterloka.helper.important"), left, importantLabelY,
                GuiTheme.MUTED);
    }

    @Override
    public void onClose() {
        listeningForKey = false;
        this.minecraft.gui.setScreen(parent);
    }

    // --- for the dev harness ---

    /** Turns the options the harness is checking on or off, as their buttons would. */
    public void devSet(boolean coords, boolean lava, boolean tl) {
        config().setCoordsHudEnabled(coords);
        config().setClearLava(lava);
        config().setTlKeyEnabled(tl);
        refreshLabels();
    }

    /** Presses the key button, then a key, as a player binding /tl would. */
    public void devBindTl(int keyCode) {
        listeningForKey = true;
        keyPressed(new KeyEvent(keyCode, 0, 0));
    }
}
