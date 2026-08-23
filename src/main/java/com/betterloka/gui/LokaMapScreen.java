package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.map.Continent;
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

import java.util.List;
import java.util.Locale;

/**
 * Loka's own map, drawn from its own territory outlines.
 *
 * <p>Five continents, each a real set of polygons rather than a picture — which is what lets a click
 * land on a territory and mean something. Picking one offers the two things worth doing with a place
 * on a map: mark it so it can be found in the world, or copy its coordinates.
 */
public class LokaMapScreen extends Screen {
    /**
     * Below the toast strip.
     *
     * <p>This screen's tab row runs the full width, so its last two tabs sit under the top right —
     * where Minecraft draws toasts. At the usual 26 an advancement popping mid-look clips the tabs,
     * which is exactly when somebody is reading the map. A toast is 32 tall, so the row starts under
     * one.
     */
    private static final int TAB_ROW_Y = 36;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int MAP_TOP = TAB_ROW_Y + TAB_ROW_HEIGHT + 6;
    private static final int MAP_MARGIN = 12;

    /** Height of the panel under the map that describes the selected territory. */
    private static final int PANEL_HEIGHT = 50;
    private static final int ROW_HEIGHT = 11;

    /** The map is the point of the screen, so everything else is sized around it. */
    private static final int MAX_CONTENT_WIDTH = 560;

    /** Loka's markers are 16 square; half that reads at the scale a whole continent is drawn at. */
    private static final int ICON_SIZE = 8;

    private final Screen parent;

    private Continent continent = Continent.KALROS;
    private List<MapTerritory> territories = List.of();
    private boolean loading;
    private String error;
    private MapTerritory selected;
    /** Only remembered during render; the tooltip is drawn last so nothing clips it. */
    private MapTerritory hovered;

    /** Bumped on every continent change so a slow load cannot overwrite a newer one. */
    private int generation;

    // Where the map was drawn this frame, so a click can be turned back into world coordinates.
    private int mapX;
    private int mapY;
    private int mapWidth;
    private int mapHeight;
    private double worldMinX;
    private double worldMinZ;
    private double worldScale = 1;

    private int contentWidth() {
        return Math.min(this.width - 20, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    public LokaMapScreen(Screen parent) {
        super(Text.translatable("betterloka.module.loka_map"));
        this.parent = parent;
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
            // The last tab takes the rounding, so the row ends flush with the panel below it.
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

        int waypointCount = BetterLokaClient.map().waypoints().size();
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("betterloka.map.clear_waypoints", waypointCount),
                        button -> {
                            BetterLokaClient.map().clear();
                            clearAndInit();
                        })
                .dimensions(left, this.height - 28, 140, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 70, this.height - 28, 140, 20).build());

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

    private void toggleWaypoint() {
        if (selected == null) {
            return;
        }
        BetterLokaClient.map().toggle(new Waypoint(selected.label(), continent.world(),
                selected.centerX(), 64, selected.centerZ(), selected.fillColor()));
    }

    private void copyCoordinates() {
        if (selected == null || this.client == null) {
            return;
        }
        String coordinates = String.format(Locale.ROOT, "%d %d %d",
                Math.round(selected.centerX()), 64, Math.round(selected.centerZ()));
        this.client.keyboard.setClipboard(coordinates);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.x() >= mapX && click.x() < mapX + mapWidth
                && click.y() >= mapY && click.y() < mapY + mapHeight) {
            double worldX = worldMinX + (click.x() - mapX) / worldScale;
            double worldZ = worldMinZ + (click.y() - mapY) / worldScale;
            for (MapTerritory territory : territories) {
                if (territory.contains(worldX, worldZ)) {
                    selected = territory;
                    clearAndInit();
                    return true;
                }
            }
            selected = null;
            clearAndInit();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

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
            hovered = territoryAt(mouseX, mouseY);
            drawMap(context);
        }

        drawPanel(context, left, this.height - 56 - PANEL_HEIGHT, width);

        // Last, and outside the map's scissor, or the panel would clip its own tooltip.
        if (hovered != null) {
            context.drawOrderedTooltip(this.textRenderer, tooltip(hovered), mouseX, mouseY);
        }
    }

    /** @return the territory under a screen position, or {@code null} outside the map. */
    private MapTerritory territoryAt(double screenX, double screenY) {
        if (screenX < mapX || screenX >= mapX + mapWidth
                || screenY < mapY || screenY >= mapY + mapHeight) {
            return null;
        }
        double worldX = worldMinX + (screenX - mapX) / worldScale;
        double worldZ = worldMinZ + (screenY - mapY) / worldScale;
        for (MapTerritory territory : territories) {
            if (territory.contains(worldX, worldZ)) {
                return territory;
            }
        }
        return null;
    }

    /**
     * The card Loka's own map shows on hover: the holder, its alliance and strength, its size.
     *
     * <p>The strength and the counts come from the town's marker, which is only in the marker file —
     * so a map restored from yesterday's cache shows the territory's own lines and stops there,
     * rather than inventing numbers.
     */
    private List<net.minecraft.text.OrderedText> tooltip(MapTerritory territory) {
        List<net.minecraft.text.Text> lines = new java.util.ArrayList<>();
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

        lines.add(Text.literal(territory.label()).formatted(Formatting.DARK_GRAY));
        if (territory.mutator() != null) {
            lines.add(Text.literal("Mutator: " + territory.mutator()).formatted(Formatting.LIGHT_PURPLE));
        }

        List<net.minecraft.text.OrderedText> ordered = new java.util.ArrayList<>(lines.size());
        for (net.minecraft.text.Text line : lines) {
            ordered.add(line.asOrderedText());
        }
        return ordered;
    }

    private static String strength(double value) {
        return value < 0 ? "?" : String.format(Locale.ROOT, "%.0f", value);
    }

    private void centered(DrawContext context, String key, int y, int color) {
        context.drawCenteredTextWithShadow(this.textRenderer, Text.translatable(key),
                this.width / 2, y, color);
    }

    /**
     * Draws the continent to fit the panel, keeping its proportions.
     *
     * <p>Each territory is filled by scanning its own rows: the polygons are concave and up to
     * twenty-six sided, so anything simpler would spill one territory's colour over its neighbour.
     */
    private void drawMap(DrawContext context) {
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

        double spanX = Math.max(1, maxX - minX);
        double spanZ = Math.max(1, maxZ - minZ);
        worldScale = Math.min(mapWidth / spanX, mapHeight / spanZ);
        // Centred, so a continent that is wider than it is tall does not sit against one edge.
        worldMinX = minX - (mapWidth / worldScale - spanX) / 2;
        worldMinZ = minZ - (mapHeight / worldScale - spanZ) / 2;

        context.enableScissor(mapX, mapY, mapX + mapWidth, mapY + mapHeight);

        // Fill, then outline, then icons — in that order, or a neighbour's fill would paint over
        // the border between them and the hexes would run together the way they did before.
        for (MapTerritory territory : territories) {
            int alpha = territory == selected ? 0xFF : (territory.neutral() ? 0xB0 : 0xD8);
            fill(context, territory, (alpha << 24) | territory.fillColor());
        }
        for (MapTerritory territory : territories) {
            drawOutline(context, territory, 0xFF000000 | territory.strokeColor());
        }
        if (selected != null) {
            drawOutline(context, selected, 0xFFFFFFFF);
        }
        for (MapTerritory territory : territories) {
            drawIcon(context, territory);
        }
        context.disableScissor();
    }

    /**
     * The keep or tower Loka draws in the middle of a territory.
     *
     * <p>Absent on the first frames while it downloads, and absent for good if the map cannot be
     * reached — the territory is still drawn and still clickable either way.
     */
    private void drawIcon(DrawContext context, MapTerritory territory) {
        var id = BetterLokaClient.mapIcons().get(territory.icon(), continent);
        if (id == null) {
            return;
        }
        int size = ICON_SIZE;
        int x = mapX + (int) ((territory.centerX() - worldMinX) * worldScale) - size / 2;
        int y = mapY + (int) ((territory.centerZ() - worldMinZ) * worldScale) - size / 2;
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                x, y, 0, 0, size, size, size, size, 0xFFFFFFFF);
    }

    /**
     * Fills one territory, a screen row at a time.
     *
     * <p>Each row asks the polygon's edges where they cross it and fills between the crossings in
     * pairs, rather than testing every pixel for being inside. The difference is not academic: a
     * continent is 143 polygons of up to twenty-six edges over a four-hundred-pixel panel, so the
     * per-pixel version was tens of millions of tests every frame and made the screen a slideshow.
     */
    private void fill(DrawContext context, MapTerritory territory, int color) {
        double[] xs = territory.xs();
        double[] zs = territory.zs();

        int top = Math.max(0, (int) Math.floor((territory.minZ() - worldMinZ) * worldScale));
        int bottom = Math.min(mapHeight - 1, (int) Math.ceil((territory.maxZ() - worldMinZ) * worldScale));
        double[] crossings = new double[xs.length];

        for (int row = top; row <= bottom; row++) {
            double worldZ = worldMinZ + (row + 0.5) / worldScale;

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

            // Crossings come in pairs: the span between the first two is inside, then the next two.
            for (int pair = 0; pair + 1 < found; pair += 2) {
                int from = (int) Math.floor((crossings[pair] - worldMinX) * worldScale);
                int to = (int) Math.ceil((crossings[pair + 1] - worldMinX) * worldScale);
                from = Math.max(0, from);
                to = Math.min(mapWidth, to);
                if (to > from) {
                    context.fill(mapX + from, mapY + row, mapX + to, mapY + row + 1, color);
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
        int px1 = (int) ((x1 - worldMinX) * worldScale);
        int py1 = (int) ((z1 - worldMinZ) * worldScale);
        int px2 = (int) ((x2 - worldMinX) * worldScale);
        int py2 = (int) ((z2 - worldMinZ) * worldScale);
        int steps = Math.max(Math.abs(px2 - px1), Math.abs(py2 - py1));
        for (int step = 0; step <= steps; step++) {
            int px = px1 + (px2 - px1) * step / Math.max(1, steps);
            int py = py1 + (py2 - py1) * step / Math.max(1, steps);
            context.fill(mapX + px, mapY + py, mapX + px + 1, mapY + py + 1, color);
        }
    }

    /** What the selected territory is, and how far away. */
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

        String alliance = selected.alliance() == null ? "—" : selected.alliance();
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 2, inner,
                Text.translatable("betterloka.map.alliance").getString(), alliance, GuiTheme.TEXT);

        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 3, inner,
                Text.translatable("betterloka.map.coords").getString(),
                String.format(Locale.ROOT, "%d, %d  ·  %s",
                        Math.round(selected.centerX()), Math.round(selected.centerZ()), distance()),
                GuiTheme.LIVE);
    }

    /** How far the player is from it, in blocks and chunks. */
    private String distance() {
        if (this.client == null || this.client.player == null) {
            return "—";
        }
        Waypoint at = new Waypoint(selected.label(), continent.world(),
                selected.centerX(), 64, selected.centerZ(), 0);
        double blocks = at.distanceTo(this.client.player.getX(), this.client.player.getZ());
        return String.format(Locale.ROOT, "%.0fm / %d chunks",
                blocks, at.chunksTo(this.client.player.getX(), this.client.player.getZ()));
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
