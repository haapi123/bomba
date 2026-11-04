package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.helper.CoordsHud;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Where the coordinates go: the box is drawn where it is and dragged where it should be, or nudged
 * with the arrow keys. It snaps to the edges and the middle when it comes close to them.
 *
 * <p>Opened with P, or from Loka Helper.
 */
public class CoordsHudPositionScreen extends Screen {
    /** How close to an edge or the middle the box has to come to snap to it. */
    private static final int SNAP = 6;

    private final Screen parent;

    private int boxX;
    private int boxY;
    private boolean dragging;
    private double grabX;
    private double grabY;

    public CoordsHudPositionScreen(Screen parent) {
        super(Component.translatable("betterloka.helper.coords_position"));
        this.parent = parent;
    }

    private List<CoordsHud.Line> lines() {
        return this.minecraft != null && this.minecraft.player != null
                ? CoordsHud.lines(this.minecraft.player) : CoordsHud.sample();
    }

    private int boxWidth() {
        return CoordsHud.width(this.font, lines());
    }

    private int boxHeight() {
        return CoordsHud.height(lines());
    }

    @Override
    protected void init() {
        int[] at = CoordsHud.place(BetterLokaClient.config().coordsHudX(), BetterLokaClient.config().coordsHudY(),
                this.width, this.height, boxWidth(), boxHeight());
        boxX = at[0];
        boxY = at[1];

        int buttonWidth = 90;
        int y = this.height / 2 + 30;
        addRenderableWidget(Button.builder(Component.translatable("betterloka.helper.coords_reset"), button -> {
            int[] corner = CoordsHud.place(0, 0, this.width, this.height, boxWidth(), boxHeight());
            boxX = corner[0];
            boxY = corner[1];
            savePosition();
        }).bounds(this.width / 2 - buttonWidth - 2, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 + 2, y, buttonWidth, 20).build());
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
        BetterLokaClient.config().setCoordsHudPosition(fractions[0], fractions[1]);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        // Guides through the middle, so centring is easy to see.
        context.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x30FFFFFF);
        context.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x30FFFFFF);

        context.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 30, GuiTheme.TEXT);
        context.centeredText(this.font, Component.translatable("betterloka.helper.coords_drag"),
                this.width / 2, this.height / 2 - 16, GuiTheme.MUTED);
        if (!BetterLokaClient.config().coordsHudEnabled()) {
            context.centeredText(this.font, Component.translatable("betterloka.helper.coords_off_note"),
                    this.width / 2, this.height / 2 - 4, GuiTheme.LIVE);
        }

        List<CoordsHud.Line> lines = lines();
        CoordsHud.draw(context, this.font, lines, boxX, boxY);
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
}
