package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ScheduledFight;
import com.betterloka.api.model.TerritoryDetails;
import com.betterloka.fights.FightTiming;
import com.betterloka.fights.FightWatch;
import com.betterloka.map.MapTerritory;
import com.betterloka.stats.PlayerTrait;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fights, on the map: a list of everything declared, as the Fight Manager used to show it, and
 * one fight's details — who is fighting whom, when it goes off, who is warping in, whether
 * reinforcements may be called, and the mutator and bonuses of the ground it is over.
 *
 * <p>Drawn over the left of the map, under a button that shows or hides it.
 */
final class FightsPanel {
    /** What the panel needs of the map it sits on. */
    interface Host {
        /** Flies the map to the fight's territory, switching continent if it has to. */
        void showOnMap(ScheduledFight fight);

        /** The territory as the map draws it, for its mutator and bonus text, or {@code null}. */
        MapTerritory mapTerritory(ScheduledFight fight);
    }

    static final int ATTACKER = 0xFFFF6B6B;
    static final int DEFENDER = 0xFF7BD88F;
    private static final int BORDER = 0xFF8A3030;
    private static final int LINE = 10;
    private static final int PAD = 5;
    private static final int CARD_GAP = 3;
    private static final int HEADER = 18;

    /** Under this long to go, the countdown turns red. */
    private static final long SOON_MILLIS = 5 * 60 * 1000L;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    /**
     * Whether the panel is up. Shut whenever the map opens: the map is for the map, and the list is
     * one press of Fights away — or opens by itself on a fight picked from the map or the banner.
     */
    private boolean open = false;

    private record Hit(int x, int y, int width, int height, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private final Host host;
    private final ScrollPanel listScroll = new ScrollPanel();
    private final ScrollPanel detailScroll = new ScrollPanel();
    private final List<Hit> hits = new ArrayList<>();
    private String detailId;

    private int x;
    private int y;
    private int width;
    private int height;

    FightsPanel(Host host) {
        this.host = host;
    }

    boolean isOpen() {
        return open;
    }

    /** Shows or hides the panel; shown again, it opens on the list. */
    void toggle() {
        open = !open;
        if (open) {
            detailId = null;
        }
    }

    /** Opens the panel on one fight's details. */
    void showDetail(ScheduledFight fight) {
        open = true;
        if (fight.id() == null || !fight.id().equals(detailId)) {
            detailScroll.reset();
        }
        detailId = fight.id();
    }

    String detailId() {
        return detailId;
    }

    void place(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        listScroll.setViewport(x + 3, y + HEADER, width - 6, Math.max(20, height - HEADER - 4));
        detailScroll.setViewport(x + 3, y + HEADER, width - 6, Math.max(20, height - HEADER - 4));
    }

    int x() {
        return x;
    }

    boolean contains(double px, double py) {
        return open &&px >= x && px < x + width && py >= y && py < y + height;
    }

    // --- drawing ---

    void render(GuiGraphicsExtractor context, Font font, FightWatch watch, long now, int mouseX, int mouseY) {
        hits.clear();
        if (!open) {
            return;
        }
        context.fill(x, y, x + width, y + height, 0xE60B0B10);
        FilterMenu.outline(context, x, y, width, height, BORDER);

        ScheduledFight detail = detailId == null ? null : watch.byId(detailId);
        if (detailId != null && detail == null && watch.updatedAt() > 0) {
            // The fight is over and has left the books.
            detailId = null;
        }
        if (detail != null) {
            renderDetail(context, font, watch, detail, now, mouseX, mouseY);
        } else {
            renderList(context, font, watch, now, mouseX, mouseY);
        }
    }

    private void renderList(GuiGraphicsExtractor context, Font font, FightWatch watch, long now,
                            int mouseX, int mouseY) {
        List<ScheduledFight> fights = watch.fights();
        context.text(font, Component.translatable("betterloka.fights.title", fights.size())
                .withStyle(ChatFormatting.BOLD), x + PAD, y + 5, GuiTheme.TEXT);
        String checked = watch.updatedAt() == 0 ? "..."
                : Component.translatable("betterloka.fights.checked",
                        FightTiming.clock(now - watch.updatedAt()) ).getString();
        int checkedColor = watch.failed() ? GuiTheme.BAD : GuiTheme.MUTED;
        context.text(font, checked, x + width - PAD - font.width(checked), y + 5, checkedColor);

        int left = x + 3;
        int cardWidth = listScroll.contentWidth();
        context.enableScissor(left, listScroll.viewportTop(), left + cardWidth + ScrollPanel.BAR_WIDTH + 2,
                listScroll.viewportBottom());
        int cy = listScroll.contentTop();
        if (fights.isEmpty()) {
            String key = watch.updatedAt() == 0 ? "betterloka.fights.loading"
                    : watch.failed() ? "betterloka.fights.unreachable" : "betterloka.fights.none";
            for (FormattedCharSequence line : font.split(Component.translatable(key), cardWidth - 8)) {
                context.text(font, line, left + 4, cy + 4, GuiTheme.MUTED);
                cy += LINE;
            }
            cy += 8;
        }
        boolean inView = mouseY >= listScroll.viewportTop() && mouseY < listScroll.viewportBottom();
        for (ScheduledFight fight : fights) {
            int cardHeight = PAD * 2 + LINE * 4;
            if (cy + cardHeight >= listScroll.viewportTop() && cy <= listScroll.viewportBottom()) {
                boolean hovered = inView && mouseX >= left && mouseX < left + cardWidth
                        && mouseY >= cy && mouseY < cy + cardHeight;
                drawCard(context, font, watch, fight, now, left, cy, cardWidth, cardHeight, hovered);
                int top = Math.max(cy, listScroll.viewportTop());
                int bottom = Math.min(cy + cardHeight, listScroll.viewportBottom());
                if (bottom > top) {
                    hits.add(new Hit(left, top, cardWidth, bottom - top, () -> {
                        showDetail(fight);
                        host.showOnMap(fight);
                    }));
                }
            }
            cy += cardHeight + CARD_GAP;
        }
        context.disableScissor();
        listScroll.setContentHeight(cy - listScroll.contentTop());
        listScroll.render(context, mouseX, mouseY);
    }

    private void drawCard(GuiGraphicsExtractor context, Font font, FightWatch watch, ScheduledFight fight,
                          long now, int left, int top, int cardWidth, int cardHeight, boolean hovered) {
        context.fill(left, top, left + cardWidth, top + cardHeight, hovered ? 0xFF24242E : 0xFF16161C);
        FilterMenu.outline(context, left, top, cardWidth, cardHeight, hovered ? 0xFF8A8AA8 : 0xFF2C2C38);
        context.fill(left, top, left + 2, top + cardHeight, statusColor(fight, now));

        int tx = left + PAD + 1;
        int ty = top + PAD;
        int inner = cardWidth - PAD * 2 - 2;

        // Who against whom: attacker red, defender green.
        String attacker = attackerName(fight);
        String defender = fight.defended() ? defenderName(fight)
                : Component.translatable("betterloka.fights.neutral").getString();
        String versus = "  vs  ";
        int sideWidth = (inner - font.width(versus)) / 2;
        String a = font.plainSubstrByWidth(attacker, sideWidth);
        context.text(font, Component.literal(a).withStyle(ChatFormatting.BOLD), tx, ty, ATTACKER);
        int vx = tx + Math.min(font.width(Component.literal(a).withStyle(ChatFormatting.BOLD)), sideWidth);
        context.text(font, versus, vx, ty, GuiTheme.MUTED);
        context.text(font, Component.literal(font.plainSubstrByWidth(defender, sideWidth))
                .withStyle(ChatFormatting.BOLD), vx + font.width(versus), ty, DEFENDER);

        // Their towns — the names above are usually their alliances — or failing that, the alliances.
        String sides = joinNonNull(townOrAlliance(fight.attackerTownId(), fight.attackerAllianceId(), attacker),
                fight.defended() ? townOrAlliance(fight.defenderTownId(), fight.defenderAllianceId(), defender) : null);
        if (sides != null) {
            context.text(font, font.plainSubstrByWidth(sides, inner), tx, ty + LINE, GuiTheme.MUTED);
        }

        // Where, and whether reinforcements may come.
        String where = where(fight, host.mapTerritory(fight));
        Component reins = Component.translatable(fight.reinforcementsAllowed()
                ? "betterloka.fights.reins_on" : "betterloka.fights.reins_off");
        int reinsColor = reinsColor(fight);
        int chipWidth = font.width(reins) + 6;
        int whereWidth = inner - chipWidth - 4;
        if (watch.isNew(fight, now)) {
            String fresh = Component.translatable("betterloka.fights.new").getString();
            int freshWidth = font.width(fresh) + 6;
            GuiTheme.chip(context, tx, ty + LINE * 2 - 2, freshWidth, 11, GuiTheme.LIVE);
            context.text(font, fresh, tx + 3, ty + LINE * 2, GuiTheme.LIVE);
            context.text(font, font.plainSubstrByWidth(where, whereWidth - freshWidth - 4), tx + freshWidth + 4,
                    ty + LINE * 2, GuiTheme.TEXT);
        } else {
            context.text(font, font.plainSubstrByWidth(where, whereWidth), tx, ty + LINE * 2, GuiTheme.TEXT);
        }
        GuiTheme.chip(context, tx + inner - chipWidth, ty + LINE * 2 - 2, chipWidth, 11, reinsColor);
        context.text(font, reins, tx + inner - chipWidth + 3, ty + LINE * 2, reinsColor);

        // When, and how many are warping.
        context.text(font, status(fight, now), tx, ty + LINE * 3, statusColor(fight, now));
        String turnout = turnout(fight);
        context.text(font, turnout, tx + inner - font.width(turnout), ty + LINE * 3, GuiTheme.TEXT);
    }

    private void renderDetail(GuiGraphicsExtractor context, Font font, FightWatch watch, ScheduledFight fight,
                              long now, int mouseX, int mouseY) {
        // The way back to the list, and to the fight on the map.
        Component back = Component.translatable("betterloka.fights.back");
        boolean overBack = mouseX >= x + PAD && mouseX < x + PAD + font.width(back) && mouseY >= y + 3
                && mouseY < y + 15;
        context.text(font, back, x + PAD, y + 5, overBack ? GuiTheme.ACCENT : GuiTheme.MUTED);
        hits.add(new Hit(x + PAD, y + 3, font.width(back), 12, () -> detailId = null));
        Component show = Component.translatable("betterloka.fights.show_on_map");
        int showX = x + width - PAD - font.width(show);
        boolean overShow = mouseX >= showX && mouseX < showX + font.width(show) && mouseY >= y + 3
                && mouseY < y + 15;
        context.text(font, show, showX, y + 5, overShow ? GuiTheme.ACCENT : GuiTheme.TEXT);
        hits.add(new Hit(showX, y + 3, font.width(show), 12, () -> host.showOnMap(fight)));

        int left = x + 3;
        int contentWidth = detailScroll.contentWidth();
        int textWidth = contentWidth - 8;
        int tx = left + 4;
        context.enableScissor(left, detailScroll.viewportTop(), left + contentWidth + ScrollPanel.BAR_WIDTH + 2,
                detailScroll.viewportBottom());
        int cy = detailScroll.contentTop() + 2;

        MapTerritory mapTerritory = host.mapTerritory(fight);
        TerritoryDetails details = watch.territory(fight.territoryId());

        // Where.
        context.text(font, Component.literal(where(fight, mapTerritory)).withStyle(ChatFormatting.BOLD),
                tx, cy, GuiTheme.TEXT);
        cy += LINE + 2;

        // When: a countdown, the clock time, and when it was declared.
        context.text(font, Component.literal(status(fight, now)).withStyle(ChatFormatting.BOLD), tx, cy,
                statusColor(fight, now));
        cy += LINE + 1;
        long startsAt = FightTiming.startsAt(fight);
        if (startsAt > 0) {
            String key = fight.started() ? "betterloka.fights.started_at" : "betterloka.fights.starts_at";
            cy = wrap(context, font, Component.translatable(key, CLOCK.format(Instant.ofEpochMilli(startsAt))),
                    tx, cy, textWidth, GuiTheme.TEXT);
        }
        long declared = FightTiming.declaredAt(fight);
        if (declared > 0) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.declared",
                    CLOCK.format(Instant.ofEpochMilli(declared)), TimeFormat.ago(declared)), tx, cy, textWidth,
                    GuiTheme.MUTED);
        }
        cy += 4;

        // Reinforcements.
        Component reins = Component.translatable(fight.reinforcementsAllowed()
                ? "betterloka.fights.reins_on" : "betterloka.fights.reins_off");
        int chipWidth = font.width(reins) + 6;
        context.text(font, Component.translatable("betterloka.fights.reinforcements"), tx, cy, GuiTheme.MUTED);
        int chipX = tx + font.width(Component.translatable("betterloka.fights.reinforcements")) + 6;
        GuiTheme.chip(context, chipX, cy - 2, chipWidth, 11, reinsColor(fight));
        context.text(font, reins, chipX + 3, cy, reinsColor(fight));
        cy += LINE + 6;

        // The two sides.
        cy = side(context, font, fight, true, tx, cy, textWidth, now);
        cy += 4;
        cy = side(context, font, fight, false, tx, cy, textWidth, now);
        cy += 4;

        // The ground: mutator, buff, bonus, what it makes.
        context.fill(tx, cy, tx + textWidth, cy + 1, 0xFF33333F);
        cy += 4;
        context.text(font, Component.translatable("betterloka.fights.territory").withStyle(ChatFormatting.BOLD),
                tx, cy, GuiTheme.TEXT);
        cy += LINE + 1;
        int before = cy;
        String mutator = details != null && details.mutator() != null ? TerritoryDetails.pretty(details.mutator())
                : mapTerritory != null ? mapTerritory.mutator() : null;
        if (mutator != null) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.mutator", mutator)
                    .withStyle(ChatFormatting.LIGHT_PURPLE), tx, cy, textWidth, GuiTheme.TEXT);
        }
        if (details != null && details.buff() != null) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.buff",
                    TerritoryDetails.pretty(details.buff())).withStyle(ChatFormatting.GOLD), tx, cy, textWidth,
                    GuiTheme.TEXT);
        }
        if (mapTerritory != null && mapTerritory.hasBonus()) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.bonus", mapTerritory.bonus())
                    .withStyle(ChatFormatting.YELLOW), tx, cy, textWidth, GuiTheme.TEXT);
        }
        if (details != null && details.bountifulItem() != null) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.bountiful",
                    TerritoryDetails.pretty(details.bountifulItem())), tx, cy, textWidth, GuiTheme.LIVE);
        }
        if (details != null && details.infestedType() != null) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.infested",
                    TerritoryDetails.pretty(details.infestedType())), tx, cy, textWidth, GuiTheme.BAD);
        }
        if (cy == before) {
            cy = wrap(context, font, Component.translatable(details == null && mapTerritory == null
                    ? "betterloka.fights.territory_loading" : "betterloka.fights.no_modifiers"),
                    tx, cy, textWidth, GuiTheme.MUTED);
        }
        if (details != null && details.resource() != null) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.resource", details.resource()),
                    tx, cy, textWidth, GuiTheme.MUTED);
        }
        int points = mapTerritory != null && mapTerritory.hasConquestPoints() ? mapTerritory.conquestPoints()
                : details != null ? details.conquestPoints() : 0;
        if (points > 0) {
            cy = wrap(context, font, Component.translatable("betterloka.map.cp_per_day", points), tx, cy,
                    textWidth, MapStyle.CONQUEST_POINTS);
        }
        cy += 6;

        context.disableScissor();
        detailScroll.setContentHeight(cy - detailScroll.contentTop());
        detailScroll.render(context, mouseX, mouseY);
    }

    /** One side: who, which alliance, how strong, and who is warping in. */
    private int side(GuiGraphicsExtractor context, Font font, ScheduledFight fight, boolean attacking,
                     int tx, int cy, int textWidth, long now) {
        int colour = attacking ? ATTACKER : DEFENDER;
        context.text(font, Component.translatable(attacking ? "betterloka.fights.attackers"
                : "betterloka.fights.defenders").withStyle(ChatFormatting.BOLD), tx, cy, colour);
        cy += LINE + 1;
        if (!attacking && !fight.defended()) {
            return wrap(context, font, Component.translatable("betterloka.fights.neutral_ground"), tx, cy,
                    textWidth, GuiTheme.MUTED);
        }
        String name = attacking ? attackerName(fight) : defenderName(fight);
        cy = wrap(context, font, Component.literal(name).withStyle(ChatFormatting.BOLD), tx, cy, textWidth,
                GuiTheme.TEXT);
        String townId = attacking ? fight.attackerTownId() : fight.defenderTownId();
        LokaTown town = townId == null ? null : BetterLokaClient.towns().byId(townId);
        String alliance = allianceName(attacking ? fight.attackerAllianceId() : fight.defenderAllianceId());
        if (alliance != null && !alliance.equals(name)) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.alliance", alliance), tx, cy,
                    textWidth, GuiTheme.MUTED);
        }
        if (town != null && !town.name().equals(name)) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.town", town.name()), tx, cy,
                    textWidth, GuiTheme.MUTED);
        }
        double strength = attacking ? fight.attackerStrength() : fight.defenderStrength();
        if (strength > 0) {
            cy = wrap(context, font, Component.translatable("betterloka.fights.strength",
                    String.format(Locale.ROOT, "%.0f", strength)), tx, cy, textWidth, GuiTheme.MUTED);
        }
        ScheduledFight.Side side = attacking ? fight.attackers() : fight.defenders();
        Component count = fight.started()
                ? Component.translatable("betterloka.fights.in_fight", side.present(), side.signedUp())
                : Component.translatable("betterloka.fights.warping_count", side.signedUp());
        cy = wrap(context, font, count, tx, cy, textWidth, colour);

        // Who: names fill in as they are looked up.
        List<String> ids = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (ScheduledFight.Warper warper : side.players()) {
            if (warper.identityId() != null) {
                ids.add(warper.identityId());
            }
            String player = warper.identityId() == null ? null
                    : BetterLokaClient.market().sellerNameIfKnown(warper.identityId());
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(player == null ? "..." : player.isEmpty() ? "?" : player);
            if (fight.started() && warper.present()) {
                names.append(" (").append(warper.kills()).append('/').append(warper.deaths()).append(')');
            }
        }
        BetterLokaClient.market().requestSellerNames(ids);
        if (!names.isEmpty()) {
            cy = wrap(context, font, Component.literal(names.toString()), tx + 4, cy, textWidth - 4, GuiTheme.TEXT);
        }
        return cy;
    }

    private static int wrap(GuiGraphicsExtractor context, Font font, Component text, int x, int y, int width,
                            int color) {
        for (FormattedCharSequence line : font.split(text, Math.max(20, width))) {
            context.text(font, line, x, y, color);
            y += LINE;
        }
        return y;
    }

    // --- what is said about a fight, shared with the map ---

    /** {@code Starts in 52:10}, {@code LIVE 12:03}, or that it is about to start. */
    static String status(ScheduledFight fight, long now) {
        long startsAt = FightTiming.startsAt(fight);
        if (fight.started()) {
            return startsAt > 0
                    ? Component.translatable("betterloka.fights.live_for", FightTiming.clock(now - startsAt)).getString()
                    : Component.translatable("betterloka.fights.live").getString();
        }
        if (startsAt == 0) {
            return Component.translatable("betterloka.fights.start_unknown").getString();
        }
        if (now >= startsAt) {
            return Component.translatable("betterloka.fights.starting").getString();
        }
        return Component.translatable("betterloka.fights.starts_in", FightTiming.clock(startsAt - now)).getString();
    }

    /** The short form for the map: {@code 52:10} or {@code LIVE}. */
    static String shortStatus(ScheduledFight fight, long now) {
        long startsAt = FightTiming.startsAt(fight);
        if (fight.started() || startsAt > 0 && now >= startsAt) {
            return Component.translatable("betterloka.fights.live_short").getString();
        }
        return startsAt == 0 ? "?" : FightTiming.clock(startsAt - now);
    }

    static int statusColor(ScheduledFight fight, long now) {
        long startsAt = FightTiming.startsAt(fight);
        if (fight.started() || startsAt > 0 && now >= startsAt) {
            return GuiTheme.LIVE;
        }
        return startsAt > 0 && startsAt - now < SOON_MILLIS ? GuiTheme.BAD : GuiTheme.ACCENT;
    }

    /** {@code 10 vs 8 warping}, or once under way {@code 7/10 vs 6/8}. */
    static String turnout(ScheduledFight fight) {
        if (fight.started()) {
            return fight.attackers().present() + "/" + fight.attackers().signedUp() + " vs "
                    + fight.defenders().present() + "/" + fight.defenders().signedUp();
        }
        return Component.translatable("betterloka.fights.warping", fight.attackers().signedUp(),
                fight.defenders().signedUp()).getString();
    }

    private static int reinsColor(ScheduledFight fight) {
        return fight.reinforcementsAllowed() ? GuiTheme.traitColor(PlayerTrait.Level.GOOD)
                : GuiTheme.traitColor(PlayerTrait.Level.POOR);
    }

    /** {@code Balak 9 · Northfields}. */
    static String where(ScheduledFight fight, MapTerritory territory) {
        String continent = fight.continent() != null ? fight.continent() : "?";
        String number = fight.territoryNumber() != null ? fight.territoryNumber() : "?";
        String area = territory != null && territory.areaName() != null && !territory.areaName().isBlank()
                ? territory.areaName() : null;
        return area == null ? continent + " " + number : continent + " " + number + " · " + area;
    }

    static String attackerName(ScheduledFight fight) {
        return sideName(fight.attackerName(), allianceName(fight.attackerAllianceId()),
                fight.attackerTownId(), fight.readableName());
    }

    static String defenderName(ScheduledFight fight) {
        return sideName(fight.defenderName(), allianceName(fight.defenderAllianceId()),
                fight.defenderTownId(), "?");
    }

    /**
     * What to call one side.
     *
     * <p>Loka names both sides on the battle record itself — "Primate Pact", "Golden Hooligans" —
     * which is what a person reads first. The alliance and the town are fallbacks for a record that
     * names nobody.
     */
    private static String sideName(String published, String alliance, String townId, String fallback) {
        if (published != null && !published.isBlank()) {
            return published;
        }
        if (alliance != null && !alliance.isBlank()) {
            return alliance;
        }
        LokaTown town = townId == null ? null : BetterLokaClient.towns().byId(townId);
        if (town != null) {
            return town.name();
        }
        return fallback == null ? "?" : fallback;
    }

    static String allianceName(String allianceId) {
        if (allianceId == null) {
            return null;
        }
        for (LokaAlliance alliance : BetterLokaClient.townLogger().alliances()) {
            if (allianceId.equals(alliance.id())) {
                return alliance.name();
            }
        }
        return null;
    }

    /** The side's town, or its alliance, whichever is known and is not already its name. */
    private static String townOrAlliance(String townId, String allianceId, String name) {
        LokaTown town = townId == null ? null : BetterLokaClient.towns().byId(townId);
        if (town != null && !town.name().equals(name)) {
            return town.name();
        }
        String alliance = allianceName(allianceId);
        return alliance != null && !alliance.equals(name) ? alliance : null;
    }

    private static String joinNonNull(String a, String b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? "-" : a) + "  ·  " + (b == null ? "-" : b);
    }

    // --- input ---

    /** @return whether the click was the panel's; any click inside it is, so the map under it stays still. */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        ScrollPanel scroll = detailId != null ? detailScroll : listScroll;
        if (scroll.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (hit.contains(mouseX, mouseY)) {
                hit.action().run();
                return true;
            }
        }
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        (detailId != null ? detailScroll : listScroll).mouseScrolled(mouseX, mouseY, amount);
        return true;
    }

    boolean mouseDragged(double mouseY) {
        return open && (detailId != null ? detailScroll : listScroll).mouseDragged(mouseY);
    }

    void mouseReleased() {
        listScroll.mouseReleased();
        detailScroll.mouseReleased();
    }

    /** The middle of the {@code i}th card of the list, for the dev harness. */
    int[] devCard(int i) {
        int index = 0;
        for (Hit hit : hits) {
            if (index++ == i) {
                return new int[]{hit.x() + hit.width() / 2, hit.y() + hit.height() / 2};
            }
        }
        return null;
    }
}
