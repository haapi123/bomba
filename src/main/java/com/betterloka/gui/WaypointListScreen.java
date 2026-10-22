package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.map.Continent;
import com.betterloka.map.MapService;
import com.betterloka.map.MapTerritory;
import com.betterloka.map.Waypoint;
import com.betterloka.map.WaypointHud;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every waypoint the player has set, on every continent, with what can be done to each.
 *
 * <p>Each card says what the waypoint is called, which territory it stands in and who holds that
 * now — read from the live map, so a hex captured since the waypoint was set shows its new owner —
 * the coordinates, the continent, and how far it is when the player is standing on that continent.
 * Copy puts the coordinates on the clipboard in the form a command takes; Delete removes the
 * waypoint for good, after a second press.
 *
 * <p>The buttons on the cards are painted rather than added as widgets: the cards scroll, and a
 * widget per card would have to be moved and hidden by hand on every frame.
 */
public class WaypointListScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 440;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;
    private static final int SECTION_HEIGHT = 14;
    private static final int SWATCH = 7;
    private static final int ACTION_WIDTH = 58;
    private static final int ACTION_GAP = 3;

    private static final int CONTROL_ROW_Y = 26;
    private static final int CONTROL_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = CONTROL_ROW_Y + CONTROL_ROW_HEIGHT + 16;

    /** A delete has to be pressed twice within this long; the first press only arms it. */
    static final long CONFIRM_MILLIS = 3000;

    private final Screen parent;

    /**
     * The territory selected on the map when this was opened, as the waypoint it would make, or
     * {@code null}: what the Add button offers.
     */
    private final Waypoint candidate;

    private final ScrollPanel scrollPanel = new ScrollPanel();

    /** One painted button, as drawn this frame, so a click can find it. */
    private record Action(int x, int y, int width, Runnable run) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + MapStyle.PIN_BUTTON_HEIGHT;
        }
    }

    private final List<Action> actions = new ArrayList<>();

    // Which card's button last said something, and when: "Copied", or a delete waiting for its second press.
    private String copiedKey;
    private long copiedAt;
    private String armedKey;
    private long armedAt;

    private Button addButton;

    public WaypointListScreen(Screen parent, Waypoint candidate) {
        super(Component.translatable("betterloka.waypoints.title"));
        this.parent = parent;
        this.candidate = candidate;
    }

    private static MapService map() {
        return BetterLokaClient.map();
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int viewportBottom() {
        return this.height - 34;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        scrollPanel.setViewport(left, VIEWPORT_TOP, width, Math.max(20, viewportBottom() - VIEWPORT_TOP));

        // Adding lives where the old button did its job: on whatever was selected on the map.
        if (candidate != null) {
            addButton = addRenderableWidget(Button.builder(addLabel(), button -> addCandidate())
                    .bounds(left, CONTROL_ROW_Y, width, CONTROL_ROW_HEIGHT).build());
            addButton.active = !alreadyAdded();
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
    }

    private boolean alreadyAdded() {
        return candidate != null && map().isSet(candidate.world(), candidate.label());
    }

    private Component addLabel() {
        return alreadyAdded()
                ? Component.translatable("betterloka.waypoints.already", candidate.label())
                : Component.translatable("betterloka.waypoints.add", candidate.label());
    }

    private void addCandidate() {
        if (candidate == null || alreadyAdded()) {
            return;
        }
        map().add(candidate);
        addButton.setMessage(addLabel());
        addButton.active = false;
    }

    /** What identifies a waypoint: its world and its name, which together are unique. */
    private static String key(Waypoint waypoint) {
        return waypoint.world() + "\u0000" + waypoint.label();
    }

    private void copy(Waypoint waypoint) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(waypoint.coordinates());
        copiedKey = key(waypoint);
        copiedAt = System.currentTimeMillis();
    }

    /** The first press arms the delete; a second within a few seconds removes the waypoint. */
    private void delete(Waypoint waypoint) {
        String key = key(waypoint);
        long now = System.currentTimeMillis();
        if (key.equals(armedKey) && now - armedAt < CONFIRM_MILLIS) {
            map().remove(waypoint);
            armedKey = null;
            if (addButton != null) {
                addButton.setMessage(addLabel());
                addButton.active = !alreadyAdded();
            }
            return;
        }
        armedKey = key;
        armedAt = now;
    }

    // --- input ---

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (scrollPanel.mouseClicked(click.x(), click.y())) {
            return true;
        }
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Action action : List.copyOf(actions)) {
                if (action.contains(click.x(), click.y())) {
                    action.run().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    // --- drawing ---

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();
        List<Waypoint> waypoints = MapService.forListing(map().waypoints());

        if (candidate == null) {
            context.centeredText(this.font, Component.translatable("betterloka.waypoints.hint"),
                    this.width / 2, CONTROL_ROW_Y + 4, GuiTheme.MUTED);
        }
        context.text(this.font, Component.translatable("betterloka.waypoints.count", waypoints.size()),
                left, VIEWPORT_TOP - 12, GuiTheme.MUTED);

        actions.clear();
        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int end = waypoints.isEmpty()
                ? renderEmpty(context, left, y, cardWidth)
                : renderCards(context, left, y, cardWidth, waypoints, mouseX, mouseY);
        context.disableScissor();

        scrollPanel.setContentHeight(end - y);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /** @return the y just past the last card. */
    private int renderCards(GuiGraphicsExtractor context, int left, int y, int width,
                            List<Waypoint> waypoints, int mouseX, int mouseY) {
        String section = null;
        for (Waypoint waypoint : waypoints) {
            String world = waypoint.world().toLowerCase(Locale.ROOT);
            if (!world.equals(section)) {
                section = world;
                long count = waypoints.stream().filter(w -> w.world().equalsIgnoreCase(waypoint.world())).count();
                context.text(this.font, Component.translatable("betterloka.waypoints.section",
                        continentName(waypoint), count), left + 1, y + 3, GuiTheme.ACCENT);
                y += SECTION_HEIGHT;
            }
            y = renderCard(context, left, y, width, waypoint, mouseX, mouseY) + CARD_GAP;
        }
        return y;
    }

    private int renderCard(GuiGraphicsExtractor context, int left, int y, int width, Waypoint waypoint,
                           int mouseX, int mouseY) {
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2 + MapStyle.PIN_BUTTON_HEIGHT;
        GuiTheme.panel(context, left, y, width, height);
        int colour = 0xFF000000 | waypoint.color();
        context.fill(left, y, left + 2, y + height, colour);

        int textX = left + CARD_PADDING + 2;
        int right = left + width - CARD_PADDING;
        int row = y + CARD_PADDING;

        // What it is, and on which continent.
        context.fill(textX, row, textX + SWATCH, row + SWATCH, colour);
        String continent = continentName(waypoint);
        int labelRoom = width - CARD_PADDING * 2 - SWATCH - 8 - this.font.width(continent) - 8;
        context.text(this.font, this.font.plainSubstrByWidth(waypoint.label(), labelRoom),
                textX + SWATCH + 4, row, GuiTheme.TEXT);
        context.text(this.font, continent, right - this.font.width(continent), row, GuiTheme.ACCENT);

        // Where it is: the territory it stands in and who holds that today, and how far away.
        row += ROW_HEIGHT;
        Component distance = distance(waypoint);
        int distanceWidth = this.font.width(distance);
        String where = where(waypoint).getString();
        context.text(this.font, this.font.plainSubstrByWidth(where, width - CARD_PADDING * 2 - distanceWidth - 12),
                textX, row, GuiTheme.MUTED);
        context.text(this.font, distance, right - distanceWidth, row, GuiTheme.LIVE);

        // The coordinates, and what can be done with them.
        row += ROW_HEIGHT + 1;
        context.text(this.font, Component.translatable("betterloka.waypoints.coords",
                        Math.round(waypoint.x()), Math.round(waypoint.y()), Math.round(waypoint.z())),
                textX, row + 3, GuiTheme.TEXT);

        String key = key(waypoint);
        long now = System.currentTimeMillis();
        boolean armed = key.equals(armedKey) && now - armedAt < CONFIRM_MILLIS;
        boolean copied = key.equals(copiedKey) && now - copiedAt < MapStyle.COPIED_NOTICE_MILLIS;

        int deleteX = right - ACTION_WIDTH;
        int copyX = deleteX - ACTION_GAP - ACTION_WIDTH;
        boolean visible = row >= scrollPanel.viewportTop()
                && row + MapStyle.PIN_BUTTON_HEIGHT <= scrollPanel.viewportBottom();
        MapStyle.wideButton(context, this.font, copyX, row, ACTION_WIDTH,
                Component.translatable(copied ? "betterloka.map.copied" : "betterloka.map.copy_here"),
                visible && over(mouseX, mouseY, copyX, row), copied);
        MapStyle.wideButton(context, this.font, deleteX, row, ACTION_WIDTH,
                Component.translatable(armed ? "betterloka.waypoints.confirm" : "betterloka.waypoints.delete"),
                visible && over(mouseX, mouseY, deleteX, row), false, armed);
        // A button scrolled half out of the viewport is not there to be pressed.
        if (visible) {
            actions.add(new Action(copyX, row, ACTION_WIDTH, () -> copy(waypoint)));
            actions.add(new Action(deleteX, row, ACTION_WIDTH, () -> delete(waypoint)));
        }
        return y + height;
    }

    private static boolean over(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + ACTION_WIDTH && mouseY >= y && mouseY < y + MapStyle.PIN_BUTTON_HEIGHT;
    }

    private static String continentName(Waypoint waypoint) {
        Continent continent = Continent.ofWorld(waypoint.world());
        return continent != null ? continent.displayName()
                : Component.translatable("betterloka.waypoints.unknown_world", waypoint.world()).getString();
    }

    /** The territory the waypoint stands in, from the live map, and who holds it now. */
    private static Component where(Waypoint waypoint) {
        Continent continent = Continent.ofWorld(waypoint.world());
        MapTerritory territory = continent == null ? null
                : BetterLokaClient.mapData().snapshot(continent).index().at(waypoint.x(), waypoint.z());
        if (territory == null) {
            return Component.translatable("betterloka.waypoints.outside");
        }
        // Named after the territory already, which is the usual case: only who holds it is news.
        if (waypoint.label().startsWith(territory.label())) {
            return territory.neutral()
                    ? Component.translatable("betterloka.waypoints.owner_neutral")
                    : Component.translatable("betterloka.waypoints.owner_held", territory.owner());
        }
        return territory.neutral()
                ? Component.translatable("betterloka.waypoints.where_neutral", territory.label())
                : Component.translatable("betterloka.waypoints.where_held", territory.label(), territory.owner());
    }

    /** How far, when the player is on the same continent; otherwise that it is somewhere else. */
    private Component distance(Waypoint waypoint) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return Component.literal("—");
        }
        if (!WaypointHud.sameWorld(this.minecraft, waypoint)) {
            return Component.translatable("betterloka.waypoints.other_continent");
        }
        double x = this.minecraft.player.getX();
        double z = this.minecraft.player.getZ();
        return Component.translatable("betterloka.waypoints.distance",
                String.format(Locale.ROOT, "%.0f", waypoint.distanceTo(x, z)), waypoint.chunksTo(x, z));
    }

    private int renderEmpty(GuiGraphicsExtractor context, int left, int y, int width) {
        List<String> lines = wrap(Component.translatable("betterloka.waypoints.empty").getString(),
                width - CARD_PADDING * 2);
        int height = CARD_PADDING * 2 + ROW_HEIGHT * Math.max(1, lines.size());
        GuiTheme.panel(context, left, y, width, height);
        int row = y + CARD_PADDING;
        for (String line : lines) {
            context.text(this.font, line, left + CARD_PADDING, row, GuiTheme.MUTED);
            row += ROW_HEIGHT;
        }
        return y + height + CARD_GAP;
    }

    /** Breaks text into lines no wider than {@code maxWidth}, between words. */
    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.font.width(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    // --- development ---

    /** Development only: the painted buttons as drawn last frame, as {x, y} of their middles. */
    public List<int[]> devActions() {
        List<int[]> points = new ArrayList<>();
        for (Action action : actions) {
            points.add(new int[] {action.x() + action.width() / 2, action.y() + MapStyle.PIN_BUTTON_HEIGHT / 2});
        }
        return points;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
