package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.helper.ImportantPlaces;
import com.betterloka.helper.ImportantWaypointsHud;
import com.betterloka.helper.PlaceIcons;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Loka Helper's important waypoints: one card per continent with its switch and its places, each
 * with the picture its marker carries. Every continent starts off; a marker only ever shows on the
 * continent the player is standing on.
 */
public class ImportantWaypointsScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 340;
    private static final int TOP = 40;
    private static final int HEADER_HEIGHT = 22;
    private static final int ROW_HEIGHT = 18;
    private static final int CARD_GAP = 6;
    private static final int TOGGLE_WIDTH = 46;
    private static final int TOGGLE_HEIGHT = 14;

    private final Screen parent;
    private final ScrollPanel scroll = new ScrollPanel();
    /** Where each continent's switch was drawn this frame, for clicks. */
    private final List<int[]> toggleBounds = new ArrayList<>();
    private final List<ImportantPlaces.Region> toggleRegions = new ArrayList<>();

    public ImportantWaypointsScreen(Screen parent) {
        super(Component.translatable("betterloka.helper.important"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int bottom() {
        return this.height - 32;
    }

    @Override
    protected void init() {
        scroll.setViewport(contentLeft(), TOP, contentWidth(), bottom() - TOP);
        int half = 98;
        addRenderableWidget(Button.builder(Component.translatable("betterloka.helper.important_all_off"), button -> {
                    for (ImportantPlaces.Region region : ImportantPlaces.Region.values()) {
                        BetterLokaClient.config().setImportantRegionEnabled(region, false);
                    }
                })
                .bounds(this.width / 2 - half - 2, this.height - 26, half, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 + 2, this.height - 26, half, 20).build());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (scroll.mouseClicked(click.x(), click.y())) {
            return true;
        }
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.y() >= TOP && click.y() < bottom()) {
            for (int i = 0; i < toggleBounds.size(); i++) {
                int[] bounds = toggleBounds.get(i);
                if (click.x() >= bounds[0] && click.x() < bounds[0] + bounds[2]
                        && click.y() >= bounds[1] && click.y() < bounds[1] + bounds[3]) {
                    toggle(toggleRegions.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private static void toggle(ImportantPlaces.Region region) {
        BetterLokaClient.config().setImportantRegionEnabled(region,
                !BetterLokaClient.config().importantRegionEnabled(region));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scroll.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return scroll.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scroll.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);

        ImportantPlaces.Region here = ImportantWaypointsHud.currentRegion(this.minecraft);
        Component where = here == null
                ? Component.translatable("betterloka.helper.important_unknown").withStyle(ChatFormatting.GRAY)
                : Component.translatable("betterloka.helper.important_here",
                Component.literal(here.displayName()).withStyle(ChatFormatting.GOLD));
        context.centeredText(this.font, where, this.width / 2, 24, GuiTheme.MUTED);

        int left = contentLeft();
        int width = scroll.contentWidth();
        toggleBounds.clear();
        toggleRegions.clear();
        context.enableScissor(left, TOP, left + contentWidth(), bottom());
        int top = scroll.contentTop();
        int y = top;
        for (ImportantPlaces.Region region : ImportantPlaces.Region.values()) {
            y = drawRegion(context, region, region == here, left, y, width, mouseX, mouseY);
        }
        context.disableScissor();
        scroll.setContentHeight(y - top);
        scroll.render(context, mouseX, mouseY);
    }

    private int drawRegion(GuiGraphicsExtractor context, ImportantPlaces.Region region, boolean here, int x, int y,
                           int width, int mouseX, int mouseY) {
        List<ImportantPlaces.Place> places = ImportantPlaces.on(region);
        boolean on = BetterLokaClient.config().importantRegionEnabled(region);
        int height = HEADER_HEIGHT + places.size() * ROW_HEIGHT + 4;
        MapStyle.card(context, x, y, width, height, on ? 0xFF5FD37A : 0xFF2C2C38);

        // The continent, a note when the player is on it, and its switch.
        context.text(this.font, Component.literal(region.displayName()).withStyle(ChatFormatting.BOLD),
                x + 8, y + 7, on ? GuiTheme.TEXT : GuiTheme.MUTED);
        if (here) {
            Component note = Component.translatable("betterloka.helper.important_you");
            int noteX = x + 14 + this.font.width(Component.literal(region.displayName()).withStyle(ChatFormatting.BOLD));
            GuiTheme.chip(context, noteX, y + 4, this.font.width(note) + 8, 13, GuiTheme.LIVE);
            context.text(this.font, note, noteX + 4, y + 7, GuiTheme.LIVE);
        }
        int toggleX = x + width - TOGGLE_WIDTH - 6;
        int toggleY = y + 4;
        boolean hovered = mouseX >= toggleX && mouseX < toggleX + TOGGLE_WIDTH && mouseY >= toggleY
                && mouseY < toggleY + TOGGLE_HEIGHT && mouseY >= TOP && mouseY < bottom();
        drawSwitch(context, toggleX, toggleY, on, hovered);
        toggleBounds.add(new int[]{toggleX, toggleY, TOGGLE_WIDTH, TOGGLE_HEIGHT});
        toggleRegions.add(region);
        context.fill(x + 6, y + HEADER_HEIGHT - 2, x + width - 6, y + HEADER_HEIGHT - 1, 0xFF33333F);

        // Its places, each with the picture its marker carries.
        int rowY = y + HEADER_HEIGHT;
        for (ImportantPlaces.Place place : places) {
            PlaceIcons.draw(context, place.icon(), x + 8, rowY + 1, 16);
            context.text(this.font, Component.translatable(place.nameKey()), x + 30, rowY + 5,
                    on ? GuiTheme.TEXT : GuiTheme.MUTED);
            String coords = place.x() + " " + place.y() + " " + place.z();
            context.text(this.font, coords, x + width - 8 - this.font.width(coords), rowY + 5, GuiTheme.MUTED);
            rowY += ROW_HEIGHT;
        }
        return y + height + CARD_GAP;
    }

    /** A sliding switch: green and to the right when on. */
    private void drawSwitch(GuiGraphicsExtractor context, int x, int y, boolean on, boolean hovered) {
        int track = on ? 0xFF2E7D45 : 0xFF3A3A46;
        context.fill(x + 1, y, x + TOGGLE_WIDTH - 1, y + TOGGLE_HEIGHT, track);
        context.fill(x, y + 1, x + TOGGLE_WIDTH, y + TOGGLE_HEIGHT - 1, track);
        if (hovered) {
            context.fill(x + 1, y, x + TOGGLE_WIDTH - 1, y + 1, 0xFFFFFFFF);
            context.fill(x + 1, y + TOGGLE_HEIGHT - 1, x + TOGGLE_WIDTH - 1, y + TOGGLE_HEIGHT, 0xFFFFFFFF);
        }
        int knob = TOGGLE_HEIGHT - 4;
        int knobX = on ? x + TOGGLE_WIDTH - knob - 2 : x + 2;
        context.fill(knobX, y + 2, knobX + knob, y + 2 + knob, on ? 0xFFE8FFE8 : 0xFFB0B0BC);
        Component label = Component.translatable(on ? "betterloka.toggle.on" : "betterloka.toggle.off");
        int labelX = on ? x + 4 : x + TOGGLE_WIDTH - 4 - this.font.width(label);
        context.text(this.font, label, labelX, y + 3, on ? 0xFFFFFFFF : 0xFF9A9AA8);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    // --- for the dev harness ---

    public void devToggle(ImportantPlaces.Region region) {
        toggle(region);
    }
}
