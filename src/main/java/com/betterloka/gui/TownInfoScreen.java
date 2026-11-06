package com.betterloka.gui;

import com.betterloka.BetterLoka;
import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.map.Continent;
import com.betterloka.map.MapTerritory;
import com.betterloka.map.MapTown;
import com.betterloka.towns.TownActivityStore;
import com.betterloka.towns.TownInfoReading;
import com.betterloka.towns.TownLogStore;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One town, opened from the Loka Map: its banner on the left, waving in the town's own map colour,
 * and everything known about it in tiles and cards to the right — level, strength, members, the
 * vulnerability window and whether it is open right now, who leads it, who it allies with, how
 * active it is, and the territories it holds, each one click from being shown on the map.
 *
 * <p>What the map already knows — the colour, the territories, the map's own strength and title —
 * is drawn at once; Loka's town record fills in the rest a moment later.
 */
public class TownInfoScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 470;
    private static final int TOP = 24;
    private static final int STAND_WIDTH = 132;
    private static final int LINE = 11;
    private static final int CARD_PADDING = 6;
    private static final int TILE_HEIGHT = 30;
    private static final int TILE_GAP = 4;
    private static final int SECTION_GAP = 10;
    private static final int CHIP_HEIGHT = 14;
    private static final int CARD_BORDER = 0xFF2C2C38;
    private static final int SEPARATOR = 0xFF33333F;
    private static final int ROW_HEIGHT = 26;

    /** A Loka vulnerability window runs eight hours from the hour the API publishes. */
    private static final int VULN_HOURS = 8;
    /** How many past allies to name. Beyond a handful it stops being a summary. */
    private static final int MAX_PARTNERS = 5;

    private record Tile(String value, String label, int color) {
    }

    private final LokaMapScreen map;
    private final String townName;
    private final Continent continent;
    private final MapTown mapTown;
    private final List<MapTerritory> held;
    private final int color;
    private final long openedAt = System.currentTimeMillis();
    private final ScrollPanel scroll = new ScrollPanel();

    private LokaTown town;
    private boolean loading = true;
    private LokaAlliance alliance;
    private List<TownLogStore.TownPartner> partners = List.of();
    /** Identity IDs resolve to names in the background; drawing shows whatever has landed. */
    private final Map<String, String> names = new ConcurrentHashMap<>();

    /** Where each territory row was drawn this frame, so a click can find which one it hit. */
    private final List<int[]> rowBounds = new ArrayList<>();
    private final List<MapTerritory> rowTerritories = new ArrayList<>();
    private int lastMouseX;
    private int lastMouseY;

    /**
     * @param held  the territories the map shows the town holding on this continent
     * @param color the town's colour on the map
     */
    public TownInfoScreen(LokaMapScreen map, String townName, Continent continent, MapTown mapTown,
                          List<MapTerritory> held, int color) {
        super(Component.literal(townName));
        this.map = map;
        this.townName = townName;
        this.continent = continent;
        this.mapTown = mapTown;
        List<MapTerritory> sorted = new ArrayList<>(held);
        // The seat first, then the rest in Loka's own numbering.
        sorted.sort(Comparator.comparing((MapTerritory territory) -> !territory.seat())
                .thenComparing(MapTerritory::number, TownInfoScreen::byNumber));
        this.held = List.copyOf(sorted);
        this.color = 0xFF000000 | (color & 0xFFFFFF);
    }

    private static int byNumber(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int bottom() {
        return this.height - 31;
    }

    private int detailsX() {
        return contentLeft() + STAND_WIDTH + 10;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        scroll.setViewport(detailsX(), TOP, left + width - detailsX(), bottom() - TOP);

        int half = Math.min(160, (width - 4) / 2);
        addRenderableWidget(Button.builder(Component.translatable("betterloka.town.show_on_map"), button -> {
                    MapTerritory seat = held.isEmpty() ? null : held.get(0);
                    if (seat != null) {
                        map.showTerritory(seat.number());
                    }
                    onClose();
                })
                .bounds(this.width / 2 - half - 2, this.height - 25, half, 20).build()).active = !held.isEmpty();
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 + 2, this.height - 25, half, 20).build());

        if (town == null && loading) {
            load();
        }
    }

    /** Loka's record of the town, and the Town Logger's alliance and allies; off the render thread. */
    private void load() {
        CompletableFuture
                .supplyAsync(() -> {
                    LokaTown found = BetterLokaClient.towns().byName(townName);
                    return found != null ? found : BetterLokaClient.townLogger().deletedByName(townName);
                }, BetterLokaClient.lokaExecutor())
                .whenComplete((found, error) -> onScreen(() -> {
                    loading = false;
                    if (error != null || found == null) {
                        return;
                    }
                    town = found;
                    alliance = BetterLokaClient.townLogger().allianceOf(found.id());
                    partners = BetterLokaClient.townLogger().partnersOf(found.id());
                    resolveNames(found);
                }));
    }

    /** Owners and sub-owners are recorded by identity, so each needs a small lookup of its own. */
    private void resolveNames(LokaTown found) {
        List<String> pending = new ArrayList<>();
        if (found.ownerId() != null) {
            pending.add(found.ownerId());
        }
        pending.addAll(found.subOwnerIds());
        for (String identity : pending) {
            if (names.containsKey(identity)) {
                continue;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    String name = BetterLokaClient.lokaApi().findNameByIdentity(identity);
                    names.put(identity, name == null ? "" : name);
                } catch (ApiException e) {
                    names.put(identity, "");
                    BetterLoka.LOGGER.debug("Could not resolve identity {}", identity, e);
                }
            }, BetterLokaClient.lokaExecutor());
        }
    }

    private void onScreen(Runnable action) {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.execute(() -> {
            if (this.minecraft.gui.screen() == this) {
                action.run();
            }
        });
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scroll.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (scroll.mouseClicked(click.x(), click.y())) {
            return true;
        }
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.y() >= TOP && click.y() < bottom()) {
            for (int i = 0; i < rowBounds.size(); i++) {
                int[] bounds = rowBounds.get(i);
                if (click.x() >= bounds[0] && click.x() < bounds[0] + bounds[2]
                        && click.y() >= bounds[1] && click.y() < bounds[1] + bounds[3]) {
                    map.showTerritory(rowTerritories.get(i).number());
                    onClose();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
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
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        context.centeredText(this.font, Component.translatable("betterloka.town.title"), this.width / 2, 9,
                GuiTheme.MUTED);
        drawBanner(context);
        drawDetails(context, mouseX, mouseY);
    }

    // --- the banner ---

    private boolean deleted() {
        return town != null && town.deleted();
    }

    /**
     * The town's banner, waving on its pole in the town's own colour with its initial on it, and who
     * the town is underneath: name, standing, continent, the chips that matter, its motto, and its
     * level on a tag at the foot.
     */
    private void drawBanner(GuiGraphicsExtractor context) {
        int left = contentLeft();
        int top = TOP;
        int bottom = bottom();
        MapStyle.card(context, left, top, STAND_WIDTH, bottom - top, CARD_BORDER);
        int centreX = left + STAND_WIDTH / 2;

        int clothWidth = 58;
        int clothHeight = Math.max(50, Math.min(86, (bottom - top) / 3));
        int clothX = centreX - clothWidth / 2;
        int clothTop = top + 16;
        int notch = 12;
        double seconds = (System.currentTimeMillis() - openedAt) / 1000.0;
        int base = deleted() ? 0xFF5A5A62 : color;
        int dark = 0xFF000000 | MarketLook.shade(base, 0.6f);
        int light = 0xFF000000 | MarketLook.shade(base, 1.35f);

        // A soft glow behind it, in its colour.
        for (int ring = 0; ring < 4; ring++) {
            int radius = clothHeight / 2 + 14 - ring * 6;
            disc(context, centreX, clothTop + clothHeight / 2, radius, (0x0C + ring * 0x06) << 24 | (base & 0xFFFFFF));
        }

        // The crossbar and its finials.
        context.fill(clothX - 6, clothTop - 5, clothX + clothWidth + 6, clothTop - 2, 0xFF8A6A44);
        context.fill(clothX - 6, clothTop - 5, clothX + clothWidth + 6, clothTop - 4, 0xFFB08A5A);
        context.fill(clothX - 9, clothTop - 6, clothX - 5, clothTop - 1, 0xFFE3B54E);
        context.fill(clothX + clothWidth + 5, clothTop - 6, clothX + clothWidth + 9, clothTop - 1, 0xFFE3B54E);
        context.fill(centreX - 1, clothTop - 12, centreX + 1, clothTop - 5, 0xFF8A6A44);

        // The cloth, one column at a time, each a little lower or higher as the wave passes; a
        // swallowtail cut out of its foot, and a border and band in lighter and darker shades.
        for (int column = 0; column < clothWidth; column++) {
            int x = clothX + column;
            int wave = (int) Math.round(Math.sin(seconds * 2.2 - column * 0.16) * 1.6 * column / clothWidth);
            int fromMiddle = Math.abs(column - clothWidth / 2);
            int cut = Math.max(0, notch - fromMiddle * notch * 2 / clothWidth);
            int y0 = clothTop + wave;
            int y1 = clothTop + clothHeight - cut + wave;
            boolean edge = column < 3 || column >= clothWidth - 3;
            context.fill(x, y0, x + 1, y1, edge ? dark : base);
            context.fill(x, y0 + 6, x + 1, y0 + 9, light);
            context.fill(x, y1 - 3, x + 1, y1, dark);
        }

        // Its initial, large, over the middle of the cloth.
        String initial = townName.isEmpty() ? "?" : townName.substring(0, 1).toUpperCase(Locale.ROOT);
        context.pose().pushMatrix();
        context.pose().translate(centreX - this.font.width(initial) * 2f + 1, clothTop + clothHeight / 2f - 14);
        context.pose().scale(4, 4);
        context.text(this.font, initial, 0, 0, 0xFFFFFFFF);
        context.pose().popMatrix();

        int y = clothTop + clothHeight + 10;
        Component name = Component.literal(this.font.plainSubstrByWidth(townName, STAND_WIDTH - 12))
                .withStyle(ChatFormatting.BOLD);
        context.centeredText(this.font, name, centreX, y, deleted() ? GuiTheme.BAD : light);
        y += LINE;
        if (mapTown != null && mapTown.hasTitle()) {
            for (FormattedCharSequence line : this.font.split(Component.literal(mapTown.title()), STAND_WIDTH - 12)) {
                context.centeredText(this.font, line, centreX, y, 0xFFE3B54E);
                y += LINE - 1;
            }
        }
        context.centeredText(this.font, continent.displayName(), centreX, y, GuiTheme.MUTED);
        y += LINE + 3;

        // The chips: deleted, recruiting, and the alliance.
        List<Component> chips = new ArrayList<>();
        List<Integer> chipColors = new ArrayList<>();
        if (deleted()) {
            chips.add(Component.translatable("betterloka.town.deleted_chip"));
            chipColors.add(GuiTheme.BAD);
        } else if (town != null && town.recruiting()) {
            chips.add(Component.translatable("betterloka.town.recruiting_chip"));
            chipColors.add(GuiTheme.GOOD);
        }
        String allianceName = alliance != null ? alliance.name()
                : mapTown != null && mapTown.hasAlliance() ? mapTown.alliance() : null;
        if (allianceName != null) {
            chips.add(Component.literal(this.font.plainSubstrByWidth(allianceName, STAND_WIDTH - 24)));
            chipColors.add(GuiTheme.ACCENT);
        }
        for (int i = 0; i < chips.size(); i++) {
            int chipWidth = this.font.width(chips.get(i)) + 10;
            GuiTheme.chip(context, centreX - chipWidth / 2, y, chipWidth, CHIP_HEIGHT, chipColors.get(i));
            context.centeredText(this.font, chips.get(i), centreX, y + 3, chipColors.get(i));
            y += CHIP_HEIGHT + 3;
        }

        // The motto, quoted and wrapped, while there is room above the tag.
        int tagHeight = 38;
        int tagY = bottom - tagHeight - 6;
        String slogan = town == null ? null : town.slogan();
        if (slogan != null && !slogan.isBlank()) {
            y += 3;
            for (FormattedCharSequence line : this.font.split(
                    Component.literal("“" + slogan.strip() + "”").withStyle(ChatFormatting.ITALIC),
                    STAND_WIDTH - 14)) {
                if (y + LINE > tagY - 4) {
                    break;
                }
                context.centeredText(this.font, line, centreX, y, 0xFFB8B8C4);
                y += LINE - 1;
            }
        }

        // Level and members on two tags at the foot, the way the Player Finder hangs its numbers.
        if (tagY > y + 2) {
            int tagWidth = (STAND_WIDTH - 20) / 2;
            String level = town == null || deleted() ? "—" : String.valueOf((int) town.townLevel());
            int members = town != null && !deleted() ? town.memberCount() : mapTown != null ? mapTown.members() : -1;
            tag(context, left + 8, tagY, tagWidth, tagHeight, level,
                    Component.translatable("betterloka.town_finder.level"), light);
            tag(context, left + STAND_WIDTH - 8 - tagWidth, tagY, tagWidth, tagHeight,
                    members < 0 ? "—" : String.valueOf(members),
                    Component.translatable("betterloka.town.members_tag"), GuiTheme.TEXT);
        }
    }

    private void tag(GuiGraphicsExtractor context, int x, int y, int width, int height, String value,
                     Component label, int valueColor) {
        MapStyle.card(context, x, y, width, height, color);
        int scale = this.font.width(value) * 2 <= width - 6 ? 2 : 1;
        context.pose().pushMatrix();
        context.pose().translate(x + width / 2f - this.font.width(value) * scale / 2f, y + (scale == 2 ? 6 : 10));
        context.pose().scale(scale, scale);
        context.text(this.font, value, 0, 0, valueColor);
        context.pose().popMatrix();
        context.centeredText(this.font, this.font.plainSubstrByWidth(label.getString(), width - 4),
                x + width / 2, y + 25, GuiTheme.MUTED);
    }

    private static void disc(GuiGraphicsExtractor context, int centreX, int centreY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            context.fill(centreX - half, centreY + dy, centreX + half, centreY + dy + 1, color);
        }
    }

    // --- the details ---

    private void drawDetails(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int x = detailsX();
        int width = scroll.contentWidth();
        rowBounds.clear();
        rowTerritories.clear();
        context.enableScissor(x, TOP, contentLeft() + contentWidth(), bottom());
        int top = scroll.contentTop();
        int y = top;

        if (loading) {
            y = card(context, x, y, width, 1, (textX, textY, inner) ->
                    context.text(this.font, Component.translatable("betterloka.town.loading"), textX, textY,
                            GuiTheme.MUTED));
        }

        // The numbers: Loka's record where it has come in, the map's otherwise.
        double strength = town != null && !deleted() ? town.strength() : mapTown != null ? mapTown.strength() : -1;
        int territories = !held.isEmpty() ? held.size() : mapTown != null ? mapTown.territories() : 0;
        y = section(context, x, y, width, Component.translatable("betterloka.town.section.overview"));
        y = tiles(context, x, y, width, 4, List.of(
                new Tile(town == null || deleted() ? "—" : String.valueOf((int) town.townLevel()),
                        label("betterloka.town_finder.level"), color),
                new Tile(strength < 0 ? "—" : String.format(Locale.ROOT, "%,.0f", strength),
                        label("betterloka.town_finder.strength"), GuiTheme.LIVE),
                new Tile(town != null && !deleted() ? String.valueOf(town.memberCount())
                        : mapTown != null ? String.valueOf(mapTown.members()) : "—",
                        label("betterloka.town_finder.members"), GuiTheme.ACCENT),
                new Tile(String.valueOf(territories), label("betterloka.town.territories_short"), GuiTheme.GOOD)));
        y += SECTION_GAP - TILE_GAP;

        if (town != null && !deleted()) {
            y = section(context, x, y, width, Component.translatable("betterloka.town.section.war"));
            y = renderWar(context, x, y, width);
            y += SECTION_GAP - TILE_GAP;

            y = section(context, x, y, width, Component.translatable("betterloka.town.section.people"));
            y = renderPeople(context, x, y, width);
            y = renderActivity(context, x, y, width);
            y += SECTION_GAP - TILE_GAP;

            y = section(context, x, y, width, Component.translatable("betterloka.town_finder.alliance"));
            y = renderAlliance(context, x, y, width);
            y += SECTION_GAP - TILE_GAP;
        } else if (deleted()) {
            y = card(context, x, y, width, 1, (textX, textY, inner) ->
                    context.text(this.font, Component.translatable("betterloka.town_finder.deleted"), textX, textY,
                            GuiTheme.BAD));
            y += SECTION_GAP - TILE_GAP;
        }

        y = section(context, x, y, width, Component.translatable("betterloka.town.section.territories", held.size()));
        y = renderTerritories(context, x, y, width, mouseX, mouseY);

        context.disableScissor();
        scroll.setContentHeight(y - top);
        scroll.render(context, mouseX, mouseY);
    }

    /** The vulnerability window — when, and whether it is open right now — and when it was founded. */
    private int renderWar(GuiGraphicsExtractor context, int x, int y, int width) {
        int hour = town.vulnerabilityWindow();
        String window = hour < 0 ? "—" : String.format(Locale.ROOT, "%02d:00 – %02d:00",
                localHour(hour), (localHour(hour) + VULN_HOURS) % 24);
        Component state;
        int stateColor;
        if (hour < 0) {
            state = Component.literal("—");
            stateColor = GuiTheme.MUTED;
        } else {
            long untilOpenMinutes = minutesUntilOpen(hour, Instant.now());
            if (untilOpenMinutes == 0) {
                long left = minutesLeftOpen(hour, Instant.now());
                state = Component.translatable("betterloka.town.vuln_open", hoursMinutes(left));
                stateColor = GuiTheme.BAD;
            } else {
                state = Component.translatable("betterloka.town.vuln_opens", hoursMinutes(untilOpenMinutes));
                stateColor = GuiTheme.GOOD;
            }
        }
        Component stateText = state;
        int stateTint = stateColor;
        return card(context, x, y, width, 3, (textX, textY, inner) -> {
            GuiTheme.statRow(context, this.font, textX, textY, inner, label("betterloka.town_finder.vuln"), window,
                    GuiTheme.LIVE);
            GuiTheme.statRow(context, this.font, textX, textY + LINE, inner, label("betterloka.town.vuln_now"),
                    stateText.getString(), stateTint);
            GuiTheme.statRow(context, this.font, textX, textY + LINE * 2, inner,
                    label(town.foundedIsImport() ? "betterloka.town_finder.on_record_since"
                            : "betterloka.town_finder.founded"),
                    TimeFormat.dateTime(town.founded()), town.foundedIsImport() ? GuiTheme.MUTED : GuiTheme.TEXT);
        });
    }

    /** Minutes until a window opening at {@code utcHour} opens: 0 while it is open. */
    static long minutesUntilOpen(int utcHour, Instant now) {
        long minuteOfDay = (now.getEpochSecond() / 60) % (24 * 60);
        long start = utcHour * 60L;
        long sinceStart = Math.floorMod(minuteOfDay - start, 24 * 60);
        if (sinceStart < VULN_HOURS * 60L) {
            return 0;
        }
        return 24 * 60 - sinceStart;
    }

    /** Minutes left of a window that is open now. */
    static long minutesLeftOpen(int utcHour, Instant now) {
        long minuteOfDay = (now.getEpochSecond() / 60) % (24 * 60);
        long sinceStart = Math.floorMod(minuteOfDay - utcHour * 60L, 24 * 60);
        return Math.max(0, VULN_HOURS * 60L - sinceStart);
    }

    private static String hoursMinutes(long minutes) {
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    /** An hour of the UTC day, moved into the local zone as it stands today. */
    private static int localHour(int utcHour) {
        return java.time.OffsetTime.of(utcHour, 0, 0, 0, java.time.ZoneOffset.UTC)
                .atDate(java.time.LocalDate.now())
                .atZoneSameInstant(java.time.ZoneId.systemDefault())
                .getHour();
    }

    /** How many columns the sub-owners are set in; a town can have thirty of them. */
    private static final int OFFICER_COLUMNS = 3;

    /** The leader, then every sub-owner, three to a row so a big town's list stays a card, not a scroll. */
    private int renderPeople(GuiGraphicsExtractor context, int x, int y, int width) {
        List<String> subOwners = town.subOwnerIds();
        int officerRows = (subOwners.size() + OFFICER_COLUMNS - 1) / OFFICER_COLUMNS;
        int rows = 1 + (subOwners.isEmpty() ? 0 : 1 + officerRows);
        return card(context, x, y, width, rows, (textX, textY, inner) -> {
            GuiTheme.statRow(context, this.font, textX, textY, inner, label("betterloka.town_finder.leader"),
                    nameOf(town.ownerId()), 0xFFE3B54E);
            if (subOwners.isEmpty()) {
                return;
            }
            context.fill(textX, textY + LINE - 2, textX + inner, textY + LINE - 1, SEPARATOR);
            context.text(this.font, Component.translatable("betterloka.town_finder.subowners", subOwners.size()),
                    textX, textY + LINE, GuiTheme.MUTED);
            int column = inner / OFFICER_COLUMNS;
            for (int i = 0; i < subOwners.size(); i++) {
                int cellX = textX + (i % OFFICER_COLUMNS) * column;
                int cellY = textY + LINE * (2 + i / OFFICER_COLUMNS);
                context.text(this.font, this.font.plainSubstrByWidth(nameOf(subOwners.get(i)), column - 4),
                        cellX, cellY, GuiTheme.TEXT);
            }
        });
    }

    private String nameOf(String identity) {
        if (identity == null) {
            return "—";
        }
        String name = names.get(identity);
        if (name == null) {
            return "...";
        }
        return name.isEmpty() ? "?" : name;
    }

    /**
     * The active count, and what it means for how long the town has left — read off the
     * {@code /town info} panel the last time the player opened it, since Loka publishes it nowhere.
     */
    private int renderActivity(GuiGraphicsExtractor context, int x, int y, int width) {
        TownActivityStore activity = BetterLokaClient.townActivity();
        TownInfoReading reading = activity == null ? null : activity.latest(town.name());
        if (reading == null) {
            List<FormattedCharSequence> lines = this.font.split(
                    Component.translatable("betterloka.town_finder.no_activity"), width - CARD_PADDING * 2);
            return card(context, x, y, width, lines.size(), (textX, textY, inner) -> {
                int lineY = textY;
                for (FormattedCharSequence line : lines) {
                    context.text(this.font, line, textX, lineY, GuiTheme.MUTED);
                    lineY += LINE;
                }
            });
        }
        boolean zero = reading.atZero();
        long days = activity.daysAtZero(town.name());
        long deletion = activity.deletionNoEarlierThan(town.name());
        return card(context, x, y, width, zero ? 3 : 2, (textX, textY, inner) -> {
            GuiTheme.statRow(context, this.font, textX, textY, inner, label("betterloka.town_finder.active"),
                    reading.hasActive() ? reading.active() + " / " + reading.members() : "—",
                    zero ? GuiTheme.BAD : GuiTheme.GOOD);
            GuiTheme.statRow(context, this.font, textX, textY + LINE, inner, label("betterloka.town_finder.read_at"),
                    TimeFormat.ago(reading.readAt()), GuiTheme.MUTED);
            if (zero) {
                GuiTheme.statRow(context, this.font, textX, textY + LINE * 2, inner,
                        Component.translatable("betterloka.town_finder.falls_after", days).getString(),
                        TimeFormat.date(Instant.ofEpochMilli(deletion)), GuiTheme.LIVE);
            }
        });
    }

    private int renderAlliance(GuiGraphicsExtractor context, int x, int y, int width) {
        int shown = Math.min(MAX_PARTNERS, partners.size());
        return card(context, x, y, width, 2 + Math.max(1, shown), (textX, textY, inner) -> {
            String allianceName = alliance != null ? alliance.name()
                    : mapTown != null && mapTown.hasAlliance() ? mapTown.alliance() : null;
            GuiTheme.statRow(context, this.font, textX, textY, inner, label("betterloka.town_finder.alliance"),
                    allianceName == null ? label("betterloka.town_finder.no_alliance") : allianceName,
                    allianceName == null ? GuiTheme.MUTED : GuiTheme.ACCENT);
            context.fill(textX, textY + LINE - 2, textX + inner, textY + LINE - 1, SEPARATOR);
            context.text(this.font, Component.translatable("betterloka.town_finder.usual_allies"), textX,
                    textY + LINE, GuiTheme.MUTED);
            if (partners.isEmpty()) {
                context.text(this.font, this.font.plainSubstrByWidth(
                                label("betterloka.town_finder.no_ally_history"), inner),
                        textX, textY + LINE * 2, GuiTheme.MUTED);
                return;
            }
            for (int i = 0; i < shown; i++) {
                TownLogStore.TownPartner partner = partners.get(i);
                String partnerName = partner.name() != null ? partner.name() : partner.townId();
                GuiTheme.statRow(context, this.font, textX, textY + LINE * (2 + i), inner,
                        this.font.plainSubstrByWidth(partnerName, inner / 2),
                        Component.translatable("betterloka.town_finder.ally_days", partner.days()).getString(),
                        GuiTheme.TEXT);
            }
        });
    }

    /** One row per territory, edged in the town's colour; a click shows it on the map. */
    private int renderTerritories(GuiGraphicsExtractor context, int x, int y, int width, int mouseX, int mouseY) {
        if (held.isEmpty()) {
            return card(context, x, y, width, 1, (textX, textY, inner) ->
                    context.text(this.font, Component.translatable("betterloka.town_finder.no_territories"),
                            textX, textY, GuiTheme.MUTED));
        }
        for (MapTerritory territory : held) {
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ROW_HEIGHT
                    && mouseY >= TOP && mouseY < bottom();
            MapStyle.card(context, x, y, width, ROW_HEIGHT, hovered ? color : CARD_BORDER);
            context.fill(x + 1, y + 2, x + 3, y + ROW_HEIGHT - 2, color);
            int textX = x + CARD_PADDING + 3;
            int right = x + width - CARD_PADDING;

            String title = "#" + territory.number()
                    + (territory.areaName() != null ? "  " + territory.areaName() : "");
            Component head = territory.seat()
                    ? Component.literal(title).append(Component.literal("  ").append(
                    Component.translatable("betterloka.town.seat").withStyle(ChatFormatting.GOLD)))
                    : Component.literal(title);
            context.text(this.font, head, textX, y + 4, hovered ? GuiTheme.ACCENT : GuiTheme.TEXT);
            String where = Math.round(territory.centerX()) + ", " + Math.round(territory.centerZ());
            context.text(this.font, where, right - this.font.width(where), y + 4, GuiTheme.MUTED);

            List<String> notes = new ArrayList<>();
            if (territory.hasConquestPoints()) {
                notes.add(territory.conquestPoints() + " CP");
            }
            if (territory.mutator() != null && !territory.mutator().isBlank()) {
                notes.add(territory.mutator());
            }
            if (territory.hasBonus()) {
                notes.add(territory.bonus());
            }
            String second = notes.isEmpty() ? label("betterloka.town.show_hint") : String.join("  ·  ", notes);
            context.text(this.font, this.font.plainSubstrByWidth(second, width - CARD_PADDING * 2 - 6), textX,
                    y + 14, notes.isEmpty() ? 0xFF6E6E7A : GuiTheme.LIVE);

            rowBounds.add(new int[]{x, y, width, ROW_HEIGHT});
            rowTerritories.add(territory);
            y += ROW_HEIGHT + TILE_GAP;
        }
        return y;
    }

    // --- small pieces, as the profiles draw them ---

    private int section(GuiGraphicsExtractor context, int x, int y, int width, Component title) {
        context.fill(x, y, x + 2, y + 8, color);
        context.text(this.font, title.copy().withStyle(ChatFormatting.BOLD), x + 6, y, GuiTheme.TEXT);
        context.fill(x, y + 11, x + width, y + 12, SEPARATOR);
        return y + 16;
    }

    private int tiles(GuiGraphicsExtractor context, int x, int y, int width, int perRow, List<Tile> tiles) {
        int tileWidth = (width - TILE_GAP * (perRow - 1)) / perRow;
        int rows = (tiles.size() + perRow - 1) / perRow;
        for (int i = 0; i < tiles.size(); i++) {
            int column = i % perRow;
            int row = i / perRow;
            int tileX = x + column * (tileWidth + TILE_GAP);
            int thisWidth = column == perRow - 1 ? x + width - tileX : tileWidth;
            int tileY = y + row * (TILE_HEIGHT + TILE_GAP);
            Tile tile = tiles.get(i);
            MapStyle.card(context, tileX, tileY, thisWidth, TILE_HEIGHT, CARD_BORDER);
            context.fill(tileX + 2, tileY + 1, tileX + thisWidth - 2, tileY + 3, tile.color());
            context.fill(tileX + 2, tileY + 3, tileX + thisWidth - 2, tileY + TILE_HEIGHT - 2,
                    0x14000000 | (tile.color() & 0xFFFFFF));
            context.centeredText(this.font, Component.literal(tile.value()).withStyle(ChatFormatting.BOLD),
                    tileX + thisWidth / 2, tileY + 7, tile.color());
            context.centeredText(this.font, this.font.plainSubstrByWidth(tile.label(), thisWidth - 6),
                    tileX + thisWidth / 2, tileY + 18, GuiTheme.MUTED);
        }
        return y + rows * (TILE_HEIGHT + TILE_GAP);
    }

    private int card(GuiGraphicsExtractor context, int x, int y, int width, int rows, CardBody body) {
        int height = CARD_PADDING * 2 + LINE * rows - 2;
        MapStyle.card(context, x, y, width, height, CARD_BORDER);
        body.draw(x + CARD_PADDING, y + CARD_PADDING, width - CARD_PADDING * 2);
        return y + height + TILE_GAP;
    }

    @FunctionalInterface
    private interface CardBody {
        void draw(int x, int y, int inner);
    }

    private static String label(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(map);
    }
}
