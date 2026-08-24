package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.map.Continent;
import com.betterloka.map.MapTerrain;
import com.betterloka.map.MapTerritory;
import com.betterloka.map.MapTown;
import com.betterloka.map.Waypoint;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loka's map, brought into the game: its outlines, its colours, its markers.
 *
 * <p>It opens zoomed in and is dragged with the mouse, the way its website is used. That is not only
 * a nicety — the ground under the territories is fetched a tile at a time for whatever is on screen,
 * and only a zoomed-in window is few enough tiles to be worth fetching at all.
 */
public class LokaMapScreen extends Screen {
    /** Below the toast strip, which owns the top right and would clip the last tabs. */
    private static final int TAB_ROW_Y = 36;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int MAP_TOP = TAB_ROW_Y + TAB_ROW_HEIGHT + 6;
    private static final int MAP_MARGIN = 8;

    private static final int PANEL_HEIGHT = 50;
    private static final int ROW_HEIGHT = 11;
    private static final int MAX_CONTENT_WIDTH = 620;

    /** Loka's markers, at the size its own map draws them. */
    private static final int ICON_SIZE = 8;

    /** World blocks per screen pixel. Smaller is closer in. */
    private static final double MIN_BLOCKS_PER_PIXEL = 0.5;
    private static final double MAX_BLOCKS_PER_PIXEL = 30;

    /**
     * Where it opens: about six territories across.
     *
     * <p>Picked from the roster rather than by eye. Kalros's territories have a median width of 584
     * blocks, so two blocks to a pixel — the first guess — put a single hex wider than the panel and
     * showed two slabs of colour instead of a map.
     */
    private static final double DEFAULT_BLOCKS_PER_PIXEL = 6;

    /**
     * Past this the ground is not drawn, and that is a limit of Loka's map rather than a choice.
     *
     * <p>Its tiles are 32 blocks each and it has only rendered about a quarter of them even in its
     * best areas. A view wide enough to read as a map — six territories, near 3500 blocks — would be
     * seven thousand tiles for a picture that would still be three-quarters holes. Close in on one
     * territory it is a couple of hundred and worth having, which is where it stays.
     */
    private static final double TERRAIN_UNTIL = 1.5;

    /** Guard against a stray drag while a continent is still loading. */
    private static final int DRAG_SLOP = 2;

    private final Screen parent;

    private Continent continent = Continent.KALROS;
    private List<MapTerritory> territories = List.of();
    private boolean loading;
    private String error;
    private MapTerritory selected;
    private MapTerritory hovered;
    private int generation;

    /** The view: which world point is in the middle, and how tight the zoom is. */
    private double centerX;
    private double centerZ;
    private double blocksPerPixel = DEFAULT_BLOCKS_PER_PIXEL;
    private boolean centred;

    private boolean dragging;
    private double dragStartX;
    private double dragStartZ;
    private double dragOriginX;
    private double dragOriginY;

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

        int buttonsY = this.height - 52;
        int half = (width - 4) / 2;
        addDrawableChild(ButtonWidget.builder(waypointLabel(), button -> {
                    toggleWaypoint();
                    button.setMessage(waypointLabel());
                })
                .dimensions(left, buttonsY, half, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.map.copy"),
                        button -> copyCoordinates())
                .dimensions(left + half + 4, buttonsY, width - half - 4, 20).build());

        int third = (width - 8) / 3;
        int bottomY = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.map.recenter"),
                        button -> fitToContinent())
                .dimensions(left, bottomY, third, 20).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("betterloka.map.clear_waypoints",
                                BetterLokaClient.map().waypoints().size()),
                        button -> {
                            BetterLokaClient.map().clear();
                            clearAndInit();
                        })
                .dimensions(left + third + 4, bottomY, third, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(left + (third + 4) * 2, bottomY, width - (third + 4) * 2, 20).build());

        if (territories.isEmpty() && !loading) {
            load();
        }
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
        continent = target;
        territories = List.of();
        selected = null;
        error = null;
        centred = false;
        blocksPerPixel = DEFAULT_BLOCKS_PER_PIXEL;
        load();
        clearAndInit();
    }

    private void load() {
        loading = true;
        int mine = ++generation;
        BetterLokaClient.map().territories(continent).whenComplete((found, throwable) -> {
            if (this.client == null) {
                return;
            }
            this.client.execute(() -> {
                if (mine != generation) {
                    return;
                }
                loading = false;
                if (throwable != null || found == null) {
                    error = Text.translatable("betterloka.map.unreachable").getString();
                    return;
                }
                territories = found;
            });
        });
    }

    /**
     * Opens where Loka's own map opens.
     *
     * <p>Not the middle of the territory outlines, which sounds right and is not: Loka has rendered
     * ground only in patches, and the geometric centre of Kalros lands on one with no tiles at all.
     * Its published centre lands on the patch it did render.
     */
    private void centreOnContinent() {
        centerX = continent.centerX();
        centerZ = continent.centerZ();
        centred = true;
    }

    /** Zooms out until the whole continent fits, for when panning has lost the plot. */
    private void fitToContinent() {
        if (territories.isEmpty()) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (MapTerritory territory : territories) {
            minX = Math.min(minX, territory.minX());
            maxX = Math.max(maxX, territory.maxX());
            minZ = Math.min(minZ, territory.minZ());
            maxZ = Math.max(maxZ, territory.maxZ());
        }
        centerX = (minX + maxX) / 2;
        centerZ = (minZ + maxZ) / 2;
        blocksPerPixel = Math.min(MAX_BLOCKS_PER_PIXEL, Math.max(
                (maxX - minX) / Math.max(1, mapWidth), (maxZ - minZ) / Math.max(1, mapHeight)));
        centred = true;
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

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
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
            // A press that did not move is a click, and picks the territory under it.
            if (Math.abs(click.x() - dragOriginX) <= DRAG_SLOP
                    && Math.abs(click.y() - dragOriginY) <= DRAG_SLOP) {
                selected = territoryAt(click.x(), click.y());
                clearAndInit();
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!insideMap(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }
        // Zoom about the cursor, so the thing being pointed at stays under the pointer.
        double worldUnderCursorX = worldXAt(mouseX);
        double worldUnderCursorZ = worldZAt(mouseY);

        double factor = vertical > 0 ? 1 / 1.25 : 1.25;
        blocksPerPixel = Math.max(MIN_BLOCKS_PER_PIXEL,
                Math.min(MAX_BLOCKS_PER_PIXEL, blocksPerPixel * factor));

        centerX = worldUnderCursorX + (centerX - worldUnderCursorX) * factor;
        centerZ = worldUnderCursorZ + (centerZ - worldUnderCursorZ) * factor;
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
        for (MapTerritory territory : territories) {
            if (territory.contains(worldX, worldZ)) {
                return territory;
            }
        }
        return null;
    }

    // --- drawing ---

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10,
                GuiTheme.TEXT);

        int width = contentWidth();
        int left = contentLeft();
        int bottom = this.height - 56 - PANEL_HEIGHT - 4;

        mapX = left + MAP_MARGIN;
        mapY = MAP_TOP;
        mapWidth = width - MAP_MARGIN * 2;
        mapHeight = Math.max(40, bottom - MAP_TOP);

        GuiTheme.panel(context, left, MAP_TOP - 4, width, mapHeight + 8);

        if (loading) {
            centered(context, "betterloka.map.loading", MAP_TOP + mapHeight / 2, GuiTheme.MUTED);
        } else if (error != null) {
            context.drawCenteredTextWithShadow(this.textRenderer, error,
                    this.width / 2, MAP_TOP + mapHeight / 2, GuiTheme.BAD);
        } else if (territories.isEmpty()) {
            centered(context, "betterloka.map.empty", MAP_TOP + mapHeight / 2, GuiTheme.MUTED);
        } else {
            if (!centred) {
                centreOnContinent();
            }
            hovered = dragging ? null : territoryAt(mouseX, mouseY);
            drawMap(context);
        }

        drawPanel(context, left, this.height - 56 - PANEL_HEIGHT, width);

        if (hovered != null) {
            context.drawOrderedTooltip(this.textRenderer, tooltip(hovered), mouseX, mouseY);
        }
    }

    private void centered(DrawContext context, String key, int y, int color) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(key),
                this.width / 2, y, color);
    }

    private void drawMap(DrawContext context) {
        context.enableScissor(mapX, mapY, mapX + mapWidth, mapY + mapHeight);

        drawTerrain(context);

        // Fill, then every outline, then the markers. A neighbour's fill drawn after a border paints
        // over it, and the hexes run together into one blob.
        boolean overTerrain = blocksPerPixel <= TERRAIN_UNTIL;
        for (MapTerritory territory : territories) {
            int alpha;
            if (territory == selected) {
                alpha = overTerrain ? 0x99 : 0xFF;
            } else if (overTerrain) {
                alpha = territory.neutral() ? 0x3C : 0x66;
            } else {
                alpha = territory.neutral() ? 0xB0 : 0xD8;
            }
            fill(context, territory, (alpha << 24) | territory.fillColor());
        }
        for (MapTerritory territory : territories) {
            drawOutline(context, territory, 0xE0000000 | territory.strokeColor());
        }
        if (selected != null) {
            drawOutline(context, selected, 0xFFFFFFFF);
        }
        for (MapTerritory territory : territories) {
            drawIcon(context, territory);
        }
        context.disableScissor();

        drawScaleNote(context);
    }

    /**
     * Loka's own ground, one tile per 32 blocks, for the window on screen.
     *
     * <p>Only close in: zoomed out this would be thousands of tiles for a picture too small to read.
     * A tile that has not arrived, or that Loka never rendered, simply leaves the background showing.
     */
    private void drawTerrain(DrawContext context) {
        if (blocksPerPixel > TERRAIN_UNTIL) {
            return;
        }
        MapTerrain terrain = BetterLokaClient.mapTerrain();
        terrain.beginFrame();

        int firstX = MapTerrain.tileXAt(worldLeft());
        int lastX = MapTerrain.tileXAt(worldXAt(mapX + mapWidth));
        int firstY = MapTerrain.tileYAt(worldZAt(mapY + mapHeight));
        int lastY = MapTerrain.tileYAt(worldTop());

        int size = (int) Math.ceil(MapTerrain.BLOCKS_PER_TILE / blocksPerPixel);
        for (int tileX = firstX; tileX <= lastX; tileX++) {
            for (int tileY = firstY; tileY <= lastY; tileY++) {
                var id = terrain.tile(continent, tileX, tileY);
                if (id == null) {
                    continue;
                }
                int x = screenXOf(MapTerrain.tileWorldX(tileX));
                int y = screenYOf(MapTerrain.tileWorldZ(tileY));
                context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                        x, y, 0f, 0f, size, size, size, size, size, size, 0xFFFFFFFF);
            }
        }
    }

    private void drawIcon(DrawContext context, MapTerritory territory) {
        double onScreenWidth = (territory.maxX() - territory.minX()) / blocksPerPixel;
        if (onScreenWidth < ICON_SIZE + 4) {
            return;
        }
        var id = BetterLokaClient.mapIcons().get(territory.icon(), continent);
        if (id == null) {
            return;
        }
        int x = screenXOf(territory.centerX()) - ICON_SIZE / 2;
        int y = screenYOf(territory.centerZ()) - ICON_SIZE / 2;
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                x, y, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);
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

    private void drawOutline(DrawContext context, MapTerritory territory, int color) {
        double[] xs = territory.xs();
        double[] zs = territory.zs();
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            line(context, xs[j], zs[j], xs[i], zs[i], color);
        }
    }

    private void line(DrawContext context, double x1, double z1, double x2, double z2, int color) {
        int px1 = screenXOf(x1);
        int py1 = screenYOf(z1);
        int px2 = screenXOf(x2);
        int py2 = screenYOf(z2);
        int steps = Math.max(Math.abs(px2 - px1), Math.abs(py2 - py1));
        if (steps > 4000) {
            return;
        }
        for (int step = 0; step <= steps; step++) {
            int px = px1 + (px2 - px1) * step / Math.max(1, steps);
            int py = py1 + (py2 - py1) * step / Math.max(1, steps);
            context.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** A quiet line saying how close in the view is, and where the middle of it sits. */
    private void drawScaleNote(DrawContext context) {
        String note = String.format(Locale.ROOT, "X %d, Z %d",
                Math.round(centerX), Math.round(centerZ));
        if (blocksPerPixel > TERRAIN_UNTIL) {
            note += "  ·  " + Text.translatable("betterloka.map.zoom_hint").getString();
        }
        context.drawTextWithShadow(this.textRenderer, note,
                mapX + 4, mapY + mapHeight - 10, GuiTheme.MUTED);
    }

    private List<net.minecraft.text.OrderedText> tooltip(MapTerritory territory) {
        List<Text> lines = new ArrayList<>();
        MapTown town = territory.neutral()
                ? null
                : BetterLokaClient.map().town(continent, territory.owner());

        lines.add(Text.literal(territory.neutral() ? territory.label() : territory.owner())
                .formatted(Formatting.WHITE));

        if (territory.neutral()) {
            lines.add(Text.translatable("betterloka.map.neutral").formatted(Formatting.GRAY));
        } else if (town != null) {
            String head = town.hasAlliance()
                    ? town.alliance() + " - " + strength(town.strength()) + " strength"
                    : strength(town.strength()) + " strength";
            lines.add(Text.literal(head).formatted(Formatting.GRAY));
            lines.add(Text.literal(town.members() + " members | " + town.territories()
                    + " territories").formatted(Formatting.GRAY));
        } else if (territory.alliance() != null) {
            lines.add(Text.literal(territory.alliance()).formatted(Formatting.GRAY));
        }

        // Only when it says something the heading did not: for neutral ground the heading is
        // already the territory's name.
        if (!territory.neutral()) {
            lines.add(Text.literal(territory.label()).formatted(Formatting.DARK_GRAY));
        }
        if (territory.mutator() != null) {
            lines.add(Text.literal("Mutator: " + territory.mutator())
                    .formatted(Formatting.LIGHT_PURPLE));
        }

        List<net.minecraft.text.OrderedText> ordered = new ArrayList<>(lines.size());
        for (Text line : lines) {
            ordered.add(line.asOrderedText());
        }
        return ordered;
    }

    private static String strength(double value) {
        return value < 0 ? "?" : String.format(Locale.ROOT, "%.0f", value);
    }

    private void drawPanel(DrawContext context, int left, int y, int width) {
        GuiTheme.panel(context, left, y, width, PANEL_HEIGHT);
        int textX = left + 6;
        int textY = y + 6;
        int inner = width - 12;

        if (selected == null) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.map.pick"), textX, textY, GuiTheme.MUTED);
            return;
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.literal(selected.label()).formatted(Formatting.BOLD), textX, textY,
                GuiTheme.ACCENT);

        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                Text.translatable("betterloka.map.owner").getString(),
                selected.neutral()
                        ? Text.translatable("betterloka.map.neutral").getString()
                        : selected.owner(),
                selected.neutral() ? GuiTheme.MUTED : GuiTheme.GOOD);

        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 2, inner,
                Text.translatable("betterloka.map.alliance").getString(),
                selected.alliance() == null ? "—" : selected.alliance(), GuiTheme.TEXT);

        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 3, inner,
                Text.translatable("betterloka.map.coords").getString(),
                String.format(Locale.ROOT, "%d, %d  ·  %s",
                        Math.round(selected.centerX()), Math.round(selected.centerZ()), distance()),
                GuiTheme.LIVE);
    }

    private String distance() {
        if (this.client == null || this.client.player == null) {
            return "—";
        }
        Waypoint at = new Waypoint(selected.label(), continent.world(),
                selected.centerX(), 64, selected.centerZ(), 0, selected.icon());
        double blocks = at.distanceTo(this.client.player.getX(), this.client.player.getZ());
        return String.format(Locale.ROOT, "%.0fm / %d chunks",
                blocks, at.chunksTo(this.client.player.getX(), this.client.player.getZ()));
    }

    private void toggleWaypoint() {
        if (selected == null) {
            return;
        }
        BetterLokaClient.map().toggle(new Waypoint(selected.label(), continent.world(),
                selected.centerX(), 64, selected.centerZ(), selected.fillColor(), selected.icon()));
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
        this.client.setScreen(parent);
    }
}
