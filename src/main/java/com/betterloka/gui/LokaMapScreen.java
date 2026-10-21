package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.map.Continent;
import com.betterloka.map.MapDataStore;
import com.betterloka.map.MapHighlight;
import com.betterloka.map.MapSearch;
import com.betterloka.map.MapTerrain;
import com.betterloka.map.MapTerritory;
import com.betterloka.map.MapTown;
import com.betterloka.map.TerrainDownload;
import com.betterloka.map.Waypoint;
import com.betterloka.map.ZoomAnimation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loka's map, brought into the game: its ground, its outlines, its markers.
 *
 * <p>It opens on the whole island and is dragged and scrolled with the mouse, the way its website is
 * used. Loka's own ground is drawn underneath and the claims are washed over it, so the desert, the
 * lava and the coastline stay readable through the colours; the borders, not the fills, are what say
 * who holds what.
 *
 * <p>Everything about how it looks lives in {@link MapStyle}.
 */
public class LokaMapScreen extends Screen {
    /** Below the toast strip, which owns the top right and would clip the last tabs. */
    private static final int TAB_ROW_Y = 36;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int MAP_TOP = TAB_ROW_Y + TAB_ROW_HEIGHT + 6;
    private static final int MAP_MARGIN = 8;
    private static final int MAX_CONTENT_WIDTH = 620;

    /** Guard against a stray drag being read as a click on a territory. */
    private static final int DRAG_SLOP = 2;

    /** Where each continent was left: reopening the map should not undo the last pan and zoom. */
    private record View(double centerX, double centerZ, double blocksPerPixel) {
    }

    private static final Map<Continent, View> VIEWS = new EnumMap<>(Continent.class);

    /** The continent last looked at, so reopening the map lands where it was left. */
    private static Continent lastContinent = Continent.KALROS;

    private final Screen parent;

    private Continent continent = lastContinent;

    /**
     * What this frame is drawing, taken from the store once per frame.
     *
     * <p>Read rather than owned: the store keeps it current in the background, so opening the
     * screen costs nothing and a territory captured while it was shut is simply already there.
     */
    private MapDataStore.Snapshot snapshot = MapDataStore.Snapshot.empty();

    /**
     * The selection, held as a number rather than an object.
     *
     * <p>A refresh replaces every territory record, so a held reference would go on describing the
     * state before the capture — the one thing this screen exists to show.
     */
    private String selectedNumber;
    private MapTerritory selected;
    private MapTerritory hovered;

    /**
     * The view: which world point is in the middle, and how tight the zoom is.
     *
     * <p>A zoom used to be one instant jump per click. It now travels there over a fraction of a
     * second about the point it was aimed at, which is {@link ZoomAnimation}'s whole job.
     */
    private final ZoomAnimation view = new ZoomAnimation(MapStyle.MAX_BLOCKS_PER_PIXEL, 0, 0);
    private boolean centred;

    /** The zoom being drawn this frame, read once so a frame cannot draw two of them. */
    private double blocksPerPixel = MapStyle.MAX_BLOCKS_PER_PIXEL;
    private long lastFrameNanos;

    /**
     * The view's origin rounded to a whole pixel, and the fraction left over.
     *
     * <p>Everything on the map is placed against the rounded origin and the whole layer is then
     * shifted by the fraction, which is what makes a drag glide. Placing each marker by rounding its
     * own distance from a moving origin — which is what this used to do — rounds every one of them
     * differently on every frame, so they crawl a pixel at a time and at different moments from each
     * other and from the ground beneath.
     */
    private int originPixelX;
    private int originPixelY;
    private float originFracX;
    private float originFracY;

    private boolean dragging;
    private double dragStartX;
    private double dragStartZ;
    private double dragOriginX;
    private double dragOriginY;

    /** The spot a right click asked about: somewhere on the ground, not necessarily a territory. */
    private boolean pinned;
    private double pinWorldX;
    private double pinWorldZ;
    private int pinScreenX;
    private int pinScreenY;
    private long pinnedAt;
    private long copiedAt;

    // Where the map was drawn this frame, so a click can be turned back into world coordinates.
    private int mapX;
    private int mapY;
    private int mapWidth;
    private int mapHeight;

    // --- search ---

    /**
     * What a search lit up: a hex by its number or a town by its name.
     *
     * <p>Held as a key rather than as territory records, for the reason the selection is: a capture
     * replaces the records, and the light should follow the hex or the town, not the old copy.
     */
    private record Found(Continent continent, MapSearch.Kind kind, String key, double x, double z,
                         long at) {
    }

    private static final int SEARCH_MAX_WIDTH = 170;
    private static final int SEARCH_HEIGHT = 16;
    private static final int SEARCH_INSET = 6;

    /** How much of the window a found hex is made to fill, and how much a found town's ground. */
    private static final double SEARCH_FILL_ONE = 0.4;
    private static final double SEARCH_FILL_TOWN = 0.75;

    /** The zoom for a town that holds no ground and is only a marker: close enough to see where. */
    private static final double SEARCH_MARKER_ZOOM = 2.0;

    /** How long the flight to a result takes, as a time constant: slower than a zoom click. */
    private static final double SEARCH_FLY_TAU_SECONDS = 0.09;

    /** The light pulses for this long so the eye finds it, then rests at a gentle wash. */
    private static final double SEARCH_PULSE_SECONDS = 2.4;
    private static final double SEARCH_PULSE_PERIOD = 0.8;
    private static final double SEARCH_REST_STRENGTH = 0.6;

    /** How long "not found" stays up after Enter found nothing. */
    private static final long SEARCH_MISS_MILLIS = 4000;

    private EditBox searchField;
    private Button waypointButton;
    private MapSearch search;
    private MapDataStore.Snapshot searchBuiltFrom;
    private Found found;
    private final MapHighlight highlight = new MapHighlight();
    private String missedQuery;
    private long missedAt;
    /** The query the light came from, so the line under the field stops offering what is done. */
    private String searchedFor;

    // What the line under the field says while typing, worked out once per change rather than per frame.
    private String suggestedFor;
    private MapDataStore.Snapshot suggestedFrom;
    private MapSearch.Hit suggestion;

    public LokaMapScreen(Screen parent) {
        super(Component.translatable("betterloka.module.loka_map"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 20, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    /** Where the map goes, worked out the same way for placing widgets on it and for drawing it. */
    private void layoutMap() {
        mapX = contentLeft() + MAP_MARGIN;
        mapY = MAP_TOP;
        mapWidth = contentWidth() - MAP_MARGIN * 2;
        mapHeight = Math.max(60, this.height - 32 - MAP_TOP);
    }

    @Override
    protected void init() {
        int width = contentWidth();
        int left = contentLeft();

        int count = Continent.values().length;
        int tabWidth = (width - 4 * (count - 1)) / count;
        int x = left;
        for (int i = 0; i < count; i++) {
            Continent target = Continent.values()[i];
            int thisWidth = i == count - 1 ? left + width - x : tabWidth;
            addRenderableWidget(Button.builder(tabLabel(target), button -> select(target))
                    .bounds(x, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            x += tabWidth + 4;
        }

        // One row, so the map gets the rest of the screen. What is selected is described on the map
        // itself the way Loka's own does it, rather than in a panel eating half the height.
        int bottomY = this.height - 26;
        int quarter = (width - 12) / 4;
        waypointButton = addRenderableWidget(Button.builder(waypointLabel(), button -> {
                    toggleWaypoint();
                    button.setMessage(waypointLabel());
                })
                .bounds(left, bottomY, quarter, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("betterloka.map.copy"),
                        button -> copyCoordinates())
                .bounds(left + quarter + 4, bottomY, quarter, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("betterloka.map.recenter"),
                        button -> fitToContinent())
                .bounds(left + (quarter + 4) * 2, bottomY, quarter, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(left + (quarter + 4) * 3, bottomY,
                        left + width - (left + (quarter + 4) * 3), 20).build());

        // The search sits on the map's top right corner, the way a web map carries one. A child but
        // not a renderable: it is drawn after the map, which would otherwise paint over it.
        layoutMap();
        String previous = searchField != null ? searchField.getValue() : "";
        int searchWidth = Math.max(80, Math.min(SEARCH_MAX_WIDTH, mapWidth / 2 - 30));
        searchField = new EditBox(this.font, mapX + mapWidth - searchWidth - SEARCH_INSET,
                mapY + SEARCH_INSET, searchWidth, SEARCH_HEIGHT,
                Component.translatable("betterloka.map.search"));
        searchField.setMaxLength(48);
        searchField.setHint(Component.translatable("betterloka.map.search_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        searchField.setValue(previous);
        searchField.setResponder(text -> missedQuery = null);
        addWidget(searchField);
    }

    private Component tabLabel(Continent value) {
        Component label = Component.literal(value.displayName());
        return value == continent ? label.copy().withStyle(ChatFormatting.YELLOW) : label;
    }

    private Component waypointLabel() {
        if (selected == null) {
            return Component.translatable("betterloka.map.waypoint");
        }
        boolean set = BetterLokaClient.map().isSet(continent.world(), selected.label());
        return Component.translatable(set ? "betterloka.map.waypoint_off" : "betterloka.map.waypoint");
    }

    private void select(Continent target) {
        if (continent == target) {
            return;
        }
        rememberView();
        continent = target;
        lastContinent = target;
        snapshot = BetterLokaClient.mapData().snapshot(target);
        selectedNumber = null;
        selected = null;
        found = null;
        missedQuery = null;
        closePin();
        centred = false;
        rebuildWidgets();
    }

    /** The territories currently held, shorthand for the snapshot's list. */
    private List<MapTerritory> territories() {
        return snapshot.territories();
    }

    /** The selected territory as the current snapshot describes it, not as it was when picked. */
    private MapTerritory resolveSelection() {
        if (selectedNumber == null) {
            return null;
        }
        for (MapTerritory territory : territories()) {
            if (selectedNumber.equals(territory.number())) {
                return territory;
            }
        }
        return null;
    }

    // --- the view ---

    private void rememberView() {
        if (centred) {
            // The target, not the frame: a view saved mid-zoom should come back where it was going.
            VIEWS.put(continent, new View(view.targetCenterX(), view.targetCenterZ(),
                    view.targetZoom()));
        }
    }

    /**
     * Opens where it was left, or on the whole island the first time.
     *
     * <p>The middle comes from Loka's website and the zoom from the territories, which is the pair
     * that frames it: its centre says where a person wants to be looking, and the outlines say how
     * much has to fit for the continent to read as one place.
     */
    private void centreOnContinent() {
        View saved = VIEWS.get(continent);
        if (saved != null) {
            view.jumpTo(clampZoom(saved.blocksPerPixel()), saved.centerX(), saved.centerZ());
            blocksPerPixel = view.zoom();
            centred = true;
            return;
        }
        fitToContinent();
    }

    /** Frames the whole continent, for opening it and for the button that gets back there. */
    private void fitToContinent() {
        double centerX = continent.centerX();
        double centerZ = continent.centerZ();
        view.jumpTo(view.targetZoom(), centerX, centerZ);
        blocksPerPixel = view.zoom();
        centred = true;
        if (territories().isEmpty() || mapWidth <= 0 || mapHeight <= 0) {
            return;
        }
        double halfWidth = 0;
        double halfHeight = 0;
        for (MapTerritory territory : territories()) {
            halfWidth = Math.max(halfWidth, Math.abs(territory.maxX() - centerX));
            halfWidth = Math.max(halfWidth, Math.abs(centerX - territory.minX()));
            halfHeight = Math.max(halfHeight, Math.abs(territory.maxZ() - centerZ));
            halfHeight = Math.max(halfHeight, Math.abs(centerZ - territory.minZ()));
        }
        if (halfWidth > 0) {
            view.jumpTo(clampZoom(Math.max(2 * halfWidth / mapWidth,
                    2 * halfHeight / mapHeight) * MapStyle.FIT_MARGIN), centerX, centerZ);
            blocksPerPixel = view.zoom();
        }
        rememberView();
    }

    private static double clampZoom(double value) {
        return Math.max(MapStyle.MIN_BLOCKS_PER_PIXEL,
                Math.min(MapStyle.MAX_BLOCKS_PER_PIXEL, value));
    }

    /**
     * How long a zoom takes to arrive, as a time constant: about 95% of the way in three of these.
     *
     * <p>Short enough that a click still feels like a click, long enough that the eye follows the
     * ground instead of being handed a different picture.
     */
    private static final double ZOOM_TAU_SECONDS = 0.055;

    /** Zooms about a point on screen, so whatever is under it stays under it. */
    private void zoomAbout(double screenX, double screenY, boolean in) {
        double target = clampZoom(in ? view.targetZoom() / MapStyle.ZOOM_STEP
                : view.targetZoom() * MapStyle.ZOOM_STEP);
        view.zoomAbout(target, worldXAt(screenX), worldZAt(screenY));
        rememberView();
    }

    /** Moves the drawn zoom a frame's worth along, and reads it out for the frame to draw. */
    private void advanceZoom() {
        long now = System.nanoTime();
        double seconds = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        view.advance(seconds, view.flying() ? SEARCH_FLY_TAU_SECONDS : ZOOM_TAU_SECONDS);
        blocksPerPixel = view.zoom();
    }

    private boolean canZoomIn() {
        return view.targetZoom() > MapStyle.MIN_BLOCKS_PER_PIXEL + 1e-9;
    }

    private boolean canZoomOut() {
        return view.targetZoom() < MapStyle.MAX_BLOCKS_PER_PIXEL - 1e-9;
    }

    private double worldLeft() {
        return view.centerX() - mapWidth * blocksPerPixel / 2;
    }

    private double worldTop() {
        return view.centerZ() - mapHeight * blocksPerPixel / 2;
    }

    /**
     * Fixes the whole-pixel origin for this frame, and the fraction the layer is shifted by.
     *
     * <p>Called once before anything on the map is drawn. See the fields for why the origin is
     * rounded once here rather than each marker being rounded against a moving one.
     */
    private void takeOrigin() {
        double leftPixels = worldLeft() / blocksPerPixel;
        double topPixels = worldTop() / blocksPerPixel;
        originPixelX = (int) Math.round(leftPixels);
        originPixelY = (int) Math.round(topPixels);
        originFracX = (float) (originPixelX - leftPixels);
        originFracY = (float) (originPixelY - topPixels);
    }

    private int screenXOf(double worldX) {
        return mapX + (int) Math.round(worldX / blocksPerPixel) - originPixelX;
    }

    private int screenYOf(double worldZ) {
        return mapY + (int) Math.round(worldZ / blocksPerPixel) - originPixelY;
    }

    /** The same place, unrounded, for the ground — which is drawn as one transformed layer. */
    private double screenXExact(double worldX) {
        return mapX + (worldX - worldLeft()) / blocksPerPixel;
    }

    private double screenYExact(double worldZ) {
        return mapY + (worldZ - worldTop()) / blocksPerPixel;
    }

    private double worldXAt(double screenX) {
        return worldLeft() + (screenX - mapX) * blocksPerPixel;
    }

    private double worldZAt(double screenY) {
        return worldTop() + (screenY - mapY) * blocksPerPixel;
    }

    // --- input ---

    private int zoomButtonX() {
        return mapX + MapStyle.ZOOM_BUTTON_MARGIN;
    }

    private int zoomInY() {
        return mapY + MapStyle.ZOOM_BUTTON_MARGIN;
    }

    private int zoomOutY() {
        return zoomInY() + MapStyle.ZOOM_BUTTON_SIZE + MapStyle.ZOOM_BUTTON_GAP;
    }

    /** The download button, set a little apart from the two zooms so it is not pressed by accident. */
    private int downloadY() {
        return zoomOutY() + MapStyle.ZOOM_BUTTON_SIZE + MapStyle.ZOOM_BUTTON_GAP * 3;
    }

    private boolean overButton(double screenX, double screenY, int buttonY) {
        int x = zoomButtonX();
        return screenX >= x && screenX < x + MapStyle.ZOOM_BUTTON_SIZE
                && screenY >= buttonY && screenY < buttonY + MapStyle.ZOOM_BUTTON_SIZE;
    }

    /** Whether a point is on one of the buttons painted over the map's corner. */
    private boolean overMapButton(double screenX, double screenY) {
        return mapWidth > 0 && (overButton(screenX, screenY, zoomInY())
                || overButton(screenX, screenY, zoomOutY())
                || overButton(screenX, screenY, downloadY()));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        // The search field sits on the map, so it is asked before the map is.
        if (searchField.isVisible() && searchField.isMouseOver(click.x(), click.y())) {
            return super.mouseClicked(click, doubled);
        }
        // Minecraft 26.3 numbers the mouse buttons from one — left 1, middle 2, right 3 — where the
        // windowing library before it counted from nought. Testing for 1 as "right" is what turned
        // every left click into a right one: it pinned a coordinate instead of reaching the tabs,
        // the bottom buttons or the zoom buttons, so nothing on this screen seemed to answer.
        if (click.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            return rightClick(click.x(), click.y()) || super.mouseClicked(click, doubled);
        }
        if (click.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(click, doubled);
        }
        // Clicking the map or its buttons is clicking away from the field: typing stops going there.
        if (insideMap(click.x(), click.y()) && getFocused() == searchField) {
            setFocused(null);
        }
        // A left click anywhere dismisses the coordinate bubble, including the click that lands on
        // its own Copy button — which is handled first so the copy still happens.
        if (pinned) {
            if (overCopyButton(click.x(), click.y())) {
                copyPinned();
                return true;
            }
            closePin();
        }
        // The zoom buttons sit over the map, so they get the click before panning does.
        if (mapWidth > 0 && overButton(click.x(), click.y(), zoomInY())) {
            if (canZoomIn()) {
                zoomAbout(mapX + mapWidth / 2.0, mapY + mapHeight / 2.0, true);
            }
            return true;
        }
        if (mapWidth > 0 && overButton(click.x(), click.y(), zoomOutY())) {
            if (canZoomOut()) {
                zoomAbout(mapX + mapWidth / 2.0, mapY + mapHeight / 2.0, false);
            }
            return true;
        }
        if (mapWidth > 0 && overButton(click.x(), click.y(), downloadY())) {
            toggleDownload();
            return true;
        }
        if (insideMap(click.x(), click.y())) {
            dragging = true;
            // A drag has to follow the hand exactly, so a zoom still running is finished here rather
            // than left to fight the pan for the middle.
            view.settle();
            dragOriginX = click.x();
            dragOriginY = click.y();
            dragStartX = view.centerX();
            dragStartZ = view.centerZ();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        if (dragging) {
            view.moveTo(dragStartX - (click.x() - dragOriginX) * blocksPerPixel,
                    dragStartZ - (click.y() - dragOriginY) * blocksPerPixel);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging) {
            dragging = false;
            rememberView();
            // A press that did not move is a click, and picks the territory under it.
            if (Math.abs(click.x() - dragOriginX) <= DRAG_SLOP
                    && Math.abs(click.y() - dragOriginY) <= DRAG_SLOP) {
                selected = territoryAt(click.x(), click.y());
                selectedNumber = selected == null ? null : selected.number();
                // Picking something else puts the search's light out; picking what it lit keeps it.
                if (selected == null || !litTerritories().contains(selected)) {
                    found = null;
                }
                if (waypointButton != null) {
                    waypointButton.setMessage(waypointLabel());
                }
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!insideMap(mouseX, mouseY) || vertical == 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        zoomAbout(mouseX, mouseY, vertical > 0);
        return true;
    }

    private boolean insideMap(double screenX, double screenY) {
        return screenX >= mapX && screenX < mapX + mapWidth
                && screenY >= mapY && screenY < mapY + mapHeight;
    }

    private MapTerritory territoryAt(double screenX, double screenY) {
        if (!insideMap(screenX, screenY)) {
            return null;
        }
        double worldX = worldXAt(screenX);
        double worldZ = worldZAt(screenY);
        if (devLinearHitTest) {
            // The old way, kept only so the new one can be measured against it in the same run.
            for (MapTerritory territory : territories()) {
                if (territory.contains(worldX, worldZ)) {
                    return territory;
                }
            }
            return null;
        }
        // Through the snapshot's grid rather than by walking the continent: this runs every frame
        // while dragging, and 143 polygons of twenty-six edges apiece is what made that stutter.
        return snapshot.index().at(worldX, worldZ);
    }

    /** Development only: forces the pre-index hit test, so the two can be timed side by side. */
    public static boolean devLinearHitTest;

    /**
     * Development only: draws the ground the way the old core chose it, for a photograph.
     *
     * <p>The level alone, not the placement or the sampling — so a pair of pictures taken with this
     * on and off shows what picking by real pixels is worth and nothing else.
     */
    public static boolean devLegacyTerrain;

    // --- drawing ---

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 10,
                GuiTheme.TEXT);

        layoutMap();
        GuiTheme.panel(context, contentLeft(), MAP_TOP - 4, contentWidth(), mapHeight + 8);

        // One read per frame: the store may swap the snapshot under us at any moment, and half a
        // frame drawn from each would tear.
        snapshot = BetterLokaClient.mapData().snapshot(continent);
        selected = resolveSelection();

        // Nothing to search until there is something on the map.
        searchField.setVisible(!territories().isEmpty());
        if (territories().isEmpty()) {
            centered(context, "betterloka.map.loading", MAP_TOP + mapHeight / 2, GuiTheme.MUTED);
            return;
        }

        if (!centred) {
            centreOnContinent();
        }
        // In this order: move the zoom on, then fix the origin the frame is drawn against.
        advanceZoom();
        takeOrigin();
        hovered = dragging || overSearch(mouseX, mouseY) ? null : territoryAt(mouseX, mouseY);
        drawMap(context, mouseX, mouseY);
        drawSearch(context, mouseX, mouseY, delta);

        // Outside the map's scissor: the card may reach past the panel, and should.
        // While the bubble is up it is what the pointer is about; two panels over one spot is
        // just noise.
        MapTerritory describing = pinned ? null : hovered != null ? hovered : selected;
        if (describing != null) {
            drawInfoCard(context, describing, mouseX, mouseY, hovered != null);
        }
        drawPin(context, mouseX, mouseY);
    }

    private void centered(GuiGraphicsExtractor context, String key, int y, int color) {
        context.centeredText(this.font, Component.translatable(key),
                this.width / 2, y, color);
    }

    private void drawMap(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        context.enableScissor(mapX, mapY, mapX + mapWidth, mapY + mapHeight);

        // Opaque, and the colour of deep water: Loka's own draws its hexes over sea.
        context.fill(mapX, mapY, mapX + mapWidth, mapY + mapHeight, MapStyle.OCEAN);
        devFrameQuads = 0;
        long devStart = System.nanoTime();
        drawTerrain(context);
        long devAfterTerrain = System.nanoTime();
        devTerrainNanos = devAfterTerrain - devStart;

        // Over the ground and under the markers, like the claims it lights up.
        List<MapTerritory> lit = litTerritories();
        drawHighlight(context, lit);

        // The claims ride on the same fraction of a pixel the ground does, so the outlines stay
        // welded to the coast through a drag instead of stepping off it and back on. Placed against
        // the rounded origin and shifted once here — see the origin fields.
        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate(originFracX, originFracY);

        // The claims themselves — fills and outlines — are painted into the ground tiles as they are
        // decoded (see TerritoryOverlay), so there is nothing to draw for them here. Drawing them
        // here, one quad per pixel of outline every frame, is what made the map lag: twenty thousand
        // quads and three tenths of a second a frame with a whole continent on screen.
        // The one outline still drawn per frame: the selection follows a click, and painting it into
        // the tiles would mean repainting tiles on every click.
        if (selected != null && !offScreen(selected)) {
            drawSelection(context, selected);
        }
        if (lit.isEmpty()) {
            drawFoundMarker(context);
        }

        long devAfterBorders = System.nanoTime();
        devSelectionNanos = devAfterBorders - devAfterTerrain;
        for (MapTerritory territory : territories()) {
            if (!offScreen(territory)) {
                drawIcon(context, territory);
            }
        }
        devIconNanos = System.nanoTime() - devAfterBorders;
        matrices.popMatrix();

        drawZoomButtons(context, mouseX, mouseY);
        drawViewNote(context, mouseX, mouseY);
        context.disableScissor();
    }

    /** Whether a territory is entirely outside the window, and so not worth drawing at all. */
    private boolean offScreen(MapTerritory territory) {
        return screenXOf(territory.maxX()) < mapX
                || screenXOf(territory.minX()) > mapX + mapWidth
                || screenYOf(territory.maxZ()) < mapY
                || screenYOf(territory.minZ()) > mapY + mapHeight;
    }

    private void drawZoomButtons(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        MapStyle.button(context, this.font, zoomButtonX(), zoomInY(),
                MapStyle.ZOOM_BUTTON_SIZE, "+",
                overButton(mouseX, mouseY, zoomInY()), canZoomIn());
        MapStyle.button(context, this.font, zoomButtonX(), zoomOutY(),
                MapStyle.ZOOM_BUTTON_SIZE, "-",
                overButton(mouseX, mouseY, zoomOutY()), canZoomOut());
        MapStyle.downloadButton(context, zoomButtonX(), downloadY(), MapStyle.ZOOM_BUTTON_SIZE,
                overButton(mouseX, mouseY, downloadY()), !territories().isEmpty(),
                BetterLokaClient.terrainDownload().running());
    }

    /**
     * Loka's own ground, in two layers.
     *
     * <p>The coarse one covers the whole continent in a few dozen tiles, so the island is there
     * complete the moment the map opens. The sharp one is drawn over it and only for the window
     * being looked at. Where a sharp tile has not arrived the coarse one is left showing, which is
     * why closing in never flashes holes.
     */
    private void drawTerrain(GuiGraphicsExtractor context) {
        MapTerrain terrain = BetterLokaClient.mapTerrain();
        terrain.beginFrame();

        drawTerrainLayer(context, terrain, MapTerrain.BASE_LEVEL);
        // Blocks per real pixel, not per interface pixel: see MapTerrain.levelFor.
        int detail = devLegacyTerrain
                ? MapTerrain.devLegacyLevelFor(blocksPerPixel)
                : MapTerrain.levelFor(MapTerrain.perRealPixel(blocksPerPixel));
        // Step out until the layer fits the texture budget. See MapTerrain.MAX_DETAIL_TILES.
        while (detail < MapTerrain.BASE_LEVEL && tilesAcross(detail) > MapTerrain.MAX_DETAIL_TILES) {
            detail++;
        }
        if (detail < MapTerrain.BASE_LEVEL) {
            drawTerrainLayer(context, terrain, detail);
            prefetchRing(terrain, detail);
        }
    }

    /** How many tiles of one level the window covers. */
    private int tilesAcross(int level) {
        int step = 1 << level;
        long columns = (MapTerrain.tileXAt(worldXAt(mapX + mapWidth), level)
                - MapTerrain.tileXAt(worldLeft(), level)) / step + 1;
        long rows = (MapTerrain.tileYAt(worldTop(), level)
                - MapTerrain.tileYAt(worldZAt(mapY + mapHeight), level)) / step + 1;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, columns) * Math.max(0, rows));
    }

    /**
     * Asks for the ring of tiles just outside the window.
     *
     * <p>A tile is only ever asked for when it is about to be drawn, which means the first frame it
     * is wanted is the first frame anyone has heard of it — so a pan walks a moving edge of ground
     * that has not arrived. One ring of margin covers the distance a drag crosses while a tile is in
     * the air, and costs nothing once fetched: tiles are kept on disk.
     */
    private void prefetchRing(MapTerrain terrain, int level) {
        int step = 1 << level;
        int firstX = MapTerrain.tileXAt(worldLeft(), level) - step;
        int lastX = MapTerrain.tileXAt(worldXAt(mapX + mapWidth), level) + step;
        int firstY = MapTerrain.tileYAt(worldZAt(mapY + mapHeight), level) - step;
        int lastY = MapTerrain.tileYAt(worldTop(), level) + step;
        for (int tileX = firstX; tileX <= lastX; tileX += step) {
            for (int tileY = firstY; tileY <= lastY; tileY += step) {
                boolean edge = tileX == firstX || tileX == lastX || tileY == firstY || tileY == lastY;
                if (edge) {
                    terrain.tile(continent, level, tileX, tileY);
                }
            }
        }
    }

    /**
     * One zoom's worth of ground, drawn as a single transformed sheet.
     *
     * <p>The tiles are laid out on their own whole-texel grid and the grid is then placed and scaled
     * to the view, rather than each tile being rounded onto screen pixels by itself. Two things fall
     * out of that. Neighbours share an edge exactly whatever the zoom happens to be, so the grid of
     * hairline seams the old placement drew through the ground cannot occur. And the sheet is placed
     * at a fractional position, so panning slides it rather than stepping it a whole pixel at a time
     * — which at the interface scales people actually play at was a jump of three or four real
     * pixels, and is most of what "not smooth" was.
     */
    private void drawTerrainLayer(GuiGraphicsExtractor context, MapTerrain terrain, int level) {
        int step = 1 << level;
        int firstX = MapTerrain.tileXAt(worldLeft(), level);
        int lastX = MapTerrain.tileXAt(worldXAt(mapX + mapWidth), level);
        int firstY = MapTerrain.tileYAt(worldZAt(mapY + mapHeight), level);
        int lastY = MapTerrain.tileYAt(worldTop(), level);

        // Tile indices count west to east but south to north, so the grid's own corner is the
        // westernmost column and the northernmost row: firstX and lastY.
        double cornerWorldX = MapTerrain.tileWorldX(firstX);
        double cornerWorldZ = MapTerrain.tileWorldZ(lastY);
        float scale = (float) (MapTerrain.blocksPerTile(level) / blocksPerPixel / MapTerrain.TILE_PIXELS);
        if (scale <= 0 || !Float.isFinite(scale)) {
            return;
        }

        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate((float) screenXExact(cornerWorldX), (float) screenYExact(cornerWorldZ));
        matrices.scale(scale, scale);
        for (int tileX = firstX; tileX <= lastX; tileX += step) {
            int gridX = (tileX - firstX) / step * MapTerrain.TILE_PIXELS;
            for (int tileY = firstY; tileY <= lastY; tileY += step) {
                var id = terrain.tile(continent, level, tileX, tileY);
                if (id == null) {
                    continue;
                }
                int gridY = (lastY - tileY) / step * MapTerrain.TILE_PIXELS;
                context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id,
                        gridX, gridY, 0f, 0f,
                        MapTerrain.TILE_PIXELS, MapTerrain.TILE_PIXELS,
                        MapTerrain.TILE_PIXELS, MapTerrain.TILE_PIXELS,
                        MapTerrain.TILE_PIXELS, MapTerrain.TILE_PIXELS, 0xFFFFFFFF);
            }
        }
        matrices.popMatrix();
    }

    private void drawIcon(GuiGraphicsExtractor context, MapTerritory territory) {
        double onScreenWidth = (territory.maxX() - territory.minX()) / blocksPerPixel;
        if (onScreenWidth < MapStyle.ICON_SIZE + 4) {
            return;
        }
        int centreX = screenXOf(territory.centerX());
        int centreY = screenYOf(territory.centerZ());

        var id = BetterLokaClient.mapIcons().get(territory.icon(), continent);
        if (id != null) {
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id,
                    centreX - MapStyle.ICON_SIZE / 2, centreY - MapStyle.ICON_SIZE / 2,
                    0f, 0f, MapStyle.ICON_SIZE, MapStyle.ICON_SIZE,
                    MapStyle.ICON_SIZE, MapStyle.ICON_SIZE, 0xFFFFFFFF);
        }

        // What the hex is worth, on the hex — only where Loka publishes it, and only when there is
        // room for it to be read rather than to be clutter.
        if (territory.hasConquestPoints() && onScreenWidth >= MapStyle.CP_ON_HEX_MIN_WIDTH) {
            String points = territory.conquestPoints() + " CP";
            context.text(this.font, points,
                    centreX - this.font.width(points) / 2,
                    centreY + MapStyle.ICON_SIZE / 2 + 1, MapStyle.CONQUEST_POINTS);
        }
    }

    /**
     * Fills one territory, a screen row at a time.
     *
     * <p>Each row asks the polygon's edges where they cross it and fills between the crossings in
     * pairs, rather than testing every pixel for being inside — which for 143 polygons of twenty-six
     * edges was tens of millions of tests a frame and made the screen a slideshow.
     */
    /** Development only: quads issued and nanoseconds spent by the last frame's map layer. */
    public static int devFrameQuads;
    public static long devTerrainNanos;
    public static long devSelectionNanos;
    public static long devIconNanos;

    /** The selected territory's outline, drawn over the painted map so a click shows at once. */
    private void drawSelection(GuiGraphicsExtractor context, MapTerritory territory) {
        double[] xs = territory.xs();
        double[] zs = territory.zs();
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            line(context, xs[j], zs[j], xs[i], zs[i], MapStyle.BORDER_SELECTED,
                    MapStyle.BORDER_SELECTED_WIDTH);
        }
    }

    private void line(GuiGraphicsExtractor context, double x1, double z1, double x2, double z2,
                      int color, int thickness) {
        int px1 = screenXOf(x1);
        int py1 = screenYOf(z1);
        int px2 = screenXOf(x2);
        int py2 = screenYOf(z2);

        // Cheap reject: most edges of most continents are nowhere near the window.
        int slack = thickness + 1;
        if (Math.max(px1, px2) < mapX - slack || Math.min(px1, px2) > mapX + mapWidth + slack
                || Math.max(py1, py2) < mapY - slack
                || Math.min(py1, py2) > mapY + mapHeight + slack) {
            return;
        }

        int steps = Math.max(Math.abs(px2 - px1), Math.abs(py2 - py1));
        if (steps > 4000) {
            return;
        }
        int half = thickness / 2;
        for (int step = 0; step <= steps; step++) {
            int px = px1 + (px2 - px1) * step / Math.max(1, steps);
            int py = py1 + (py2 - py1) * step / Math.max(1, steps);
            context.fill(px - half, py - half, px - half + thickness, py - half + thickness, color);
            devFrameQuads++;
        }
    }

    /** Where the middle of the view sits — labelled, so it cannot be read as a territory's own. */
    private void drawViewNote(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        // The download has no panel of its own: what it is doing goes where the view note goes,
        // because the two are never both interesting and a second line would crowd the map.
        Component note = downloadNote(mouseX, mouseY);
        int colour = GuiTheme.MUTED;
        if (note == null) {
            note = Component.translatable("betterloka.map.view",
                    Math.round(view.centerX()), Math.round(view.centerZ()));
        } else {
            colour = GuiTheme.LIVE;
        }
        context.text(this.font, note,
                mapX + 4, mapY + mapHeight - 10, colour);

        // Old data is shown rather than hidden, and said so rather than passed off as current.
        if (snapshot.stale(System.currentTimeMillis())) {
            Component age = Component.translatable("betterloka.map.as_of", TimeFormat.ago(snapshot.fetchedAt()));
            context.text(this.font, age,
                    mapX + mapWidth - this.font.width(age) - 4,
                    mapY + mapHeight - 10, GuiTheme.LIVE);
        }
    }

    // --- downloading the ground ---

    /** How long a finished download keeps saying so before the view note comes back. */
    private static final long DOWNLOAD_DONE_MILLIS = 20_000;

    /** The corners of everything claimed on this continent, which is the ground worth having. */
    private TerrainDownload.Bounds continentBounds() {
        return TerrainDownload.Bounds.of(territories());
    }

    /** Starts the download for this continent, or stops the one running. */
    private void toggleDownload() {
        TerrainDownload download = BetterLokaClient.terrainDownload();
        if (download.running()) {
            download.cancel();
            return;
        }
        if (territories().isEmpty()) {
            return;
        }
        download.start(continent, continentBounds(), TerrainDownload.DEFAULT_DEEPEST_LEVEL);
    }

    /**
     * What to say about the download, or {@code null} to leave the view note showing.
     *
     * <p>Idle and not being pointed at is the only case that says nothing: what a download will cost
     * has to be readable before it is started, not after.
     */
    private Component downloadNote(int mouseX, int mouseY) {
        TerrainDownload download = BetterLokaClient.terrainDownload();
        TerrainDownload.Progress progress = download.progress();
        boolean hovering = mapWidth > 0 && overButton(mouseX, mouseY, downloadY());

        if (progress.running() && progress.continent() == continent) {
            return Component.translatable("betterloka.map.download_running",
                    progress.done(), progress.total(), size(progress.bytes()));
        }
        if (progress.running()) {
            return Component.translatable("betterloka.map.download_elsewhere",
                    progress.continent().displayName(), progress.done(), progress.total());
        }
        if (progress.finished() && progress.continent() == continent
                && System.currentTimeMillis() - download.finishedAt() < DOWNLOAD_DONE_MILLIS) {
            return Component.translatable("betterloka.map.download_done",
                    progress.fetched(), size(progress.bytes()));
        }
        if (!hovering) {
            return null;
        }
        if (territories().isEmpty()) {
            return null;
        }
        int tiles = TerrainDownload.tileCount(continentBounds(),
                TerrainDownload.DEFAULT_DEEPEST_LEVEL);
        return Component.translatable("betterloka.map.download_offer", continent.displayName(), tiles,
                size((long) tiles * ESTIMATED_TILE_BYTES));
    }

    /** What a tile weighs on average, measured across a cache of a few hundred: 3.9 KB. */
    private static final int ESTIMATED_TILE_BYTES = 4000;

    /**
     * A size somebody can read, with its own unit.
     *
     * <p>Whole megabytes alone will not do: a continent part-way through is a few hundred kilobytes
     * and rounds to "0 MB", which reads as a download that is not moving.
     */
    private static String size(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%d KB", Math.round(bytes / 1024.0));
        }
        double megabytes = bytes / (1024.0 * 1024.0);
        return megabytes < 10
                ? String.format(Locale.ROOT, "%.1f MB", megabytes)
                : String.format(Locale.ROOT, "%.0f MB", megabytes);
    }

    // --- the information card ---

    private enum Kind { HEADER, OWNER, SEAT, SEPARATOR, ROW, SMALL }

    private record CardLine(Kind kind, Component left, Component right, int leftColor, int rightColor,
                            String icon) {
        static CardLine header(Component text) {
            return new CardLine(Kind.HEADER, text, null, GuiTheme.TEXT, 0, null);
        }

        static CardLine owner(Component text, int color) {
            return new CardLine(Kind.OWNER, text, null, color, 0, null);
        }

        static CardLine seat(Component text, String icon) {
            return new CardLine(Kind.SEAT, text, null, GuiTheme.TEXT, 0, icon);
        }

        static CardLine separator() {
            return new CardLine(Kind.SEPARATOR, null, null, 0, 0, null);
        }

        static CardLine row(Component label, Component value, int valueColor) {
            return new CardLine(Kind.ROW, label, value, GuiTheme.MUTED, valueColor, null);
        }

        static CardLine small(Component text) {
            return new CardLine(Kind.SMALL, text, null, GuiTheme.MUTED, 0, null);
        }
    }

    /**
     * Everything known about one territory, in sections.
     *
     * <p>The distance is worked out from the player's position every frame rather than when the
     * territory was picked, so it counts down as they walk.
     */
    private List<CardLine> cardLines(MapTerritory territory) {
        List<CardLine> lines = new ArrayList<>();
        lines.add(CardLine.header(Component.literal(territory.label()).withStyle(ChatFormatting.BOLD)));

        if (territory.neutral()) {
            lines.add(CardLine.owner(Component.translatable("betterloka.map.unclaimed"),
                    GuiTheme.MUTED));
        } else {
            lines.add(CardLine.owner(Component.literal(territory.owner()),
                    0xFF000000 | snapshot.palette().colorOf(territory.owner())));
        }

        MapTown seat = BetterLokaClient.mapData().seatOf(continent, territory);
        if (seat != null) {
            lines.add(CardLine.seat(Component.translatable("betterloka.map.town_seat",
                    Component.literal(seat.name()).withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE)),
                    territory.icon()));
            if (seat.hasTitle()) {
                lines.add(CardLine.owner(Component.literal(seat.title()), MapStyle.CONQUEST_POINTS));
            }
        }

        lines.add(CardLine.separator());
        lines.add(CardLine.row(Component.translatable("betterloka.map.coordinates"),
                Component.literal(String.format(Locale.ROOT, "%d, %d",
                        Math.round(territory.centerX()), Math.round(territory.centerZ()))),
                GuiTheme.TEXT));
        lines.add(CardLine.row(Component.translatable("betterloka.map.distance"), distanceText(territory),
                GuiTheme.LIVE));
        if (territory.hasConquestPoints()) {
            lines.add(CardLine.row(Component.translatable("betterloka.map.conquest_points"),
                    Component.translatable("betterloka.map.cp_per_day", territory.conquestPoints()),
                    MapStyle.CONQUEST_POINTS));
        }

        List<CardLine> extra = new ArrayList<>();
        if (territory.alliance() != null) {
            extra.add(CardLine.small(Component.translatable("betterloka.map.alliance_of",
                    territory.alliance())));
        }
        MapTown town = territory.neutral()
                ? null : BetterLokaClient.mapData().town(continent, territory.owner());
        if (town != null) {
            if (town.strength() >= 0) {
                extra.add(CardLine.small(Component.translatable("betterloka.map.strength_of",
                        String.format(Locale.ROOT, "%.0f", town.strength()))));
            }
            extra.add(CardLine.small(Component.translatable("betterloka.map.holdings",
                    town.members(), town.territories())));
        }
        extra.add(CardLine.small(chunksText(territory)));
        if (territory.mutator() != null) {
            extra.add(CardLine.small(Component.translatable("betterloka.map.mutator_of",
                    territory.mutator())));
        }

        // What taking this hex actually buys. Wrapped rather than trimmed: a bonus cut off halfway
        // through its own sentence is worse than not showing it, and the card sizes to its widest
        // line so one long sentence would otherwise stretch it across the map.
        if (territory.hasBonus()) {
            extra.add(CardLine.separator());
            extra.add(CardLine.small(Component.translatable("betterloka.map.bonus")
                    .copy().withStyle(ChatFormatting.GOLD)));
            for (String line : MapStyle.wrapSmall(this.font, territory.bonus(),
                    MapStyle.SMALL_WRAP_WIDTH)) {
                extra.add(CardLine.small(Component.literal(line).withStyle(ChatFormatting.YELLOW)));
            }
        }
        if (!extra.isEmpty()) {
            lines.add(CardLine.separator());
            lines.addAll(extra);
        }
        return lines;
    }

    private Component distanceText(MapTerritory territory) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return Component.literal("—");
        }
        double blocks = at(territory).distanceTo(this.minecraft.player.getX(),
                this.minecraft.player.getZ());
        return Component.literal(String.format(Locale.ROOT, "%.0f m", blocks));
    }

    private Component chunksText(MapTerritory territory) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return Component.translatable("betterloka.map.chunks_away", "—");
        }
        return Component.translatable("betterloka.map.chunks_away",
                at(territory).chunksTo(this.minecraft.player.getX(), this.minecraft.player.getZ()));
    }

    private Waypoint at(MapTerritory territory) {
        return new Waypoint(territory.label(), continent.world(),
                territory.centerX(), 64, territory.centerZ(),
                territory.fillColor(), territory.icon());
    }

    private int lineHeight(CardLine line) {
        return switch (line.kind()) {
            case HEADER -> MapStyle.CARD_ROW_HEIGHT + 2;
            case OWNER, ROW -> MapStyle.CARD_ROW_HEIGHT;
            case SEAT -> Math.max(MapStyle.CARD_ROW_HEIGHT, MapStyle.ICON_SIZE + 2);
            case SEPARATOR -> MapStyle.CARD_SECTION_GAP * 2 + 1;
            case SMALL -> MapStyle.smallHeight(this.font);
        };
    }

    private int lineWidth(CardLine line) {
        return switch (line.kind()) {
            case HEADER, OWNER -> (line.kind() == Kind.OWNER
                    ? MapStyle.SWATCH_WIDTH + MapStyle.SWATCH_GAP : 0)
                    + this.font.width(line.left());
            case SEAT -> MapStyle.ICON_SIZE + MapStyle.SWATCH_GAP
                    + this.font.width(line.left());
            case ROW -> this.font.width(line.left()) + 14
                    + this.font.width(line.right());
            case SEPARATOR -> 0;
            case SMALL -> MapStyle.smallWidth(this.font, line.left());
        };
    }

    /**
     * The card, placed beside the cursor and folded back when it would leave the screen.
     *
     * <p>When nothing is under the pointer it describes what was last clicked instead, pinned to the
     * corner — one card either way, rather than a hover panel and a selection panel repeating each
     * other.
     */
    private void drawInfoCard(GuiGraphicsExtractor context, MapTerritory territory,
                              int mouseX, int mouseY, boolean followCursor) {
        List<CardLine> lines = cardLines(territory);

        int inner = MapStyle.CARD_MIN_WIDTH;
        int height = 0;
        for (CardLine line : lines) {
            inner = Math.max(inner, lineWidth(line));
            height += lineHeight(line);
        }
        int width = inner + MapStyle.CARD_PADDING * 2;
        height += MapStyle.CARD_PADDING * 2;

        int x;
        int y;
        if (followCursor) {
            x = mouseX + MapStyle.CARD_CURSOR_OFFSET;
            if (x + width > this.width - MapStyle.CARD_SCREEN_MARGIN) {
                x = mouseX - MapStyle.CARD_CURSOR_OFFSET - width;
            }
            y = mouseY + MapStyle.CARD_CURSOR_OFFSET;
            if (y + height > this.height - MapStyle.CARD_SCREEN_MARGIN) {
                y = mouseY - MapStyle.CARD_CURSOR_OFFSET - height;
            }
        } else {
            x = mapX + 4;
            y = mapY + mapHeight - height - 4;
        }
        x = Math.max(MapStyle.CARD_SCREEN_MARGIN,
                Math.min(x, this.width - width - MapStyle.CARD_SCREEN_MARGIN));
        y = Math.max(MapStyle.CARD_SCREEN_MARGIN,
                Math.min(y, this.height - height - MapStyle.CARD_SCREEN_MARGIN));

        int border = territory.neutral()
                ? GuiTheme.MUTED : 0xFF000000 | snapshot.palette().colorOf(territory.owner());
        MapStyle.card(context, x, y, width, height, border);

        int textX = x + MapStyle.CARD_PADDING;
        int cursorY = y + MapStyle.CARD_PADDING;
        for (CardLine line : lines) {
            drawCardLine(context, line, textX, cursorY, inner, territory);
            cursorY += lineHeight(line);
        }
    }

    private void drawCardLine(GuiGraphicsExtractor context, CardLine line, int x, int y, int inner,
                              MapTerritory territory) {
        switch (line.kind()) {
            case HEADER -> context.text(this.font, line.left(), x, y,
                    line.leftColor());
            case OWNER -> {
                context.fill(x, y, x + MapStyle.SWATCH_WIDTH, y + MapStyle.SWATCH_HEIGHT,
                        line.leftColor());
                context.text(this.font, line.left(),
                        x + MapStyle.SWATCH_WIDTH + MapStyle.SWATCH_GAP, y, line.leftColor());
            }
            case SEAT -> {
                var id = BetterLokaClient.mapIcons().get(line.icon(), continent);
                if (id != null) {
                    context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id,
                            x, y, 0f, 0f, MapStyle.ICON_SIZE, MapStyle.ICON_SIZE,
                            MapStyle.ICON_SIZE, MapStyle.ICON_SIZE, 0xFFFFFFFF);
                }
                context.text(this.font, line.left(),
                        x + MapStyle.ICON_SIZE + MapStyle.SWATCH_GAP, y, line.leftColor());
            }
            case SEPARATOR -> context.fill(x, y + MapStyle.CARD_SECTION_GAP,
                    x + inner, y + MapStyle.CARD_SECTION_GAP + 1, MapStyle.CARD_SEPARATOR);
            case ROW -> {
                context.text(this.font, line.left(), x, y, line.leftColor());
                int valueWidth = this.font.width(line.right());
                context.text(this.font, line.right(),
                        x + inner - valueWidth, y, line.rightColor());
            }
            case SMALL -> MapStyle.small(context, this.font, line.left(), x, y,
                    line.leftColor());
        }
    }

    // --- the coordinate bubble ---

    /**
     * A right click asks "where is this?", anywhere on the map.
     *
     * <p>Left click is already spoken for by panning and picking a territory, and open sea has
     * nothing to pick — so the question that has no answer on the left button gets its own.
     */
    private boolean rightClick(double screenX, double screenY) {
        // Not on the buttons over the map: a right click there is a missed left click, not a question.
        if (!insideMap(screenX, screenY) || overMapButton(screenX, screenY)) {
            return false;
        }
        pinned = true;
        pinWorldX = worldXAt(screenX);
        pinWorldZ = worldZAt(screenY);
        pinScreenX = (int) Math.round(screenX);
        pinScreenY = (int) Math.round(screenY);
        pinnedAt = System.currentTimeMillis();
        copiedAt = 0;
        return true;
    }

    private void closePin() {
        pinned = false;
        copiedAt = 0;
    }

    /** What lands in the clipboard: two numbers, ready for chat, a command, or a note. */
    private String pinnedCoordinates() {
        return String.format(Locale.ROOT, "%d %d",
                Math.round(pinWorldX), Math.round(pinWorldZ));
    }

    private void copyPinned() {
        if (this.minecraft != null) {
            this.minecraft.keyboardHandler.setClipboard(pinnedCoordinates());
            copiedAt = System.currentTimeMillis();
        }
    }

    private int pinBubbleWidth() {
        int text = this.font.width(pinnedCoordinates());
        int button = this.font.width(
                Component.translatable("betterloka.map.copy_here")) + MapStyle.PIN_BUTTON_PADDING * 2;
        return Math.max(MapStyle.PIN_MIN_WIDTH,
                MapStyle.CARD_PADDING * 2 + Math.max(text, button));
    }

    private int pinBubbleHeight() {
        return MapStyle.CARD_PADDING * 2 + MapStyle.CARD_ROW_HEIGHT + MapStyle.PIN_BUTTON_HEIGHT + 3;
    }

    private int pinBubbleX() {
        int width = pinBubbleWidth();
        int x = pinScreenX + MapStyle.CARD_CURSOR_OFFSET;
        if (x + width > this.width - MapStyle.CARD_SCREEN_MARGIN) {
            x = pinScreenX - MapStyle.CARD_CURSOR_OFFSET - width;
        }
        return Math.max(MapStyle.CARD_SCREEN_MARGIN,
                Math.min(x, this.width - width - MapStyle.CARD_SCREEN_MARGIN));
    }

    private int pinBubbleY() {
        int height = pinBubbleHeight();
        int y = pinScreenY + MapStyle.CARD_CURSOR_OFFSET;
        if (y + height > this.height - MapStyle.CARD_SCREEN_MARGIN) {
            y = pinScreenY - MapStyle.CARD_CURSOR_OFFSET - height;
        }
        return Math.max(MapStyle.CARD_SCREEN_MARGIN,
                Math.min(y, this.height - height - MapStyle.CARD_SCREEN_MARGIN));
    }

    private boolean overCopyButton(double screenX, double screenY) {
        if (!pinned) {
            return false;
        }
        int x = pinBubbleX() + MapStyle.CARD_PADDING;
        int y = pinBubbleY() + MapStyle.CARD_PADDING + MapStyle.CARD_ROW_HEIGHT + 3;
        int width = pinBubbleWidth() - MapStyle.CARD_PADDING * 2;
        return screenX >= x && screenX < x + width
                && screenY >= y && screenY < y + MapStyle.PIN_BUTTON_HEIGHT;
    }

    private void drawPin(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (!pinned) {
            return;
        }
        if (System.currentTimeMillis() - pinnedAt > MapStyle.PIN_LIFETIME_MILLIS) {
            closePin();
            return;
        }
        int x = pinBubbleX();
        int y = pinBubbleY();
        int width = pinBubbleWidth();

        // A cross on the spot itself, so the bubble beside it is clearly about that point.
        context.fill(pinScreenX - 3, pinScreenY, pinScreenX + 4, pinScreenY + 1, MapStyle.PIN_MARK);
        context.fill(pinScreenX, pinScreenY - 3, pinScreenX + 1, pinScreenY + 4, MapStyle.PIN_MARK);

        MapStyle.card(context, x, y, width, pinBubbleHeight(), MapStyle.PIN_BORDER);
        context.text(this.font, pinnedCoordinates(),
                x + MapStyle.CARD_PADDING, y + MapStyle.CARD_PADDING, GuiTheme.TEXT);

        boolean copied = copiedAt > 0
                && System.currentTimeMillis() - copiedAt < MapStyle.COPIED_NOTICE_MILLIS;
        MapStyle.wideButton(context, this.font,
                x + MapStyle.CARD_PADDING,
                y + MapStyle.CARD_PADDING + MapStyle.CARD_ROW_HEIGHT + 3,
                width - MapStyle.CARD_PADDING * 2,
                Component.translatable(copied ? "betterloka.map.copied" : "betterloka.map.copy_here"),
                overCopyButton(mouseX, mouseY), copied);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (searchField.isFocused() && input.isConfirmation()) {
            runSearch();
            return true;
        }
        if (pinned && input.isEscape()) {
            closePin();
            return true;
        }
        return super.keyPressed(input);
    }

    // --- search ---

    /** The search for this snapshot, built again only when the store hands over a new one. */
    private MapSearch searchIndex() {
        if (search == null || searchBuiltFrom != snapshot) {
            search = MapSearch.of(territories(), snapshot.towns());
            searchBuiltFrom = snapshot;
        }
        return search;
    }

    /**
     * Enter in the field: flies to the best match, selects it and lights it up.
     *
     * <p>An empty field puts the light out, so the search can be cleared the way it was made.
     */
    private void runSearch() {
        String query = searchField.getValue().trim();
        if (query.isEmpty()) {
            found = null;
            missedQuery = null;
            return;
        }
        if (territories().isEmpty()) {
            return;
        }
        MapSearch.Hit hit = searchIndex().find(query);
        if (hit == null) {
            missedQuery = query;
            missedAt = System.currentTimeMillis();
            return;
        }
        missedQuery = null;
        searchedFor = query;
        found = new Found(continent, hit.kind(),
                hit.kind() == MapSearch.Kind.TERRITORY ? hit.focus().number() : hit.name(),
                hit.x(), hit.z(), System.currentTimeMillis());
        // The hex, or the town's seat, becomes the selection: its card comes up and the waypoint
        // and copy buttons act on it, so a search is one keystroke from walking there.
        selected = hit.focus();
        selectedNumber = selected == null ? null : selected.number();
        closePin();
        flyTo(hit);
        if (waypointButton != null) {
            waypointButton.setMessage(waypointLabel());
        }
    }

    /** Glides the view to frame a result: a hex filling a good part of the window, a town all of it. */
    private void flyTo(MapSearch.Hit hit) {
        double centerX = hit.x();
        double centerZ = hit.z();
        double zoom;
        if (hit.territories().isEmpty()) {
            zoom = Math.min(view.targetZoom(), SEARCH_MARKER_ZOOM);
        } else {
            TerrainDownload.Bounds bounds = TerrainDownload.Bounds.of(hit.territories());
            double fill = hit.territories().size() == 1 ? SEARCH_FILL_ONE : SEARCH_FILL_TOWN;
            zoom = Math.max((bounds.maxX() - bounds.minX()) / (mapWidth * fill),
                    (bounds.maxZ() - bounds.minZ()) / (mapHeight * fill));
            // The middle of the shape rather than its marker, so all of it is in the window.
            centerX = (bounds.minX() + bounds.maxX()) / 2;
            centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        }
        view.flyTo(clampZoom(zoom), centerX, centerZ);
        centred = true;
        rememberView();
    }

    /** The territories the search has lit, as this frame's snapshot describes them. */
    private List<MapTerritory> litTerritories() {
        if (found == null || found.continent() != continent) {
            return List.of();
        }
        List<MapTerritory> lit = new ArrayList<>();
        for (MapTerritory territory : territories()) {
            boolean match = found.kind() == MapSearch.Kind.TERRITORY
                    ? found.key().equals(territory.number())
                    : !territory.neutral() && territory.owner().equalsIgnoreCase(found.key());
            if (match) {
                lit.add(territory);
            }
        }
        return lit;
    }

    /** The wash over what was found: one texture, placed and scaled like a layer of ground. */
    private void drawHighlight(GuiGraphicsExtractor context, List<MapTerritory> lit) {
        if (lit.isEmpty()) {
            highlight.clear();
            return;
        }
        // Painted for the zoom being headed to, so a flight paints it once rather than all the way.
        highlight.ensure(lit, view.targetZoom());
        if (!highlight.ready()) {
            return;
        }
        float scale = (float) (highlight.blocksPerTexel() / blocksPerPixel);
        if (scale <= 0 || !Float.isFinite(scale)) {
            return;
        }
        var matrices = context.pose();
        matrices.pushMatrix();
        matrices.translate((float) screenXExact(highlight.left()), (float) screenYExact(highlight.top()));
        matrices.scale(scale, scale);
        context.blit(RenderPipelines.GUI_TEXTURED, highlight.id(), 0, 0, 0f, 0f,
                highlight.width(), highlight.height(), highlight.width(), highlight.height(),
                highlight.width(), highlight.height(), highlightTint());
        matrices.popMatrix();
    }

    /**
     * How strongly the light shows: full for a moment and pulsing so the eye finds it, then a
     * gentle wash that leaves the colours underneath readable.
     */
    private int highlightTint() {
        double age = found == null ? SEARCH_PULSE_SECONDS
                : (System.currentTimeMillis() - found.at()) / 1000.0;
        double strength = SEARCH_REST_STRENGTH;
        if (age < SEARCH_PULSE_SECONDS) {
            double pulse = 0.5 + 0.5 * Math.cos(age / SEARCH_PULSE_PERIOD * 2 * Math.PI);
            strength += (1 - SEARCH_REST_STRENGTH) * pulse * (1 - age / SEARCH_PULSE_SECONDS);
        }
        return (int) Math.round(255 * strength) << 24 | 0xFFFFFF;
    }

    /** A town that holds no ground is only a marker, so the light is a ring around where it stands. */
    private void drawFoundMarker(GuiGraphicsExtractor context) {
        if (found == null || found.continent() != continent || found.kind() != MapSearch.Kind.TOWN) {
            return;
        }
        int x = screenXOf(found.x());
        int y = screenYOf(found.z());
        int r = 7;
        int colour = highlightTint();
        context.fill(x - r, y - r, x + r + 1, y - r + 2, colour);
        context.fill(x - r, y + r - 1, x + r + 1, y + r + 1, colour);
        context.fill(x - r, y - r, x - r + 2, y + r + 1, colour);
        context.fill(x + r - 1, y - r, x + r + 1, y + r + 1, colour);
    }

    /** Whether the pointer is on the field or the line under it, where the map should not react. */
    private boolean overSearch(double screenX, double screenY) {
        if (!searchField.isVisible()) {
            return false;
        }
        int x = searchField.getX();
        int y = searchField.getY();
        int bottom = y + searchField.getHeight() + (searchLine() != null ? 14 : 0);
        return screenX >= x && screenX < x + searchField.getWidth() && screenY >= y && screenY < bottom;
    }

    private void drawSearch(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        searchField.extractRenderState(context, mouseX, mouseY, delta);
        Component line = searchLine();
        if (line == null) {
            return;
        }
        boolean missed = missedQuery != null;
        int width = Math.min(this.font.width(line) + 8, mapWidth - SEARCH_INSET * 2);
        int x = Math.max(mapX + SEARCH_INSET, searchField.getX() + searchField.getWidth() - width);
        int y = searchField.getY() + searchField.getHeight() + 2;
        MapStyle.card(context, x, y, width, 12, missed ? GuiTheme.BAD : GuiTheme.MUTED);
        context.text(this.font, line, x + 4, y + 2, missed ? GuiTheme.BAD : GuiTheme.TEXT);
    }

    /**
     * The line under the field: what Enter will go to while typing, or that nothing was found.
     *
     * <p>Saying where Enter goes before it is pressed is what makes a loose match safe — typing
     * "forest" shows which Forest it will be, and a wrong guess is corrected before it is made.
     */
    private Component searchLine() {
        if (missedQuery != null) {
            if (System.currentTimeMillis() - missedAt < SEARCH_MISS_MILLIS) {
                return Component.translatable("betterloka.map.search_none", missedQuery,
                        continent.displayName());
            }
            missedQuery = null;
        }
        String query = searchField.getValue();
        if (!searchField.isFocused() || query.isBlank() || territories().isEmpty()) {
            return null;
        }
        if (found != null && query.trim().equals(searchedFor)) {
            return null;
        }
        if (!query.equals(suggestedFor) || suggestedFrom != snapshot) {
            suggestion = searchIndex().find(query);
            suggestedFor = query;
            suggestedFrom = snapshot;
        }
        if (suggestion == null) {
            return Component.translatable("betterloka.map.search_nothing");
        }
        return Component.translatable("betterloka.map.search_enter", describe(suggestion));
    }

    private Component describe(MapSearch.Hit hit) {
        if (hit.kind() == MapSearch.Kind.TOWN) {
            return Component.translatable("betterloka.map.search_town", hit.name(),
                    hit.territories().size());
        }
        MapTerritory territory = hit.focus();
        return territory.neutral()
                ? Component.translatable("betterloka.map.search_neutral", territory.label())
                : Component.translatable("betterloka.map.search_held", territory.label(), territory.owner());
    }

    // --- development ---

    /**
     * Presses a zoom button, for the screenshot driver.
     *
     * <p>Needed because the two are painted rather than added as widgets — {@code drawZoomButtons}
     * paints them and {@code mouseClicked} claims the clicks — so a driver that hunts for a button
     * labelled {@code +} finds nothing and silently photographs an unzoomed map, which is exactly
     * what the first run of this did.
     */
    public void devZoom(boolean in) {
        int y = in ? zoomInY() : zoomOutY();
        double x = zoomButtonX() + MapStyle.ZOOM_BUTTON_SIZE / 2.0;
        mouseClicked(new MouseButtonEvent(x, y + MapStyle.ZOOM_BUTTON_SIZE / 2.0,
                new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
                false);
    }

    /**
     * The line the download shows for a given pointer, resolved to plain text.
     *
     * <p>Only the driver uses it, and only because the hover state cannot be reached otherwise: the
     * windowing layer will not move the pointer without input focus, which a headless display never
     * grants, so the
     * one line a player reads before agreeing to spend a minute and fifty megabytes would otherwise
     * go to release unread.
     */
    public String devDownloadNote(boolean hovering) {
        int x = hovering ? zoomButtonX() + MapStyle.ZOOM_BUTTON_SIZE / 2 : -1;
        int y = hovering ? downloadY() + MapStyle.ZOOM_BUTTON_SIZE / 2 : -1;
        Component note = downloadNote(x, y);
        return note == null ? null : note.getString();
    }

    /** Presses the download button, for the screenshot driver; it is painted, not a widget. */
    public void devPressDownload() {
        mouseClicked(new MouseButtonEvent(zoomButtonX() + MapStyle.ZOOM_BUTTON_SIZE / 2.0,
                downloadY() + MapStyle.ZOOM_BUTTON_SIZE / 2.0,
                new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)),
                false);
    }

    /** Development only: which continent the screen is showing. */
    public Continent devContinent() {
        return continent;
    }

    /** Development only: whether a right click's coordinate bubble is up. */
    public boolean devPinned() {
        return pinned;
    }

    /** Development only: the zoom the view is heading for. */
    public double devTargetZoom() {
        return view.targetZoom();
    }

    /** Development only: the middle the view is heading for, as {x, z}. */
    public double[] devTargetCenter() {
        return new double[] {view.targetCenterX(), view.targetCenterZ()};
    }

    /** Development only: what is selected, by its label, or {@code null}. */
    public String devSelectedLabel() {
        return selected == null ? null : selected.label();
    }

    /** Development only: how many territories the search has lit. */
    public int devLitCount() {
        return litTerritories().size();
    }

    /** Development only: what the line under the search field says right now, as plain text. */
    public String devSearchLine() {
        Component line = searchLine();
        return line == null ? null : line.getString();
    }

    /**
     * Development only: types a query and presses Enter, through the field and the key handler a
     * person's keystrokes go through — clicking the field first, as a person would.
     */
    public void devSearch(String query) {
        double x = searchField.getX() + searchField.getWidth() / 2.0;
        double y = searchField.getY() + searchField.getHeight() / 2.0;
        var left = new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0);
        mouseClicked(new MouseButtonEvent(x, y, left), false);
        mouseReleased(new MouseButtonEvent(x, y, left));
        searchField.setValue("");
        for (int i = 0; i < query.length(); i++) {
            charTyped(new net.minecraft.client.input.CharacterEvent(query.charAt(i)));
        }
        keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
    }

    /** The zoom being drawn right now, part-way through an animation and all. */
    public double devBlocksPerPixel() {
        return blocksPerPixel;
    }

    /**
     * Where a territory currently sits on screen, or {@code null} if none matches.
     *
     * <p>Only the screenshot driver uses this: it has to put the pointer on a held territory and on
     * neutral ground to photograph both cards, and the mapping from a territory to a pixel lives
     * here. {@link #zoomButtonX()} and {@link #zoomInY()} are what it aims at for the zoom buttons.
     */
    public int[] devPointAt(java.util.function.Predicate<MapTerritory> match) {
        for (MapTerritory territory : territories()) {
            if (!match.test(territory)) {
                continue;
            }
            int x = screenXOf(territory.centerX());
            int y = screenYOf(territory.centerZ());
            // The middle itself has to be grabbable, not merely the territory visible: a wide hex
            // can overlap the window while its centre sits above the top edge, and a press there
            // is not on the map at all. Nor may it land on the zoom buttons, which take the click.
            if (!insideMap(x, y) || overButton(x, y, zoomInY()) || overButton(x, y, zoomOutY())) {
                continue;
            }
            return new int[] {x, y};
        }
        return null;
    }

    /** Development only: the middle of the zoom-in button, for the screenshot driver to click. */
    public int[] devZoomInButton() {
        return new int[] {zoomButtonX() + MapStyle.ZOOM_BUTTON_SIZE / 2,
                zoomInY() + MapStyle.ZOOM_BUTTON_SIZE / 2};
    }

    /** Development only: the middle of the coordinate bubble's Copy button, if it is showing. */
    public int[] devCopyButton() {
        if (!pinned) {
            return null;
        }
        return new int[] {pinBubbleX() + pinBubbleWidth() / 2,
                pinBubbleY() + MapStyle.CARD_PADDING + MapStyle.CARD_ROW_HEIGHT + 3
                        + MapStyle.PIN_BUTTON_HEIGHT / 2};
    }

    /** Development only: the middle of the zoom-out button. */
    public int[] devZoomOutButton() {
        return new int[] {zoomButtonX() + MapStyle.ZOOM_BUTTON_SIZE / 2,
                zoomOutY() + MapStyle.ZOOM_BUTTON_SIZE / 2};
    }

    // --- actions ---

    private void toggleWaypoint() {
        if (selected == null) {
            return;
        }
        BetterLokaClient.map().toggle(at(selected));
    }

    private void copyCoordinates() {
        if (selected == null || this.minecraft == null) {
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(String.format(Locale.ROOT, "%d %d %d",
                Math.round(selected.centerX()), 64, Math.round(selected.centerZ())));
    }

    @Override
    public void onClose() {
        rememberView();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        // Closed by any route — the map key, a teleport, a disconnect — the texture goes with it.
        highlight.clear();
        super.removed();
    }
}
