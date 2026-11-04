package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.helper.CoordsHud;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Where the coordinates go and how big they are: the box is drawn where it is and dragged where it
 * should be, or nudged with the arrow keys. It snaps to the edges and the middle when it comes close
 * to them. The buttons under the title turn it on or off and make it smaller or bigger — so does the
 * mouse wheel over the box, and the minus and plus keys.
 *
 * <p>Opened with P, or from Loka Helper.
 */
public class CoordsHudPositionScreen extends Screen {
    /** How close to an edge or the middle the box has to come to snap to it. */
    private static final int SNAP = 6;
    /** The row of controls: as wide as the two buttons at the bottom of it. */
    private static final int ROW_WIDTH = 184;
    private static final int STEP_BUTTON = 20;
    /** The keypad's minus, which {@link InputConstants} names no constant for (GLFW_KEY_KP_SUBTRACT). */
    private static final int KEY_KP_SUBTRACT = 333;

    private final Screen parent;

    private int boxX;
    private int boxY;
    private boolean dragging;
    private double grabX;
    private double grabY;

    private Button toggleButton;
    private Button smallerButton;
    private Button biggerButton;

    public CoordsHudPositionScreen(Screen parent) {
        super(Component.translatable("betterloka.helper.coords_position"));
        this.parent = parent;
    }

    private static BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    private List<CoordsHud.Line> lines() {
        return this.minecraft != null && this.minecraft.player != null
                ? CoordsHud.lines(this.minecraft.player) : CoordsHud.sample();
    }

    private float scale() {
        return CoordsHud.scale(config().coordsHudScale());
    }

    private int boxWidth() {
        return CoordsHud.width(this.font, lines(), scale());
    }

    private int boxHeight() {
        return CoordsHud.height(lines(), scale());
    }

    private int rowLeft() {
        return this.width / 2 - ROW_WIDTH / 2;
    }

    @Override
    protected void init() {
        placeFromConfig();

        int left = rowLeft();
        int y = this.height / 2 - 14;
        toggleButton = addRenderableWidget(Button.builder(toggleLabel(), button -> {
            config().setCoordsHudEnabled(!config().coordsHudEnabled());
            refreshLabels();
        }).bounds(left, y, ROW_WIDTH, 20).build());

        y += 24;
        smallerButton = addRenderableWidget(Button.builder(Component.literal("-"), button -> resize(-1))
                .bounds(left, y, STEP_BUTTON, 20).build());
        biggerButton = addRenderableWidget(Button.builder(Component.literal("+"), button -> resize(1))
                .bounds(left + ROW_WIDTH - STEP_BUTTON, y, STEP_BUTTON, 20).build());

        y += 24;
        int half = (ROW_WIDTH - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("betterloka.helper.coords_reset"), button -> {
            int[] corner = CoordsHud.place(0, 0, this.width, this.height, boxWidth(), boxHeight());
            boxX = corner[0];
            boxY = corner[1];
            savePosition();
        }).bounds(left, y, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + ROW_WIDTH - half, y, half, 20).build());
        refreshLabels();
    }

    /** Puts the box where the saved fractions say, at the size it is now. */
    private void placeFromConfig() {
        int[] at = CoordsHud.place(config().coordsHudX(), config().coordsHudY(),
                this.width, this.height, boxWidth(), boxHeight());
        boxX = at[0];
        boxY = at[1];
    }

    private void refreshLabels() {
        toggleButton.setMessage(toggleLabel());
        int percent = config().coordsHudScale();
        smallerButton.active = percent > BetterLokaConfig.COORDS_SCALE_MIN;
        biggerButton.active = percent < BetterLokaConfig.COORDS_SCALE_MAX;
    }

    private static Component toggleLabel() {
        boolean on = config().coordsHudEnabled();
        return Component.translatable("betterloka.helper.coords_onoff",
                Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off")
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }

    /**
     * One step smaller or bigger. The box keeps its place the way a resized screen keeps it: the
     * same fraction across, so one against an edge stays against that edge.
     */
    private void resize(int steps) {
        savePosition();
        config().setCoordsHudScale(config().coordsHudScale() + steps * BetterLokaConfig.COORDS_SCALE_STEP);
        placeFromConfig();
        refreshLabels();
    }

    private boolean overBox(double x, double y) {
        return x >= boxX && x < boxX + boxWidth() && y >= boxY && y < boxY + boxHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && overBox(click.x(), click.y())) {
            dragging = true;
            grabX = click.x() - boxX;
            grabY = click.y() - boxY;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (dragging) {
            moveTo((int) Math.round(click.x() - grabX), (int) Math.round(click.y() - grabY), true);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging) {
            dragging = false;
            savePosition();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0 && overBox(mouseX, mouseY)) {
            resize(verticalAmount > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int step = input.hasShiftDown() ? 10 : 1;
        int key = input.key();
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT
                || key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            int dx = key == InputConstants.KEY_LEFT ? -step : key == InputConstants.KEY_RIGHT ? step : 0;
            int dy = key == InputConstants.KEY_UP ? -step : key == InputConstants.KEY_DOWN ? step : 0;
            moveTo(boxX + dx, boxY + dy, false);
            savePosition();
            return true;
        }
        if (key == InputConstants.KEY_MINUS || key == KEY_KP_SUBTRACT) {
            resize(-1);
            return true;
        }
        if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
            resize(1);
            return true;
        }
        return super.keyPressed(input);
    }

    /** Moves the box, keeping it on screen and, when dragged, snapping it to edges and the middle. */
    private void moveTo(int x, int y, boolean snap) {
        int width = boxWidth();
        int height = boxHeight();
        if (snap) {
            x = snapped(x, width, this.width);
            y = snapped(y, height, this.height);
        }
        boxX = Math.max(2, Math.min(this.width - width - 2, x));
        boxY = Math.max(2, Math.min(this.height - height - 2, y));
    }

    private static int snapped(int position, int size, int screen) {
        int far = screen - size - 2;
        int middle = (screen - size) / 2;
        if (Math.abs(position - 2) <= SNAP) {
            return 2;
        }
        if (Math.abs(position - far) <= SNAP) {
            return far;
        }
        if (Math.abs(position - middle) <= SNAP) {
            return middle;
        }
        return position;
    }

    private void savePosition() {
        double[] fractions = CoordsHud.fractions(boxX, boxY, this.width, this.height, boxWidth(), boxHeight());
        config().setCoordsHudPosition(fractions[0], fractions[1]);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        // Guides through the middle, so centring is easy to see.
        context.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x30FFFFFF);
        context.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x30FFFFFF);

        context.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 44, GuiTheme.TEXT);
        context.centeredText(this.font, Component.translatable("betterloka.helper.coords_drag"),
                this.width / 2, this.height / 2 - 30, GuiTheme.MUTED);

        // The size, between its minus and plus.
        int sizeY = this.height / 2 + 10 + 6;
        context.centeredText(this.font,
                Component.translatable("betterloka.helper.coords_size", config().coordsHudScale()),
                this.width / 2, sizeY, GuiTheme.TEXT);

        boolean on = config().coordsHudEnabled();
        List<CoordsHud.Line> lines = lines();
        CoordsHud.draw(context, this.font, lines, boxX, boxY, scale());
        if (!on) {
            // Still here to be placed, but plainly not on: greyed over. A stratum up, or the text —
            // which is drawn after the fills of its own stratum — would come through the grey.
            context.nextStratum();
            context.fill(boxX, boxY, boxX + boxWidth(), boxY + boxHeight(), 0xA0202020);
        }
        boolean hovered = dragging || overBox(mouseX, mouseY);
        FilterMenu.outline(context, boxX - 1, boxY - 1, boxWidth() + 2, boxHeight() + 2,
                hovered ? 0xFFFFD46B : 0x80FFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        savePosition();
        this.minecraft.gui.setScreen(parent);
    }

    // --- for the dev harness ---

    public int[] devBox() {
        return new int[]{boxX + boxWidth() / 2, boxY + boxHeight() / 2};
    }

    /** Presses minus or plus, as a player resizing the box would. */
    public void devResize(int steps) {
        resize(steps);
    }
}
