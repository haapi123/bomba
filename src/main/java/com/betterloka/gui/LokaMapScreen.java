package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.map.Continent;
import com.betterloka.map.MapTerrain;
import com.betterloka.map.MapTerritory;
import com.betterloka.map.MapTown;
import com.betterloka.map.MapDataStore;
import com.betterloka.map.Waypoint;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

    /** The view: which world point is in the middle, and how tight the zoom is. */
    private double centerX;
    private double centerZ;
    private double blocksPerPixel = MapStyle.MAX_BLOCKS_PER_PIXEL;
    private boolean centred;

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

    public LokaMapScreen(Screen parent) {
        super(Text.translatable("betterloka.module.loka_map"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 20, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
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
            addDrawableChild(ButtonWidget.builder(tabLabel(target), button -> select(target))
                    .dimensions(x, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            x += tabWidth + 4;
        }

        // One row, so the map gets the rest of the screen. What is selected is described on the map
        // itself the way Loka's own does it, rather than in a panel eating half the height.
        int bottomY = this.height - 26;
        int quarter = (width - 12) / 4;
        addDrawableChild(ButtonWidget.builder(waypointLabel(), button -> {
                    toggleWaypoint();
                    button.setMessage(waypointLabel());
                })
                .dimensions(left, bottomY, quarter, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.map.copy"),
                        button -> copyCoordinates())
                .dimensions(left + quarter + 4, bottomY, quarter, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.map.recenter"),
                        button -> fitToContinent())
                .dimensions(left + (quarter + 4) * 2, bottomY, quarter, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(left + (quarter + 4) * 3, bottomY,
                        left + width - (left + (quarter + 4) * 3), 20).build());

    }

    private Text tabLabel(Continent value) {
        Text label = Text.literal(value.displayName());
        return value == continent ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    private Text waypointLabel() {
        if (selected == null) {
            return Text.translatable("betterloka.map.waypoint");
        }
        boolean set = BetterLokaClient.map().isSet(continent.world(), selected.label());
        return Text.translatable(set ? "betterloka.map.waypoint_off" : "betterloka.map.waypoint");
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
        closePin();
        centred = false;
        clearAndInit();
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
            VIEWS.put(continent, new View(centerX, centerZ, blocksPerPixel));
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
            centerX = saved.centerX();
            centerZ = saved.centerZ();
            blocksPerPixel = clampZoom(saved.blocksPerPixel());
            centred = true;
            return;
        }
        fitToContinent();
    }

    /** Frames the whole continent, for opening it and for the button that gets back there. */
    private void fitToContinent() {
        centerX = continent.centerX();
        centerZ = continent.centerZ();
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
            blocksPerPixel = clampZoom(Math.max(2 * halfWidth / mapWidth,
                    2 * halfHeight / mapHeight) * MapStyle.FIT_MARGIN);
        }
        rememberView();
    }

    private static double clampZoom(double value) {
        return Math.max(MapStyle.MIN_BLOCKS_PER_PIXEL,
                Math.min(MapStyle.MAX_BLOCKS_PER_PIXEL, value));
    }

    /** Zooms about a point on screen, so whatever is under it stays under it. */
    private void zoomAbout(double screenX, double screenY, boolean in) {
        double worldUnderX = worldXAt(screenX);
        double worldUnderZ = worldZAt(screenY);

        double before = blocksPerPixel;
        blocksPerPixel = clampZoom(in ? blocksPerPixel / MapStyle.ZOOM_STEP
                : blocksPerPixel * MapStyle.ZOOM_STEP);
        double factor = blocksPerPixel / before;

        centerX = worldUnderX + (centerX - worldUnderX) * factor;
        centerZ = worldUnderZ + (centerZ - worldUnderZ) * factor;
        rememberView();
    }

    private boolean canZoomIn() {
        return blocksPerPixel > MapStyle.MIN_BLOCKS_PER_PIXEL + 1e-9;
    }

    private boolean canZoomOut() {
        return blocksPerPixel < MapStyle.MAX_BLOCKS_PER_PIXEL - 1e-9;
    }

    private double worldLeft() {
        return centerX - mapWidth * blocksPerPixel / 2;
    }

    private double worldTop() {
        return centerZ - mapHeight * blocksPerPixel / 2;
    }

    private int screenXOf(double worldX) {
        return mapX + (int) Math.round((worldX - worldLeft()) / blocksPerPixel);
    }

    private int screenYOf(double worldZ) {
        return mapY + (int) Math.round((worldZ - worldTop()) / blocksPerPixel);
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

    private boolean overButton(double screenX, double screenY, int buttonY) {
        int x = zoomButtonX();
        return screenX >= x && screenX < x + MapStyle.ZOOM_BUTTON_SIZE
                && screenY >= buttonY && screenY < buttonY + MapStyle.ZOOM_BUTTON_SIZE;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 1) {
            return rightClick(click.x(), click.y());
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
        if (insideMap(click.x(), click.y())) {
            dragging = true;
            dragOriginX = click.x();
            dragOriginY = click.y();
            dragStartX = centerX;
            dragStartZ = centerZ;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (dragging) {
            centerX = dragStartX - (click.x() - dragOriginX) * blocksPerPixel;
            centerZ = dragStartZ - (click.y() - dragOriginY) * blocksPerPixel;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging) {
            dragging = false;
            rememberView();
            // A press that did not move is a click, and picks the territory under it.
            if (Math.abs(click.x() - dragOriginX) <= DRAG_SLOP
                    && Math.abs(click.y() - dragOriginY) <= DRAG_SLOP) {
                selected = territoryAt(click.x(), click.y());
                selectedNumber = selected == null ? null : selected.number();
                clearAndInit();
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

    // --- drawing ---

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10,
                GuiTheme.TEXT);

        int width = contentWidth();
        int left = contentLeft();

        mapX = left + MAP_MARGIN;
        mapY = MAP_TOP;
        mapWidth = width - MAP_MARGIN * 2;
        mapHeight = Math.max(60, this.height - 32 - MAP_TOP);

        GuiTheme.panel(context, left, MAP_TOP - 4, width, mapHeight + 8);

        // One read per frame: the store may swap the snapshot under us at any moment, and half a
        // frame drawn from each would tear.
        snapshot = BetterLokaClient.mapData().snapshot(continent);
        selected = resolveSelection();

        if (territories().isEmpty()) {
            centered(context, "betterloka.map.loading", MAP_TOP + mapHeight / 2, GuiTheme.MUTED);
            return;
        }

        if (!centred) {
            centreOnContinent();
        }
        hovered = dragging ? null : territoryAt(mouseX, mouseY);
        drawMap(context, mouseX, mouseY);

        // Outside the map's scissor: the card may reach past the panel, and should.
        // While the bubble is up it is what the pointer is about; two panels over one spot is
        // just noise.
        MapTerritory describing = pinned ? null : hovered != null ? hovered : selected;
        if (describing != null) {
            drawInfoCard(context, describing, mouseX, mouseY, hovered != null);
        }
        drawPin(context, mouseX, mouseY);
    }

    private void centered(DrawContext context, String key, int y, int color) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(key),
                this.width / 2, y, color);
    }

    private void drawMap(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(mapX, mapY, mapX + mapWidth, mapY + mapHeight);

        // Opaque, and the colour of deep water: Loka's own draws its hexes over sea.
        context.fill(mapX, mapY, mapX + mapWidth, mapY + mapHeight, MapStyle.OCEAN);
        drawTerrain(context);

        // Fills first, then every border, then the markers. A neighbour's fill drawn after a border
        // paints over it, and the hexes run together into one blob.
        for (MapTerritory territory : territories()) {
            if (offScreen(territory)) {
                continue;
            }
            int alpha = territory == selected ? MapStyle.FILL_ALPHA_SELECTED
                    : territory.seat() ? MapStyle.FILL_ALPHA_SEAT
                    : territory.neutral() ? MapStyle.FILL_ALPHA_NEUTRAL : MapStyle.FILL_ALPHA_OWNED;
            fill(context, territory, (alpha << 24) | territory.fillColor());
        }

        for (MapTerritory territory : territories()) {
            if (!territory.neutral() || offScreen(territory)) {
                continue;
            }
            drawBorder(context, territory, MapStyle.BORDER_NEUTRAL,
                    MapStyle.BORDER_NEUTRAL_WIDTH, MapStyle.BORDER_NEUTRAL_WIDTH, false);
        }
        for (MapTerritory territory : territories()) {
            if (territory.neutral() || offScreen(territory)) {
                continue;
            }
            int color = 0xFF000000 | snapshot.palette().colorOf(territory.owner());
            // A town's own hex is ringed all the way round rather than having its seams thinned
            // away: it is the thing on the map somebody is looking for.
            int inner = territory.seat() ? MapStyle.BORDER_SEAT_WIDTH : MapStyle.BORDER_INNER_WIDTH;
            int outer = territory.seat() ? MapStyle.BORDER_SEAT_WIDTH : MapStyle.BORDER_OUTER_WIDTH;
            drawBorder(context, territory, color, inner, outer, true);
        }
        // Last, so the selection is never buried under a neighbour's border.
        if (selected != null && !offScreen(selected)) {
            drawBorder(context, selected, MapStyle.BORDER_SELECTED,
                    MapStyle.BORDER_SELECTED_WIDTH, MapStyle.BORDER_SELECTED_WIDTH, false);
        }

        for (MapTerritory territory : territories()) {
            if (!offScreen(territory)) {
                drawIcon(context, territory);
            }
        }

        drawZoomButtons(context, mouseX, mouseY);
        drawViewNote(context);
        context.disableScissor();
    }

    /** Whether a territory is entirely outside the window, and so not worth drawing at all. */
    private boolean offScreen(MapTerritory territory) {
        return screenXOf(territory.maxX()) < mapX
                || screenXOf(territory.minX()) > mapX + mapWidth
                || screenYOf(territory.maxZ()) < mapY
                || screenYOf(territory.minZ()) > mapY + mapHeight;
    }

    private void drawZoomButtons(DrawContext context, int mouseX, int mouseY) {
        MapStyle.button(context, this.textRenderer, zoomButtonX(), zoomInY(),
                MapStyle.ZOOM_BUTTON_SIZE, "+",
                overButton(mouseX, mouseY, zoomInY()), canZoomIn());
        MapStyle.button(context, this.textRenderer, zoomButtonX(), zoomOutY(),
                MapStyle.ZOOM_BUTTON_SIZE, "-",
                overButton(mouseX, mouseY, zoomOutY()), canZoomOut());
    }

    /**
     * Loka's own ground, in two layers.
     *
     * <p>The coarse one covers the whole continent in a few dozen tiles, so the island is there
     * complete the moment the map opens. The sharp one is drawn over it and only for the window
     * being looked at. Where a sharp tile has not arrived the coarse one is left showing, which is
     * why closing in never flashes holes.
     */
    private void drawTerrain(DrawContext context) {
        MapTerrain terrain = BetterLokaClient.mapTerrain();
        terrain.beginFrame();

        drawTerrainLayer(context, terrain, MapTerrain.BASE_LEVEL);
        int detail = MapTerrain.levelFor(blocksPerPixel);
        if (detail < MapTerrain.BASE_LEVEL) {
            drawTerrainLayer(context, terrain, detail);
        }
    }

    private void drawTerrainLayer(DrawContext context, MapTerrain terrain, int level) {
        int step = 1 << level;
        int firstX = MapTerrain.tileXAt(worldLeft(), level);
        int lastX = MapTerrain.tileXAt(worldXAt(mapX + mapWidth), level);
        int firstY = MapTerrain.tileYAt(worldZAt(mapY + mapHeight), level);
        int lastY = MapTerrain.tileYAt(worldTop(), level);

        double blocks = MapTerrain.blocksPerTile(level);
        for (int tileX = firstX; tileX <= lastX; tileX += step) {
            for (int tileY = firstY; tileY <= lastY; tileY += step) {
                var id = terrain.tile(continent, level, tileX, tileY);
                if (id == null) {
                    continue;
                }
                int x = screenXOf(MapTerrain.tileWorldX(tileX));
                int y = screenYOf(MapTerrain.tileWorldZ(tileY));
                // Sized from the far corner rather than by rounding the width, or neighbouring
                // tiles disagree by a pixel and the ground is drawn with a grid of seams through it.
                int width = screenXOf(MapTerrain.tileWorldX(tileX) + blocks) - x;
                int height = screenYOf(MapTerrain.tileWorldZ(tileY) + blocks) - y;
                if (width <= 0 || height <= 0) {
                    continue;
                }
                context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                        x, y, 0f, 0f, width, height,
                        MapTerrain.TILE_PIXELS, MapTerrain.TILE_PIXELS,
                        MapTerrain.TILE_PIXELS, MapTerrain.TILE_PIXELS, 0xFFFFFFFF);
            }
        }
    }

    private void drawIcon(DrawContext context, MapTerritory territory) {
        double onScreenWidth = (territory.maxX() - territory.minX()) / blocksPerPixel;
        if (onScreenWidth < MapStyle.ICON_SIZE + 4) {
            return;
        }
        int centreX = screenXOf(territory.centerX());
        int centreY = screenYOf(territory.centerZ());

        var id = BetterLokaClient.mapIcons().get(territory.icon(), continent);
        if (id != null) {
            context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                    centreX - MapStyle.ICON_SIZE / 2, centreY - MapStyle.ICON_SIZE / 2,
                    0f, 0f, MapStyle.ICON_SIZE, MapStyle.ICON_SIZE,
                    MapStyle.ICON_SIZE, MapStyle.ICON_SIZE, 0xFFFFFFFF);
        }

        // What the hex is worth, on the hex — only where Loka publishes it, and only when there is
        // room for it to be read rather than to be clutter.
        if (territory.hasConquestPoints() && onScreenWidth >= MapStyle.CP_ON_HEX_MIN_WIDTH) {
            String points = territory.conquestPoints() + " CP";
            context.drawTextWithShadow(this.textRenderer, points,
                    centreX - this.textRenderer.getWidth(points) / 2,
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
    private void fill(DrawContext context, MapTerritory territory, int color) {
        double[] xs = territory.xs();
        double[] zs = territory.zs();

        int top = Math.max(mapY, screenYOf(territory.minZ()));
        int bottom = Math.min(mapY + mapHeight - 1, screenYOf(territory.maxZ()));
        double[] crossings = new double[xs.length];

        for (int row = top; row <= bottom; row++) {
            double worldZ = worldZAt(row + 0.5);
            int found = 0;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((zs[i] > worldZ) != (zs[j] > worldZ)) {
                    crossings[found++] =
                            xs[i] + (xs[j] - xs[i]) * (worldZ - zs[i]) / (zs[j] - zs[i]);
                }
            }
            if (found < 2) {
                continue;
            }
            java.util.Arrays.sort(crossings, 0, found);

            for (int pair = 0; pair + 1 < found; pair += 2) {
                int from = Math.max(mapX, screenXOf(crossings[pair]));
                int to = Math.min(mapX + mapWidth, screenXOf(crossings[pair + 1]));
                if (to > from) {
                    context.fill(from, row, to, row + 1, color);
                }
            }
        }
    }

    /**
     * One territory's outline, thin where it meets its own town and heavy where the holding ends.
     *
     * <p>Loka's map has no notion of a town's outer boundary, so six hexes held by one town read as
     * six separate claims. Drawing the seams between them faintly and the rest heavily is what makes
     * a holding read as one shape.
     */
    private void drawBorder(DrawContext context, MapTerritory territory, int color,
                            int innerWidth, int outerWidth, boolean rimOuter) {
        double[] xs = territory.xs();
        double[] zs = territory.zs();
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean inner = snapshot.borders().isInternal(xs[j], zs[j], xs[i], zs[i]);
            int width = inner ? innerWidth : outerWidth;
            if (rimOuter && !inner) {
                line(context, xs[j], zs[j], xs[i], zs[i], MapStyle.BORDER_RIM,
                        MapStyle.BORDER_RIM_WIDTH);
            }
            line(context, xs[j], zs[j], xs[i], zs[i], color, width);
        }
    }

    private void line(DrawContext context, double x1, double z1, double x2, double z2,
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
        }
    }

    /** Where the middle of the view sits — labelled, so it cannot be read as a territory's own. */
    private void drawViewNote(DrawContext context) {
        Text note = Text.translatable("betterloka.map.view",
                Math.round(centerX), Math.round(centerZ));
        context.drawTextWithShadow(this.textRenderer, note,
                mapX + 4, mapY + mapHeight - 10, GuiTheme.MUTED);

        // Old data is shown rather than hidden, and said so rather than passed off as current.
        if (snapshot.stale(System.currentTimeMillis())) {
            Text age = Text.translatable("betterloka.map.as_of", TimeFormat.ago(snapshot.fetchedAt()));
            context.drawTextWithShadow(this.textRenderer, age,
                    mapX + mapWidth - this.textRenderer.getWidth(age) - 4,
                    mapY + mapHeight - 10, GuiTheme.LIVE);
        }
    }

    // --- the information card ---

    private enum Kind { HEADER, OWNER, SEAT, SEPARATOR, ROW, SMALL }

    private record CardLine(Kind kind, Text left, Text right, int leftColor, int rightColor,
                            String icon) {
        static CardLine header(Text text) {
            return new CardLine(Kind.HEADER, text, null, GuiTheme.TEXT, 0, null);
        }

        static CardLine owner(Text text, int color) {
            return new CardLine(Kind.OWNER, text, null, color, 0, null);
        }

        static CardLine seat(Text text, String icon) {
            return new CardLine(Kind.SEAT, text, null, GuiTheme.TEXT, 0, icon);
        }

        static CardLine separator() {
            return new CardLine(Kind.SEPARATOR, null, null, 0, 0, null);
        }

        static CardLine row(Text label, Text value, int valueColor) {
            return new CardLine(Kind.ROW, label, value, GuiTheme.MUTED, valueColor, null);
        }

        static CardLine small(Text text) {
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
        lines.add(CardLine.header(Text.literal(territory.label()).formatted(Formatting.BOLD)));

        if (territory.neutral()) {
            lines.add(CardLine.owner(Text.translatable("betterloka.map.unclaimed"),
                    GuiTheme.MUTED));
        } else {
            lines.add(CardLine.owner(Text.literal(territory.owner()),
                    0xFF000000 | snapshot.palette().colorOf(territory.owner())));
        }

        MapTown seat = BetterLokaClient.mapData().seatOf(continent, territory);
        if (seat != null) {
            lines.add(CardLine.seat(Text.translatable("betterloka.map.town_seat",
                    Text.literal(seat.name()).formatted(Formatting.BOLD, Formatting.WHITE)),
                    territory.icon()));
            if (seat.hasTitle()) {
                lines.add(CardLine.owner(Text.literal(seat.title()), MapStyle.CONQUEST_POINTS));
            }
        }

        lines.add(CardLine.separator());
        lines.add(CardLine.row(Text.translatable("betterloka.map.coordinates"),
                Text.literal(String.format(Locale.ROOT, "%d, %d",
                        Math.round(territory.centerX()), Math.round(territory.centerZ()))),
                GuiTheme.TEXT));
        lines.add(CardLine.row(Text.translatable("betterloka.map.distance"), distanceText(territory),
                GuiTheme.LIVE));
        if (territory.hasConquestPoints()) {
            lines.add(CardLine.row(Text.translatable("betterloka.map.conquest_points"),
                    Text.translatable("betterloka.map.cp_per_day", territory.conquestPoints()),
                    MapStyle.CONQUEST_POINTS));
        }

        List<CardLine> extra = new ArrayList<>();
        if (territory.alliance() != null) {
            extra.add(CardLine.small(Text.translatable("betterloka.map.alliance_of",
                    territory.alliance())));
        }
        MapTown town = territory.neutral()
                ? null : BetterLokaClient.mapData().town(continent, territory.owner());
        if (town != null) {
            if (town.strength() >= 0) {
                extra.add(CardLine.small(Text.translatable("betterloka.map.strength_of",
                        String.format(Locale.ROOT, "%.0f", town.strength()))));
            }
            extra.add(CardLine.small(Text.translatable("betterloka.map.holdings",
                    town.members(), town.territories())));
        }
        extra.add(CardLine.small(chunksText(territory)));
        if (territory.mutator() != null) {
            extra.add(CardLine.small(Text.translatable("betterloka.map.mutator_of",
                    territory.mutator())));
        }
        if (!extra.isEmpty()) {
            lines.add(CardLine.separator());
            lines.addAll(extra);
        }
        return lines;
    }

    private Text distanceText(MapTerritory territory) {
        if (this.client == null || this.client.player == null) {
            return Text.literal("—");
        }
        double blocks = at(territory).distanceTo(this.client.player.getX(),
                this.client.player.getZ());
        return Text.literal(String.format(Locale.ROOT, "%.0f m", blocks));
    }

    private Text chunksText(MapTerritory territory) {
        if (this.client == null || this.client.player == null) {
            return Text.translatable("betterloka.map.chunks_away", "—");
        }
        return Text.translatable("betterloka.map.chunks_away",
                at(territory).chunksTo(this.client.player.getX(), this.client.player.getZ()));
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
            case SMALL -> MapStyle.smallHeight(this.textRenderer);
        };
    }

    private int lineWidth(CardLine line) {
        return switch (line.kind()) {
            case HEADER, OWNER -> (line.kind() == Kind.OWNER
                    ? MapStyle.SWATCH_WIDTH + MapStyle.SWATCH_GAP : 0)
                    + this.textRenderer.getWidth(line.left());
            case SEAT -> MapStyle.ICON_SIZE + MapStyle.SWATCH_GAP
                    + this.textRenderer.getWidth(line.left());
            case ROW -> this.textRenderer.getWidth(line.left()) + 14
                    + this.textRenderer.getWidth(line.right());
            case SEPARATOR -> 0;
            case SMALL -> MapStyle.smallWidth(this.textRenderer, line.left());
        };
    }

    /**
     * The card, placed beside the cursor and folded back when it would leave the screen.
     *
     * <p>When nothing is under the pointer it describes what was last clicked instead, pinned to the
     * corner — one card either way, rather than a hover panel and a selection panel repeating each
     * other.
     */
    private void drawInfoCard(DrawContext context, MapTerritory territory,
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

    private void drawCardLine(DrawContext context, CardLine line, int x, int y, int inner,
                              MapTerritory territory) {
        switch (line.kind()) {
            case HEADER -> context.drawTextWithShadow(this.textRenderer, line.left(), x, y,
                    line.leftColor());
            case OWNER -> {
                context.fill(x, y, x + MapStyle.SWATCH_WIDTH, y + MapStyle.SWATCH_HEIGHT,
                        line.leftColor());
                context.drawTextWithShadow(this.textRenderer, line.left(),
                        x + MapStyle.SWATCH_WIDTH + MapStyle.SWATCH_GAP, y, line.leftColor());
            }
            case SEAT -> {
                var id = BetterLokaClient.mapIcons().get(line.icon(), continent);
                if (id != null) {
                    context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                            x, y, 0f, 0f, MapStyle.ICON_SIZE, MapStyle.ICON_SIZE,
                            MapStyle.ICON_SIZE, MapStyle.ICON_SIZE, 0xFFFFFFFF);
                }
                context.drawTextWithShadow(this.textRenderer, line.left(),
                        x + MapStyle.ICON_SIZE + MapStyle.SWATCH_GAP, y, line.leftColor());
            }
            case SEPARATOR -> context.fill(x, y + MapStyle.CARD_SECTION_GAP,
                    x + inner, y + MapStyle.CARD_SECTION_GAP + 1, MapStyle.CARD_SEPARATOR);
            case ROW -> {
                context.drawTextWithShadow(this.textRenderer, line.left(), x, y, line.leftColor());
                int valueWidth = this.textRenderer.getWidth(line.right());
                context.drawTextWithShadow(this.textRenderer, line.right(),
                        x + inner - valueWidth, y, line.rightColor());
            }
            case SMALL -> MapStyle.small(context, this.textRenderer, line.left(), x, y,
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
        if (!insideMap(screenX, screenY)) {
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
        if (this.client != null) {
            this.client.keyboard.setClipboard(pinnedCoordinates());
            copiedAt = System.currentTimeMillis();
        }
    }

    private int pinBubbleWidth() {
        int text = this.textRenderer.getWidth(pinnedCoordinates());
        int button = this.textRenderer.getWidth(
                Text.translatable("betterloka.map.copy_here")) + MapStyle.PIN_BUTTON_PADDING * 2;
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

    private void drawPin(DrawContext context, int mouseX, int mouseY) {
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
        context.drawTextWithShadow(this.textRenderer, pinnedCoordinates(),
                x + MapStyle.CARD_PADDING, y + MapStyle.CARD_PADDING, GuiTheme.TEXT);

        boolean copied = copiedAt > 0
                && System.currentTimeMillis() - copiedAt < MapStyle.COPIED_NOTICE_MILLIS;
        MapStyle.wideButton(context, this.textRenderer,
                x + MapStyle.CARD_PADDING,
                y + MapStyle.CARD_PADDING + MapStyle.CARD_ROW_HEIGHT + 3,
                width - MapStyle.CARD_PADDING * 2,
                Text.translatable(copied ? "betterloka.map.copied" : "betterloka.map.copy_here"),
                overCopyButton(mouseX, mouseY), copied);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (pinned && input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            closePin();
            return true;
        }
        return super.keyPressed(input);
    }

    // --- development ---

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
        if (selected == null || this.client == null) {
            return;
        }
        this.client.keyboard.setClipboard(String.format(Locale.ROOT, "%d %d %d",
                Math.round(selected.centerX()), 64, Math.round(selected.centerZ())));
    }

    @Override
    public void close() {
        rememberView();
        this.client.setScreen(parent);
    }
}
