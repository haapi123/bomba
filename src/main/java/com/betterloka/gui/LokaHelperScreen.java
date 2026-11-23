package com.betterloka.gui;

import com.betterloka.BetterLoka;
import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.helper.GuiScaleController;
import com.betterloka.helper.ImportantPlaces;
import com.betterloka.helper.ImportantWaypointsHud;
import com.betterloka.helper.PlaceIcons;
import com.betterloka.helper.ShulkerScan;
import com.betterloka.map.Continent;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loka Helper, as a window of its own rather than a column of buttons: the sections down a sidebar,
 * each setting on a card that says what it does, with switches that slide.
 *
 * <ul>
 *   <li><b>Waypoints</b> — Loka's continents as islands on a sea, drawn from Loka's own map. Pick one
 *       to see what it marks (its docks, resource island, boss; everything at spawn on Aladra) and to
 *       switch its markers on or off. Every continent starts off.</li>
 *   <li><b>HUD &amp; view</b> — coordinates on screen, see-through lava, and a separate GUI scale for
 *       containers.</li>
 *   <li><b>Shortcuts</b> — {@code /tl} on a key, and the key that moves the coordinates.</li>
 *   <li><b>Duels</b> — joining by itself, the screens before and after, the shared statistics.</li>
 *   <li><b>Shulkers</b> — the Shulker Scanner, with what its colours mean.</li>
 * </ul>
 *
 * <p>Everything is drawn here, not built from the game's buttons: hover, clicks and the cursor are
 * worked out against what was drawn in the frame, which is what lets the islands be pictures and the
 * switches move.
 */
public class LokaHelperScreen extends Screen {
    private static final int MAX_WIDTH = 560;
    private static final int MAX_HEIGHT = 330;
    private static final int HEADER = 30;
    private static final int PAD = 8;
    private static final int NAV_WIDTH = 108;
    private static final int NAV_COMPACT = 30;
    private static final int NAV_ROW = 22;
    private static final int GAP = 4;
    private static final int SWITCH_W = 24;
    private static final int SWITCH_H = 12;
    private static final int CONTROL_H = 16;

    /** The sections, in the order the sidebar lists them. */
    private enum Page {
        WAYPOINTS("waypoints", Items.FILLED_MAP),
        DISPLAY("display", Items.SPYGLASS),
        KEYS("keys", Items.TRIPWIRE_HOOK),
        DUELS("duels", Items.DIAMOND_SWORD),
        SHULKERS("shulkers", Items.SHULKER_BOX);

        final String id;
        final ItemStack icon;

        Page(String id, net.minecraft.world.item.Item icon) {
            this.id = id;
            this.icon = new ItemStack(icon);
        }

        Component label() {
            return Component.translatable("betterloka.helper.nav." + id);
        }
    }

    /**
     * An island on the sea: its picture and where it sits, as fractions of the sea. Loka runs each
     * continent as a world of its own, so there is no real distance between them to keep; they are
     * laid out the way their world keys read — Kalros "north", Garama "south", Ascalon "west" — round
     * Aladra, where every boat leaves from.
     */
    private record Island(ImportantPlaces.Region region, String texture, int textureWidth, int textureHeight,
                          float x, float y, float size) {
        Identifier id() {
            return Identifier.fromNamespaceAndPath("betterloka", "textures/gui/islands/" + texture + ".png");
        }
    }

    private static final List<Island> ISLANDS = List.of(
            new Island(ImportantPlaces.Region.KALROS, "kalros", 360, 246, 0.31f, 0.21f, 0.46f),
            new Island(ImportantPlaces.Region.RIVINA, "rivina", 360, 303, 0.79f, 0.22f, 0.32f),
            new Island(ImportantPlaces.Region.ASCALON, "ascalon", 260, 360, 0.15f, 0.64f, 0.34f),
            new Island(ImportantPlaces.Region.ALADRA, "aladra", 360, 349, 0.49f, 0.52f, 0.21f),
            new Island(ImportantPlaces.Region.BALAK, "balak", 360, 319, 0.81f, 0.63f, 0.28f),
            new Island(ImportantPlaces.Region.GARAMA, "garama", 360, 325, 0.52f, 0.82f, 0.31f));

    /** Which key is being chosen, if any. */
    private enum Listening { TL, COORDS }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static Page lastPage = Page.WAYPOINTS;
    private static ImportantPlaces.Region lastIsland;

    private final Screen parent;
    private Page page = lastPage;
    private ImportantPlaces.Region selected;
    private Listening listening;

    // The window, worked out in init.
    private int wx, wy, ww, wh, navWidth;

    // What this frame drew that can be clicked, and the region clicks and hover are held to.
    private final List<Hit> hits = new ArrayList<>();
    private int clipX0, clipY0, clipX1, clipY1;
    private int mouseX, mouseY;
    private boolean pointing;

    // Animations: each value eases towards where it is going, a little more every frame.
    private final Map<String, Float> anims = new HashMap<>();
    private long lastFrame;
    private float dt;

    // Scrolling: the page's list, and the island panel's list of places.
    private final Map<Page, Integer> scrolls = new HashMap<>();
    private int pageHeight;
    private int pageView;
    private int panelScroll;
    private int panelHeight;
    private int panelView;
    private int panelX, panelY, panelW, panelH;

    private String copied;
    private long copiedAt;

    public LokaHelperScreen(Screen parent) {
        super(Component.translatable("betterloka.module.loka_helper"));
        this.parent = parent;
    }

    private static BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    @Override
    protected void init() {
        ww = Math.min(this.width - 16, MAX_WIDTH);
        wh = Math.min(this.height - 12, MAX_HEIGHT);
        wx = (this.width - ww) / 2;
        wy = (this.height - wh) / 2;
        navWidth = ww < 430 ? NAV_COMPACT : NAV_WIDTH;
        if (selected == null) {
            ImportantPlaces.Region here = ImportantWaypointsHud.currentRegion(this.minecraft);
            selected = here != null ? here : lastIsland != null ? lastIsland : ImportantPlaces.Region.KALROS;
        }
    }

    // --- frame ---

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        long now = System.nanoTime();
        dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        hits.clear();
        pointing = false;
        noClip();

        context.fill(0, 0, this.width, this.height, 0x66000000);
        // The window, with a shadow under it.
        HelperUi.round(context, wx - 1, wy + 2, ww + 2, wh + 2, 6, 0x50000000);
        HelperUi.framed(context, wx, wy, ww, wh, 5, HelperUi.WINDOW, HelperUi.WINDOW_EDGE);
        drawHeader(context);
        drawSidebar(context);

        int cx = wx + navWidth + PAD;
        int cy = wy + HEADER + PAD;
        int cw = ww - navWidth - 2 * PAD;
        int ch = wh - HEADER - 2 * PAD;
        if (page == Page.WAYPOINTS) {
            drawWaypoints(context, cx, cy, cw, ch);
        } else {
            drawList(context, cx, cy, cw, ch);
        }
        if (pointing) {
            context.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void drawHeader(GuiGraphicsExtractor context) {
        // A band a shade lighter than the window, under the title.
        context.fillGradient(wx + 1, wy + 3, wx + ww - 1, wy + HEADER, HelperUi.HEADER_TOP, HelperUi.HEADER_BOTTOM);
        context.fillGradient(wx + 3, wy + 1, wx + ww - 3, wy + 3, HelperUi.HEADER_TOP, HelperUi.HEADER_TOP);
        context.fill(wx + 1, wy + HEADER, wx + ww - 1, wy + HEADER + 1, HelperUi.WINDOW_EDGE);
        drawLogo(context, wx + 8, wy + 5, 20);
        context.text(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), wx + 33, wy + 6, HelperUi.TEXT);
        context.text(this.font, Component.translatable("betterloka.helper.subtitle"), wx + 33, wy + 17, HelperUi.MUTED);

        // Close, top right.
        int size = 16;
        int bx = wx + ww - size - 7;
        int by = wy + (HEADER - size) / 2;
        boolean hover = hovered(bx, by, size, size);
        float t = ease("close", hover ? 1 : 0, 18);
        HelperUi.round(context, bx, by, size, size, 3, HelperUi.mix(0x00000000, 0xFFB3423F, t));
        context.centeredText(this.font, "×", bx + size / 2 + 1, by + 4, HelperUi.mix(HelperUi.MUTED, 0xFFFFFFFF, t));
        hit(bx, by, size, size, this::onClose);

        // Where the player is, beside it.
        ImportantPlaces.Region here = ImportantWaypointsHud.currentRegion(this.minecraft);
        if (here != null && ww >= 380) {
            Component where = Component.translatable("betterloka.helper.important_here",
                    Component.literal(here.displayName()).withStyle(ChatFormatting.GOLD));
            int w = this.font.width(where) + 22;
            int x = bx - 8 - w;
            HelperUi.framed(context, x, by + 1, w, 14, 7, 0xFF141E30, 0xFF2B3A56);
            pin(context, x + 9, by + 11, 0);
            context.text(this.font, where, x + 16, by + 4, HelperUi.MUTED);
        }
    }

    private void drawLogo(GuiGraphicsExtractor context, int x, int y, int size) {
        int scale = Math.max(1, Math.min(6, this.minecraft.getWindow().getGuiScale()));
        int pixels = 26 * scale;
        Identifier logo = Identifier.fromNamespaceAndPath("betterloka", "textures/gui/logo_" + scale + ".png");
        context.blit(RenderPipelines.GUI_TEXTURED, logo, x, y, 0f, 0f, size, size, pixels, pixels, pixels, pixels,
                0xFFFFFFFF);
    }

    private void drawSidebar(GuiGraphicsExtractor context) {
        int x = wx + 1;
        int top = wy + HEADER + 1;
        int bottom = wy + wh - 1;
        context.fill(x, top, x + navWidth - 1, bottom - 4, HelperUi.SIDEBAR);
        context.fill(x + 4, bottom - 4, x + navWidth - 1, bottom - 1, HelperUi.SIDEBAR);
        context.fill(x + navWidth - 1, top, x + navWidth, bottom, HelperUi.WINDOW_EDGE);

        int y = top + 8;
        Page[] pages = Page.values();
        // The gold marker slides to the chosen section.
        float marker = ease("nav", page.ordinal(), 16);
        int markerY = Math.round(y + marker * (NAV_ROW + 2));
        for (Page p : pages) {
            boolean chosen = p == page;
            boolean hover = hovered(x + 4, y, navWidth - 9, NAV_ROW);
            float t = ease("nav:" + p.id, chosen ? 1 : hover ? 0.5f : 0, 16);
            HelperUi.round(context, x + 4, y, navWidth - 9, NAV_ROW, 4, HelperUi.mix(0x00000000, 0xFF1A2640, t));
            context.item(p.icon, x + 8, y + 3);
            if (navWidth > NAV_COMPACT) {
                context.text(this.font, p.label(), x + 29, y + 7, HelperUi.mix(HelperUi.MUTED, HelperUi.TEXT, t));
            } else if (hover) {
                context.setTooltipForNextFrame(this.font, p.label(), mouseX, mouseY);
            }
            Page target = p;
            hit(x + 4, y, navWidth - 9, NAV_ROW, () -> choose(target));
            y += NAV_ROW + 2;
        }
        context.fill(x + 4, markerY + 4, x + 6, markerY + NAV_ROW - 4, HelperUi.GOLD);

        if (navWidth > NAV_COMPACT) {
            String version = "BetterLoka " + BetterLoka.VERSION;
            context.text(this.font, this.font.plainSubstrByWidth(version, navWidth - 14), x + 8, bottom - 13,
                    HelperUi.FAINT);
        }
    }

    private void choose(Page target) {
        if (page != target) {
            page = target;
            lastPage = target;
            listening = null;
        }
    }

    // --- the islands ---

    private void drawWaypoints(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        int head = pageHeader(context, x, y, w, Component.translatable("betterloka.helper.important"),
                Component.translatable("betterloka.helper.page.waypoints"));
        int top = y + head;
        int height = h - head;
        int panel = Math.max(118, Math.min(190, (int) (w * 0.40)));
        int seaW = w - panel - 6;
        drawSea(context, x, top, seaW, height);
        drawIslandPanel(context, x + seaW + 6, top, panel, height);
    }

    private void drawSea(GuiGraphicsExtractor context, int sx, int sy, int sw, int sh) {
        HelperUi.round(context, sx, sy, sw, sh, 4, 0xFF1E3A5E);
        context.fillGradient(sx + 2, sy + 1, sx + sw - 2, sy + sh - 1, 0xFF184675, 0xFF0E2945);
        context.fillGradient(sx + 1, sy + 2, sx + sw - 1, sy + sh - 2, 0xFF184675, 0xFF0E2945);
        clip(sx + 1, sy + 1, sx + sw - 1, sy + sh - 1);
        context.enableScissor(clipX0, clipY0, clipX1, clipY1);

        // Waves drifting slowly across.
        float time = (System.currentTimeMillis() % 1_000_000L) / 1000f;
        for (int row = 0, wy0 = sy + 7; wy0 < sy + sh; row++, wy0 += 13) {
            int shift = (int) (time * 3 + row * 7) % 22;
            for (int wx0 = sx - 22 + shift + (row % 2) * 11; wx0 < sx + sw; wx0 += 22) {
                context.fill(wx0, wy0, wx0 + 6, wy0 + 1, 0x12FFFFFF);
                context.fill(wx0 + 1, wy0 - 1, wx0 + 5, wy0, 0x0AFFFFFF);
            }
        }

        // Where every island is this frame, the boat routes from Aladra first so they pass under.
        Map<ImportantPlaces.Region, int[]> boxes = new HashMap<>();
        for (Island island : ISLANDS) {
            boxes.put(island.region(), islandBox(island, sx, sy, sw, sh));
        }
        int[] hub = boxes.get(ImportantPlaces.Region.ALADRA);
        for (Island island : ISLANDS) {
            if (island.region() == ImportantPlaces.Region.ALADRA) {
                continue;
            }
            int[] b = boxes.get(island.region());
            HelperUi.dotted(context, hub[0] + hub[2] / 2, hub[1] + hub[3] / 2, b[0] + b[2] / 2, b[1] + b[3] / 2, 5,
                    time * 6, 0x38FFFFFF);
        }

        ImportantPlaces.Region here = ImportantWaypointsHud.currentRegion(this.minecraft);
        Map<ImportantPlaces.Region, int[]> drawn = new HashMap<>();
        for (Island island : ISLANDS) {
            drawn.put(island.region(), drawIsland(context, island, boxes.get(island.region())));
        }
        // Names and the pin last, so no island ever covers another's name.
        for (Island island : ISLANDS) {
            drawIslandLabel(context, island.region(), drawn.get(island.region()), island.region() == here, sy + sh);
        }
        context.disableScissor();
        noClip();
    }

    /** x, y, width and height of an island, before it lifts on hover. */
    private static int[] islandBox(Island island, int sx, int sy, int sw, int sh) {
        float side = island.size() * Math.min(sw, sh * 1.2f);
        float aspect = island.textureWidth() / (float) island.textureHeight();
        int w = Math.round(aspect >= 1 ? side : side * aspect);
        int h = Math.round(aspect >= 1 ? side / aspect : side);
        int cx = Math.round(sx + island.x() * sw);
        int cy = Math.round(sy + island.y() * sh);
        return new int[] {cx - w / 2, cy - h / 2, w, h};
    }

    /** @return the island as drawn this frame: centre x, centre y, width, height */
    private int[] drawIsland(GuiGraphicsExtractor context, Island island, int[] box) {
        ImportantPlaces.Region region = island.region();
        boolean on = config().importantRegionEnabled(region);
        boolean chosen = region == selected;
        boolean hover = hovered(box[0], box[1], box[2], box[3]);
        float lift = ease("isle:" + region.id(), hover ? 1 : 0, 14);
        float pick = ease("pick:" + region.id(), chosen ? 1 : 0, 10);
        float grow = 1 + 0.06f * lift + 0.03f * pick;
        int w = Math.round(box[2] * grow);
        int h = Math.round(box[3] * grow);
        int cx = box[0] + box[2] / 2;
        int cy = box[1] + box[3] / 2 - Math.round(2 * lift);

        if (pick > 0.01f) {
            HelperUi.glow(context, cx, cy + 2, w / 2, h / 2, HelperUi.GOLD, Math.round(70 * pick));
        }
        // A shadow on the water that stays behind as the island rises.
        if (lift > 0.01f) {
            HelperUi.ellipse(context, cx, box[1] + box[3] / 2 + 3, w / 2 - 2, h / 2 - 3,
                    HelperUi.alpha(0x000000, Math.round(40 * lift)));
        }
        int tint = on ? 0xFFFFFFFF : HelperUi.mix(0xFF9AA3B4, 0xFFFFFFFF, Math.max(lift, pick) * 0.6f);
        context.blit(RenderPipelines.GUI_TEXTURED, island.id(), cx - w / 2, cy - h / 2, 0f, 0f, w, h,
                island.textureWidth(), island.textureHeight(), island.textureWidth(), island.textureHeight(), tint);
        hit(box[0], box[1], box[2], box[3], () -> {
            selected = region;
            lastIsland = region;
            panelScroll = 0;
        });
        return new int[] {cx, cy, w, h};
    }

    /** An island's name, with a dot for whether its markers are on, and the pin where the player is. */
    private void drawIslandLabel(GuiGraphicsExtractor context, ImportantPlaces.Region region, int[] drawn, boolean here,
                                 int seaBottom) {
        boolean on = config().importantRegionEnabled(region);
        boolean chosen = region == selected;
        float lift = anims.getOrDefault("isle:" + region.id(), 0f);
        int cx = drawn[0];
        int cy = drawn[1];
        int h = drawn[3];
        // Its name, with a dot for whether its markers are on.
        Component name = Component.literal(region.displayName()).withStyle(ChatFormatting.BOLD);
        int nameW = this.font.width(name) + 8;
        int ny = Math.min(cy + h / 2 - 4, seaBottom - 12);
        int nx = cx - nameW / 2;
        HelperUi.round(context, nx - 3, ny - 2, nameW + 6, 12, 5, HelperUi.alpha(0x0A1424, 0xC8));
        HelperUi.round(context, nx, ny + 2, 4, 4, 2, on ? HelperUi.ON : 0xFF6A7488);
        context.text(this.font, name, nx + 7, ny, HelperUi.mix(chosen ? HelperUi.GOLD_LIGHT : HelperUi.TEXT,
                HelperUi.GOLD_LIGHT, lift), false);
        if (here) {
            float bob = (float) Math.sin(System.currentTimeMillis() / 260.0) * 1.5f;
            pin(context, cx, cy - h / 2 + 2 + Math.round(bob), 1);
        }
    }

    /** A map pin: a gold head on a point; {@code size} 0 is the small one, 1 the one over an island. */
    private static void pin(GuiGraphicsExtractor context, int x, int y, int size) {
        int r = size == 0 ? 3 : 4;
        int tip = size == 0 ? 3 : 5;
        HelperUi.round(context, x - r, y - r * 2 - tip + 1, r * 2, r * 2, r, 0xFF3A2A0A);
        HelperUi.round(context, x - r + 1, y - r * 2 - tip + 2, r * 2 - 2, r * 2 - 2, r - 1, HelperUi.GOLD);
        for (int i = 0; i < tip; i++) {
            int half = Math.max(0, (tip - i) * r / (tip + 1));
            context.fill(x - half, y - tip + i, x + half + 1, y - tip + i + 1, i == tip - 1 ? 0xFF3A2A0A : HelperUi.GOLD);
        }
        context.fill(x - 1, y - r - tip - 1, x + 1, y - r - tip + 1, 0xFFFFF4D0);
    }

    private void drawIslandPanel(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        panelX = x;
        panelY = y;
        panelW = w;
        panelH = h;
        ImportantPlaces.Region region = selected;
        Island island = islandOf(region);
        boolean on = config().importantRegionEnabled(region);
        boolean here = region == ImportantWaypointsHud.currentRegion(this.minecraft);
        HelperUi.framed(context, x, y, w, h, 4, HelperUi.CARD, HelperUi.CARD_EDGE);

        // The island small, its name and what it is.
        int thumbW = 38;
        int thumbH = 30;
        float aspect = island.textureWidth() / (float) island.textureHeight();
        int tw = aspect >= 1 ? thumbW : Math.round(thumbH * aspect);
        int th = aspect >= 1 ? Math.round(thumbW / aspect) : thumbH;
        HelperUi.round(context, x + 6, y + 6, thumbW + 4, thumbH + 4, 3, 0xFF15385C);
        context.blit(RenderPipelines.GUI_TEXTURED, island.id(), x + 8 + (thumbW - tw) / 2, y + 8 + (thumbH - th) / 2,
                0f, 0f, tw, th, island.textureWidth(), island.textureHeight(), island.textureWidth(),
                island.textureHeight(), 0xFFFFFFFF);
        int tx = x + thumbW + 16;
        context.text(this.font, Component.literal(region.displayName()).withStyle(ChatFormatting.BOLD), tx, y + 9,
                HelperUi.TEXT);
        List<ImportantPlaces.Place> places = ImportantPlaces.on(region);
        Component kind = Component.translatable(region == ImportantPlaces.Region.ALADRA
                ? "betterloka.helper.island_spawn" : "betterloka.helper.island_continent");
        Component counted = Component.translatable("betterloka.helper.island_count", kind, places.size());
        context.text(this.font, this.font.plainSubstrByWidth((this.font.width(counted) <= x + w - tx - 4 ? counted : kind)
                .getString(), x + w - tx - 4), tx, y + 20, HelperUi.MUTED);
        if (here) {
            Component you = Component.translatable("betterloka.helper.important_you");
            int yw = this.font.width(you) + 8;
            if (tx + yw <= x + w - 4) {
                GuiTheme.chip(context, tx, y + 31, yw, 11, HelperUi.GOLD);
                context.text(this.font, you, tx + 4, y + 33, HelperUi.GOLD_LIGHT);
            }
        }

        // Its markers' switch.
        int rowY = y + 44;
        HelperUi.round(context, x + 6, rowY, w - 12, 20, 3, 0xFF0F1622);
        Component markers = Component.translatable("betterloka.helper.island_markers");
        if (this.font.width(markers) > w - 26 - SWITCH_W) {
            markers = Component.translatable("betterloka.helper.island_markers_short");
        }
        context.text(this.font, this.font.plainSubstrByWidth(markers.getString(), w - 26 - SWITCH_W), x + 11, rowY + 6,
                on ? HelperUi.TEXT : HelperUi.MUTED);
        toggle(context, x + w - 11 - SWITCH_W, rowY + 4, "region:" + region.id(), on, true,
                () -> config().setImportantRegionEnabled(region, !config().importantRegionEnabled(region)));

        // Its places, scrolling when they do not fit; a click copies the coordinates.
        int footer = 24;
        int listTop = rowY + 26;
        int listBottom = y + h - footer;
        panelView = listBottom - listTop;
        int rowH = 22;
        panelHeight = places.size() * rowH;
        panelScroll = Math.max(0, Math.min(panelScroll, Math.max(0, panelHeight - panelView)));
        clip(x + 2, listTop, x + w - 2, listBottom);
        context.enableScissor(clipX0, clipY0, clipX1, clipY1);
        int py = listTop - panelScroll;
        for (ImportantPlaces.Place place : places) {
            boolean hover = hovered(x + 4, py, w - 8, rowH - 1);
            if (hover) {
                HelperUi.round(context, x + 4, py, w - 8, rowH - 1, 3, 0xFF1A2438);
            }
            PlaceIcons.draw(context, place.icon(), x + 8, py + 3, 16);
            String name = Component.translatable(place.nameKey()).getString();
            context.text(this.font, this.font.plainSubstrByWidth(name, w - 38), x + 29, py + 2,
                    on ? HelperUi.TEXT : HelperUi.MUTED);
            String coords = place.x() + " " + place.y() + " " + place.z();
            boolean justCopied = coords.equals(copied) && System.currentTimeMillis() - copiedAt < 1500;
            context.text(this.font, justCopied ? Component.translatable("betterloka.helper.island_copied").getString()
                    : coords, x + 29, py + 12, justCopied ? HelperUi.ON : HelperUi.FAINT);
            if (hover) {
                context.setTooltipForNextFrame(this.font, Component.translatable("betterloka.helper.island_copy"),
                        mouseX, mouseY);
            }
            hit(x + 4, py, w - 8, rowH - 1, () -> {
                this.minecraft.keyboardHandler.setClipboard(coords);
                copied = coords;
                copiedAt = System.currentTimeMillis();
            });
            py += rowH;
        }
        context.disableScissor();
        noClip();
        if (panelHeight > panelView) {
            scrollbar(context, x + w - 4, listTop, panelView, panelScroll, panelHeight);
        }

        // Below: the map, for a continent, and every marker off.
        int by = y + h - footer + 4;
        boolean onMap = region != ImportantPlaces.Region.ALADRA;
        int allW = onMap ? Math.min(56, (w - 16) / 3) : w - 12;
        if (onMap) {
            button(context, x + 6, by, w - 16 - allW, CONTROL_H, Component.translatable("betterloka.helper.island_open_map"),
                    true, () -> this.minecraft.gui.setScreen(LokaMapScreen.on(this, Continent.valueOf(region.name()))));
        }
        button(context, x + w - 6 - allW, by, allW, CONTROL_H, Component.translatable("betterloka.helper.important_all_off"),
                false, () -> {
                    for (ImportantPlaces.Region r : ImportantPlaces.Region.values()) {
                        config().setImportantRegionEnabled(r, false);
                    }
                });
    }

    private static Island islandOf(ImportantPlaces.Region region) {
        for (Island island : ISLANDS) {
            if (island.region() == region) {
                return island;
            }
        }
        return ISLANDS.get(0);
    }

    // --- the lists of settings ---

    private void drawList(GuiGraphicsExtractor context, int x, int y, int w, int h) {
        pageView = h;
        int scroll = Math.max(0, Math.min(scrolls.getOrDefault(page, 0), Math.max(0, pageHeight - h)));
        scrolls.put(page, scroll);
        clip(x - 2, y, x + w + 2, y + h);
        context.enableScissor(clipX0, clipY0, clipX1, clipY1);
        int cardW = w - 6;
        int top = y - scroll;
        int end = switch (page) {
            case DISPLAY -> drawDisplay(context, x, top, cardW);
            case KEYS -> drawKeys(context, x, top, cardW);
            case DUELS -> drawDuels(context, x, top, cardW);
            case SHULKERS -> drawShulkers(context, x, top, cardW);
            default -> top;
        };
        context.disableScissor();
        noClip();
        pageHeight = end - top;
        if (pageHeight > h) {
            scrollbar(context, x + w - 3, y, h, scroll, pageHeight);
        }
    }

    private int drawDisplay(GuiGraphicsExtractor context, int x, int y, int w) {
        y += pageHeader(context, x, y, w, Component.translatable("betterloka.helper.nav.display"),
                Component.translatable("betterloka.helper.page.display"));

        Component move = positionLabel();
        int moveW = this.font.width(move) + 12;
        int h = card(context, x, y, w, new ItemStack(Items.COMPASS), Component.translatable("betterloka.helper.coords"),
                Component.translatable("betterloka.helper.coords_desc"), null, SWITCH_W + 6 + moveW, false);
        toggleRight(context, x, y, w, h, "coords", config().coordsHudEnabled(),
                () -> config().setCoordsHudEnabled(!config().coordsHudEnabled()));
        button(context, x + w - 8 - SWITCH_W - 6 - moveW, y + (h - CONTROL_H) / 2, moveW, CONTROL_H, move, false,
                () -> this.minecraft.gui.setScreen(new CoordsHudPositionScreen(this)));
        y += h + GAP;

        h = card(context, x, y, w, new ItemStack(Items.LAVA_BUCKET), Component.translatable("betterloka.helper.lava"),
                Component.translatable("betterloka.helper.lava_desc"), null, SWITCH_W, false);
        toggleRight(context, x, y, w, h, "lava", config().clearLava(), () -> config().setClearLava(!config().clearLava()));
        y += h + GAP;

        boolean scaled = config().guiScaleEnabled();
        h = card(context, x, y, w, new ItemStack(Items.ITEM_FRAME), Component.translatable("betterloka.helper.gui_scale_card"),
                Component.translatable("betterloka.helper.gui_scale_desc"), null, SWITCH_W, false);
        toggleRight(context, x, y, w, h, "scale", scaled, () -> config().setGuiScaleEnabled(!config().guiScaleEnabled()));
        y += h + GAP;

        int pickerW = 58;
        h = card(context, x, y, w, new ItemStack(Items.CHEST), Component.translatable("betterloka.helper.inventory_card"),
                Component.translatable("betterloka.helper.inventory_desc"), null, pickerW, !scaled);
        picker(context, x + w - 8 - pickerW, y + (h - CONTROL_H) / 2, pickerW,
                GuiScaleController.label(config().inventoryGuiScale()), scaled,
                () -> config().setInventoryGuiScale(previous(config().inventoryGuiScale())),
                () -> config().setInventoryGuiScale(GuiScaleController.next(config().inventoryGuiScale())));
        y += h + GAP;

        h = card(context, x, y, w, new ItemStack(Items.TRIPWIRE_HOOK), Component.translatable("betterloka.helper.hotbar_card"),
                Component.translatable("betterloka.helper.hotbar_desc"), null, pickerW, !scaled);
        picker(context, x + w - 8 - pickerW, y + (h - CONTROL_H) / 2, pickerW,
                GuiScaleController.label(config().hotbarGuiScale()), scaled,
                () -> config().setHotbarGuiScale(previous(config().hotbarGuiScale())),
                () -> config().setHotbarGuiScale(GuiScaleController.next(config().hotbarGuiScale())));
        return y + h;
    }

    private int drawKeys(GuiGraphicsExtractor context, int x, int y, int w) {
        y += pageHeader(context, x, y, w, Component.translatable("betterloka.helper.nav.keys"),
                Component.translatable("betterloka.helper.page.keys"));
        int keyW = listening != null ? 82 : 64;
        int h = card(context, x, y, w, new ItemStack(Items.ENDER_PEARL), Component.translatable("betterloka.helper.tl"),
                Component.translatable("betterloka.helper.tl_desc"), null, SWITCH_W + 6 + keyW, false);
        toggleRight(context, x, y, w, h, "tl", config().tlKeyEnabled(), () -> config().setTlKeyEnabled(!config().tlKeyEnabled()));
        keyBox(context, x + w - 8 - SWITCH_W - 6 - keyW, y + (h - CONTROL_H) / 2, keyW, BetterLokaClient.tlKey(),
                Listening.TL);
        y += h + GAP;

        h = card(context, x, y, w, new ItemStack(Items.COMPASS), Component.translatable("betterloka.helper.coords_key_card"),
                Component.translatable("betterloka.helper.coords_key_desc"), null, keyW, false);
        keyBox(context, x + w - 8 - keyW, y + (h - CONTROL_H) / 2, keyW, BetterLokaClient.coordsPositionKey(),
                Listening.COORDS);
        return y + h;
    }

    private int drawDuels(GuiGraphicsExtractor context, int x, int y, int w) {
        y += pageHeader(context, x, y, w, Component.translatable("betterloka.helper.nav.duels"),
                Component.translatable("betterloka.helper.page.duels"));
        y = duelCard(context, x, y, w, Items.BELL, "duel_accept", config().duelAutoAccept(),
                () -> config().setDuelAutoAccept(!config().duelAutoAccept()));
        y = duelCard(context, x, y, w, Items.PLAYER_HEAD, "duel_screen", config().duelScreen(),
                () -> config().setDuelScreen(!config().duelScreen()));
        y = duelCard(context, x, y, w, Items.WRITABLE_BOOK, "duel_stats", config().duelStats(),
                () -> config().setDuelStats(!config().duelStats()));
        y = duelCard(context, x, y, w, Items.ENDER_CHEST, "duel_share", config().duelShare(),
                () -> config().setDuelShare(!config().duelShare()));
        return y - GAP;
    }

    private int duelCard(GuiGraphicsExtractor context, int x, int y, int w, net.minecraft.world.item.Item icon,
                         String key, boolean on, Runnable flip) {
        int h = card(context, x, y, w, new ItemStack(icon), Component.translatable("betterloka.helper." + key + "_card"),
                Component.translatable("betterloka.helper." + key + "_desc"),
                Component.translatable("betterloka.helper." + key + "_tip"), SWITCH_W, false);
        toggleRight(context, x, y, w, h, key, on, flip);
        return y + h + GAP;
    }

    private int drawShulkers(GuiGraphicsExtractor context, int x, int y, int w) {
        y += pageHeader(context, x, y, w, Component.translatable("betterloka.helper.nav.shulkers"),
                Component.translatable("betterloka.helper.page.shulkers"));
        boolean on = config().shulkerScanner();
        int h = card(context, x, y, w, new ItemStack(Items.SHULKER_BOX), Component.translatable("betterloka.helper.shulker_card"),
                Component.translatable("betterloka.helper.shulker_desc"), Component.translatable("betterloka.helper.shulker_tip"),
                SWITCH_W, false);
        toggleRight(context, x, y, w, h, "shulker", on, () -> config().setShulkerScanner(!config().shulkerScanner()));
        y += h + GAP;

        // What the colours mean: three slots as the scanner tints them, with a box in each.
        int legendH = 54;
        HelperUi.framed(context, x, y, w, legendH, 4, HelperUi.CARD, HelperUi.CARD_EDGE);
        context.text(this.font, Component.translatable("betterloka.helper.shulker_legend"), x + 10, y + 7, HelperUi.TEXT);
        String[] keys = {"betterloka.helper.shulker_full", "betterloka.helper.shulker_low", "betterloka.helper.shulker_none"};
        ShulkerScan.Level[] levels = ShulkerScan.Level.values();
        int cell = Math.max(60, (w - 20) / 3);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        for (int i = 0; i < keys.length; i++) {
            int sx = x + 10 + i * cell;
            int sy = y + 22;
            slot(context, sx, sy, levels[i].rgb(), on);
            context.item(box, sx + 1, sy + 1);
            String text = this.font.plainSubstrByWidth(Component.translatable(keys[i]).getString(), cell - 26);
            context.text(this.font, text, sx + 23, sy + 5, on ? HelperUi.TEXT : HelperUi.MUTED);
        }
        return y + legendH;
    }

    /** An inventory slot, tinted the way the Shulker Scanner tints it (or plain while it is off). */
    private static void slot(GuiGraphicsExtractor context, int x, int y, int rgb, boolean tinted) {
        context.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        context.fill(x, y, x + 17, y + 1, 0xFF373737);
        context.fill(x, y, x + 1, y + 17, 0xFF373737);
        context.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF);
        context.fill(x + 17, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        if (tinted) {
            context.fill(x + 1, y + 1, x + 17, y + 17, 0x70000000 | rgb);
            context.fill(x + 1, y + 1, x + 17, y + 2, 0xFF000000 | rgb);
            context.fill(x + 1, y + 16, x + 17, y + 17, 0xFF000000 | rgb);
            context.fill(x + 1, y + 2, x + 2, y + 16, 0xFF000000 | rgb);
            context.fill(x + 16, y + 2, x + 17, y + 16, 0xFF000000 | rgb);
        }
    }

    /** A page's title and what it is for. */
    private int pageHeader(GuiGraphicsExtractor context, int x, int y, int w, Component title, Component description) {
        context.text(this.font, title.copy().withStyle(ChatFormatting.BOLD), x + 1, y + 1, HelperUi.TEXT);
        List<FormattedCharSequence> lines = this.font.split(description, w - 2);
        int ly = y + 13;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            context.text(this.font, lines.get(i), x + 1, ly, HelperUi.MUTED);
            ly += 10;
        }
        return ly - y + 5;
    }

    /**
     * One setting: an icon, its name, what it does, and room on the right for its controls. A card with
     * a longer explanation has a small "?" beside its name that shows it.
     *
     * @return its height
     */
    private int card(GuiGraphicsExtractor context, int x, int y, int w, ItemStack icon, Component title,
                     Component description, Component tip, int controls, boolean dim) {
        int textW = Math.max(40, w - 36 - controls - 14);
        List<FormattedCharSequence> lines = this.font.split(description, textW);
        int h = Math.max(32, 21 + lines.size() * 10 + 4);
        boolean hover = hovered(x, y, w, h);
        float t = ease("card:" + page.id + ":" + title.getString(), hover ? 1 : 0, 14);
        HelperUi.framed(context, x, y, w, h, 4, HelperUi.mix(HelperUi.CARD, HelperUi.CARD_HOVER, t),
                HelperUi.mix(HelperUi.CARD_EDGE, HelperUi.CARD_EDGE_HOVER, t));
        HelperUi.round(context, x + 7, y + (h - 22) / 2, 22, 22, 4, HelperUi.ICON_TILE);
        context.item(icon, x + 10, y + (h - 16) / 2);
        Component name = title.copy().withStyle(ChatFormatting.BOLD);
        context.text(this.font, name, x + 36, y + 8, dim ? HelperUi.MUTED : HelperUi.TEXT);
        if (tip != null) {
            int bx = x + 36 + this.font.width(name) + 5;
            int by = y + 7;
            boolean onTip = hovered(bx, by, 10, 10);
            HelperUi.round(context, bx, by, 10, 10, 5, onTip ? 0xFF3A4C70 : 0xFF243049);
            context.centeredText(this.font, "?", bx + 5, by + 1, onTip ? HelperUi.TEXT : HelperUi.MUTED);
            if (onTip) {
                context.setTooltipForNextFrame(this.font, this.font.split(tip, 220), mouseX, mouseY);
            }
        }
        int ly = y + 20;
        for (FormattedCharSequence line : lines) {
            context.text(this.font, line, x + 36, ly, dim ? HelperUi.FAINT : HelperUi.MUTED);
            ly += 10;
        }
        return h;
    }

    // --- controls ---

    private void toggleRight(GuiGraphicsExtractor context, int x, int y, int w, int h, String key, boolean on, Runnable flip) {
        toggle(context, x + w - 8 - SWITCH_W, y + (h - SWITCH_H) / 2, key, on, true, flip);
    }

    /** A switch that slides: green and to the right when on. */
    private void toggle(GuiGraphicsExtractor context, int x, int y, String key, boolean on, boolean enabled, Runnable flip) {
        float t = ease("switch:" + key, on ? 1 : 0, 16);
        boolean hover = enabled && hovered(x - 2, y - 2, SWITCH_W + 4, SWITCH_H + 4);
        int track = HelperUi.mix(HelperUi.OFF, HelperUi.ON, t);
        if (hover) {
            track = HelperUi.lighten(track, 0.15f);
        }
        HelperUi.round(context, x, y, SWITCH_W, SWITCH_H, SWITCH_H / 2, track);
        int knob = SWITCH_H - 4;
        int kx = Math.round(x + 2 + HelperUi.easeOut(t) * (SWITCH_W - knob - 4));
        HelperUi.round(context, kx, y + 3, knob, knob, knob / 2, 0x50000000);
        HelperUi.round(context, kx, y + 2, knob, knob, knob / 2, 0xFFF6F8FB);
        if (enabled) {
            hit(x - 2, y - 2, SWITCH_W + 4, SWITCH_H + 4, flip);
        }
    }

    /** A flat button; {@code primary} ones are drawn in the accent. */
    private void button(GuiGraphicsExtractor context, int x, int y, int w, int h, Component label, boolean primary,
                        Runnable action) {
        boolean hover = hovered(x, y, w, h);
        float t = ease("button:" + label.getString() + x, hover ? 1 : 0, 16);
        int fill = primary ? HelperUi.mix(0xFF23406A, 0xFF2E5590, t) : HelperUi.mix(0xFF1C2638, 0xFF263450, t);
        int edge = primary ? HelperUi.mix(0xFF3A609A, 0xFF5A86C8, t) : HelperUi.mix(0xFF2C3A56, 0xFF40527A, t);
        HelperUi.framed(context, x, y, w, h, 3, fill, edge);
        String text = this.font.plainSubstrByWidth(label.getString(), w - 6);
        context.centeredText(this.font, text, x + w / 2, y + (h - 8) / 2, HelperUi.mix(0xFFD8E0EE, 0xFFFFFFFF, t));
        hit(x, y, w, h, action);
    }

    /** A value with an arrow either side: the left one steps back, the right one forward. */
    private void picker(GuiGraphicsExtractor context, int x, int y, int w, String value, boolean enabled,
                        Runnable back, Runnable forward) {
        HelperUi.framed(context, x, y, w, CONTROL_H, 3, enabled ? 0xFF1C2638 : 0xFF151C2A, 0xFF2C3A56);
        int half = w / 2;
        boolean left = enabled && hovered(x, y, half, CONTROL_H);
        boolean right = enabled && hovered(x + half, y, w - half, CONTROL_H);
        int arrow = enabled ? HelperUi.MUTED : HelperUi.FAINT;
        context.text(this.font, "‹", x + 5, y + 4, left ? HelperUi.GOLD : arrow);
        context.text(this.font, "›", x + w - 9, y + 4, right ? HelperUi.GOLD : arrow);
        context.centeredText(this.font, value, x + w / 2, y + 4, enabled ? HelperUi.TEXT : HelperUi.FAINT);
        if (enabled) {
            hit(x, y, half, CONTROL_H, back);
            hit(x + half, y, w - half, CONTROL_H, forward);
        }
    }

    /** A key's name in a box; a click waits for the next key pressed, Esc clears it. */
    private void keyBox(GuiGraphicsExtractor context, int x, int y, int w, KeyMapping key, Listening which) {
        boolean waiting = listening == which;
        boolean hover = hovered(x, y, w, CONTROL_H);
        float pulse = waiting ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0) : 0;
        int edge = waiting ? HelperUi.mix(0xFF8A6A20, HelperUi.GOLD, pulse) : hover ? 0xFF40527A : 0xFF2C3A56;
        HelperUi.framed(context, x, y, w, CONTROL_H, 3, 0xFF101724, edge);
        String text;
        int color;
        if (waiting) {
            text = Component.translatable("betterloka.helper.key_press").getString();
            color = HelperUi.GOLD_LIGHT;
        } else if (key == null || key.isUnbound()) {
            text = Component.translatable("betterloka.helper.tl_unbound").getString();
            color = HelperUi.FAINT;
        } else {
            text = key.getTranslatedKeyMessage().getString();
            color = 0xFF7FD8FF;
        }
        context.centeredText(this.font, this.font.plainSubstrByWidth(text, w - 6), x + w / 2, y + 4, color);
        hit(x, y, w, CONTROL_H, () -> listening = waiting ? null : which);
    }

    private void scrollbar(GuiGraphicsExtractor context, int x, int y, int view, int scroll, int total) {
        int bar = Math.max(12, view * view / Math.max(1, total));
        int max = Math.max(1, total - view);
        int by = y + (view - bar) * scroll / max;
        HelperUi.round(context, x, y, 2, view, 1, 0x30FFFFFF);
        HelperUi.round(context, x, by, 2, bar, 1, 0xA0FFFFFF);
    }

    private Component positionLabel() {
        KeyMapping key = BetterLokaClient.coordsPositionKey();
        return key == null || key.isUnbound()
                ? Component.translatable("betterloka.helper.coords_move")
                : Component.translatable("betterloka.helper.coords_move_key", key.getTranslatedKeyMessage());
    }

    /** The value before {@code scale} in the GUI scale's cycle. */
    private static int previous(int scale) {
        int value = scale;
        for (int i = 0; i < 8; i++) {
            int next = GuiScaleController.next(value);
            if (next == scale) {
                return value;
            }
            value = next;
        }
        return scale;
    }

    // --- hover, clicks and animation ---

    private float ease(String key, float target, float speed) {
        Float value = anims.get(key);
        float v = value == null ? target : value;
        v += (target - v) * (1 - (float) Math.exp(-speed * dt));
        if (Math.abs(target - v) < 0.002f) {
            v = target;
        }
        anims.put(key, v);
        return v;
    }

    private void clip(int x0, int y0, int x1, int y1) {
        clipX0 = x0;
        clipY0 = y0;
        clipX1 = x1;
        clipY1 = y1;
    }

    private void noClip() {
        clip(0, 0, this.width, this.height);
    }

    private boolean hovered(int x, int y, int w, int h) {
        return mouseX >= Math.max(x, clipX0) && mouseX < Math.min(x + w, clipX1)
                && mouseY >= Math.max(y, clipY0) && mouseY < Math.min(y + h, clipY1);
    }

    /** Something clickable this frame, cut to the region being drawn into. */
    private void hit(int x, int y, int w, int h, Runnable action) {
        int x0 = Math.max(x, clipX0);
        int y0 = Math.max(y, clipY0);
        int x1 = Math.min(x + w, clipX1);
        int y1 = Math.min(y + h, clipY1);
        if (x1 > x0 && y1 > y0) {
            hits.add(new Hit(x0, y0, x1 - x0, y1 - y0, action));
            if (mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1) {
                pointing = true;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.contains(click.x(), click.y())) {
                    Listening before = listening;
                    hit.action().run();
                    // A click anywhere but a key box gives up waiting for a key.
                    if (before != null && listening == before) {
                        listening = null;
                    }
                    this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    return true;
                }
            }
            listening = null;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int step = (int) Math.round(-verticalAmount * 18);
        if (page == Page.WAYPOINTS) {
            if (mouseX >= panelX && mouseX < panelX + panelW && mouseY >= panelY && mouseY < panelY + panelH) {
                panelScroll = Math.max(0, Math.min(Math.max(0, panelHeight - panelView), panelScroll + step));
                return true;
            }
        } else {
            int max = Math.max(0, pageHeight - pageView);
            scrolls.put(page, Math.max(0, Math.min(max, scrolls.getOrDefault(page, 0) + step)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (listening != null) {
            KeyMapping key = listening == Listening.TL ? BetterLokaClient.tlKey() : BetterLokaClient.coordsPositionKey();
            listening = null;
            if (key != null) {
                // Escape clears it: no key is how /tl starts, and how it can always be put back.
                key.setKey(input.key() == InputConstants.KEY_ESCAPE ? InputConstants.UNKNOWN : InputConstants.getKey(input));
                KeyMapping.resetMapping();
                this.minecraft.options.save();
            }
            return true;
        }
        // Up and down move between the sections.
        if (input.key() == InputConstants.KEY_UP || input.key() == InputConstants.KEY_DOWN) {
            int count = Page.values().length;
            int step = input.key() == InputConstants.KEY_UP ? count - 1 : 1;
            choose(Page.values()[(page.ordinal() + step) % count]);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void onClose() {
        listening = null;
        this.minecraft.gui.setScreen(parent);
    }

    // --- for the dev harness ---

    /** Turns the options the harness is checking on or off, as their switches would. */
    public void devSet(boolean coords, boolean lava, boolean tl) {
        config().setCoordsHudEnabled(coords);
        config().setClearLava(lava);
        config().setTlKeyEnabled(tl);
    }

    /** Presses the /tl key box, then a key, as a player binding /tl would. */
    public void devBindTl(int keyCode) {
        listening = Listening.TL;
        keyPressed(new KeyEvent(keyCode, 0, 0));
    }

    /** Opens a section by its id: waypoints, display, keys, duels, shulkers. */
    public void devPage(String id) {
        for (Page p : Page.values()) {
            if (p.id.equals(id)) {
                choose(p);
            }
        }
    }

    /** Picks an island, as a click on it would. */
    public void devIsland(ImportantPlaces.Region region) {
        selected = region;
    }

    /** Where an island's middle is drawn, for the harness's pointer; {@code null} off the waypoints page. */
    public int[] devIslandCentre(ImportantPlaces.Region region) {
        if (page != Page.WAYPOINTS) {
            return null;
        }
        int cx = wx + navWidth + PAD;
        int cw = ww - navWidth - 2 * PAD;
        int head = 13 + Math.min(2, this.font.split(Component.translatable("betterloka.helper.page.waypoints"), cw - 2).size()) * 10 + 5;
        int top = wy + HEADER + PAD + head;
        int height = wh - HEADER - 2 * PAD - head;
        int panel = Math.max(118, Math.min(190, (int) (cw * 0.40)));
        int[] box = islandBox(islandOf(region), cx, top, cw - panel - 6, height);
        return new int[] {box[0] + box[2] / 2, box[1] + box[3] / 2};
    }
}
