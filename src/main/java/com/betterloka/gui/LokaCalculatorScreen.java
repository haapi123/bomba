package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.ScheduledFight;
import com.betterloka.api.model.Territory;
import com.betterloka.conquest.Simulation;
import com.betterloka.conquest.Standings;
import com.betterloka.conquest.StandingsService;
import com.betterloka.conquest.StrengthMath;
import com.betterloka.fights.FightTiming;
import com.betterloka.fights.FightWatch;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loka Calculator: what a conquest fight is worth in Strength.
 *
 * <p>The player names their town (it is looked up from their own name the first time) and the
 * screen fills in the rest: the alliance whose Strength the town fights with, where it stands on its
 * continent, every fight declared for or against it — which side it is on, against whom, and what a
 * win gains and a loss costs, with the ranking either would leave — and the same two numbers against
 * every other side on the continent. A simulation plays several fights out in a row, each from the
 * Strength the one before left. A row of three boxes at the bottom works anything else out by hand.
 *
 * <p>The arithmetic is {@link StrengthMath}; who stands where is {@link Standings}, read afresh
 * while the screen is open.
 */
public class LokaCalculatorScreen extends Screen {
    private static final int MAX_WIDTH = 520;
    private static final int TOP = 24;
    private static final int LINE = 10;
    private static final int PAD = 6;
    private static final int GAP = 5;
    private static final int BOX_WIDTH = 52;
    /** How many of a ranking a fight's card names for either outcome. */
    private static final int TOP_SHOWN = 3;

    private record Hit(int x, int y, int width, int height, Runnable action) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    // Kept between openings, as the answer to "which town" does not change in a session.
    private static String query;
    private static boolean balak;
    /** The fights being simulated, and for which side on which world; kept between openings. */
    private static final List<Simulation.Fight> simulated = new ArrayList<>();
    private static String simulatedFor;
    private static boolean lookedUpOwnTown;
    private static LokaCalculatorScreen devInstance;

    private final Screen parent;
    private final ScrollPanel scroll = new ScrollPanel();
    private final List<Hit> hits = new ArrayList<>();

    private EditBox townField;
    private EditBox youField;
    private EditBox themField;
    private EditBox levelField;
    private String manualYou = "";
    private String manualThem = "";
    private String manualLevel = "";

    private Standings seen;
    private String seenQuery;
    private Standings.Match match;
    /** What the three boxes were last filled in for, so typing in them is not written over. */
    private String filledFor;
    /** What the boxes were last worked out from; nothing is recomputed until one of these is replaced. */
    private Standings filledStandings;
    private List<ScheduledFight> filledFights;
    private String pickedId;
    /** The simulation as last played out, and what it was played from. */
    private Simulation.Result simResult;
    private Standings simStandings;
    private String simKey;
    private boolean lookingUp;

    public LokaCalculatorScreen(Screen parent) {
        super(Component.translatable("betterloka.module.loka_calculator"));
        this.parent = parent;
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_WIDTH);
    }

    private int left() {
        return (this.width - contentWidth()) / 2;
    }

    private int backY() {
        return this.height - 24;
    }

    /** The three boxes sit above the Back button, under their labels. */
    private int manualY() {
        return backY() - 24;
    }

    private int listTop() {
        return TOP + 18 + GAP;
    }

    private int listBottom() {
        return manualY() - LINE - 2 - GAP - 3;
    }

    @Override
    protected void init() {
        devInstance = this;
        if (query == null) {
            query = BetterLokaClient.config().calculatorTown();
        }
        int left = left();
        int width = contentWidth();

        Component label = Component.translatable("betterloka.calculator.town");
        int toggleWidth = 118;
        int fieldX = left + this.font.width(label) + 6;
        int fieldWidth = Math.max(60, Math.min(190, left + width - toggleWidth - 6 - fieldX));
        townField = new EditBox(this.font, fieldX, TOP, fieldWidth, 18, label);
        townField.setMaxLength(48);
        townField.setHint(Component.translatable("betterloka.calculator.town_hint").withStyle(ChatFormatting.DARK_GRAY));
        townField.setValue(query);
        townField.setResponder(value -> query = value.strip());
        addRenderableWidget(townField);
        if (query.isBlank()) {
            setInitialFocus(townField);
        }
        addRenderableWidget(Button.builder(Component.translatable(balak ? "betterloka.calculator.count_balak"
                        : "betterloka.calculator.count_continent"), button -> {
                    balak = !balak;
                    filledFor = null;
                    pickedId = null;
                    scroll.reset();
                    rebuildWidgets();
                })
                .bounds(left + width - toggleWidth, TOP - 1, toggleWidth, 20).build());

        int y = manualY();
        youField = numberBox(left, y, manualYou, "betterloka.calculator.your_strength", value -> manualYou = value);
        themField = numberBox(left + column(0), y, manualThem, "betterloka.calculator.their_strength", value -> manualThem = value);
        // Balak counts every fight the same, whatever the towns' levels.
        levelField = balak ? null : numberBox(left + column(0) + column(1), y, manualLevel,
                "betterloka.calculator.lowest_level", value -> manualLevel = value);

        scroll.setViewport(left, listTop(), width, listBottom() - listTop());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 50, backY(), 100, 20).build());

        if (query.isBlank() && !lookedUpOwnTown) {
            lookUpOwnTown();
        }
    }

    private static final String[] BOX_LABELS = {"betterloka.calculator.your_strength",
            "betterloka.calculator.their_strength", "betterloka.calculator.lowest_level"};

    /** How far one box and its label push the next one along. */
    private int column(int index) {
        return Math.max(BOX_WIDTH, this.font.width(Component.translatable(BOX_LABELS[index]))) + 10;
    }

    private EditBox numberBox(int x, int y, String value, String labelKey, java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(this.font, x, y, BOX_WIDTH, 18, Component.translatable(labelKey));
        box.setMaxLength(6);
        box.setValue(value);
        box.setResponder(onChange);
        addRenderableWidget(box);
        return box;
    }

    /** The first time, with nothing typed: the player's own town, from their own name. */
    private void lookUpOwnTown() {
        lookedUpOwnTown = true;
        lookingUp = true;
        String name = Minecraft.getInstance().getUser().getName();
        BetterLokaClient.lokaExecutor().execute(() -> {
            String town = null;
            try {
                LokaPlayer player = BetterLokaClient.lokaApi().findPlayerByName(name);
                if (player != null) {
                    // The name on the player's own record is often missing; the town roster is the sure way.
                    com.betterloka.api.model.LokaTown home = BetterLokaClient.lokaApi().findTownByMember(player.identityId());
                    town = home != null ? home.name() : player.inlineTownName();
                }
            } catch (Exception e) {
                // Not on Loka, or Loka cannot be reached: the box simply stays empty.
            }
            String found = town;
            Minecraft.getInstance().execute(() -> {
                lookingUp = false;
                if (found != null && !found.isBlank() && query.isBlank()) {
                    query = found;
                    BetterLokaClient.config().setCalculatorTown(found);
                    if (townField != null) {
                        townField.setValue(found);
                    }
                }
            });
        });
    }

    // ---- what the screen is about ----

    private void resolve(Standings standings) {
        if (standings == seen && query.equals(seenQuery)) {
            return;
        }
        seen = standings;
        seenQuery = query;
        Standings.Match found = standings.find(query);
        if (found == null || match == null || !found.side().id().equals(match.side().id())) {
            pickedId = null;
            scroll.reset();
        }
        match = found;
        if (match != null) {
            // A town in no alliance carries its own Strength, which the saved roster may have hours old.
            BetterLokaClient.standings().watch(match.town() != null ? match.town().id() : match.side().id());
            BetterLokaClient.config().setCalculatorTown(query);
        }
    }

    /** The world whose Strength is being counted: the side's own continent, or Balak. */
    private String world() {
        return balak ? "bigboi" : match == null ? null : match.side().world();
    }

    private static boolean isSide(Standings.Side side, String allianceId, String townId) {
        return side != null && ((side.alliance() && side.id().equals(allianceId)) || side.has(townId));
    }

    /** The fights on the books that this side is in, either way round. */
    private List<ScheduledFight> fightsOf(Standings.Side side) {
        List<ScheduledFight> mine = new ArrayList<>();
        for (ScheduledFight fight : BetterLokaClient.fights().fights()) {
            if (isSide(side, fight.attackerAllianceId(), fight.attackerTownId())
                    || isSide(side, fight.defenderAllianceId(), fight.defenderTownId())) {
                mine.add(fight);
            }
        }
        return mine;
    }

    private int kAgainst(Standings standings, Standings.Side other) {
        return standings.kFactor(world(), match.side(), match.town(), other, null);
    }

    /** Fills the three boxes for a new side, a new world or a row picked from the table. */
    private void fillManual(Standings standings) {
        if (match == null) {
            return;
        }
        List<ScheduledFight> fights = BetterLokaClient.fights().fights();
        if (filledFor != null && standings == filledStandings && fights == filledFights) {
            return;
        }
        filledStandings = standings;
        filledFights = fights;
        String world = world();
        Standings.Side you = match.side();
        Standings.Side them = null;
        if (pickedId != null) {
            for (Standings.Row row : standings.ranking(world, you)) {
                if (row.side().id().equals(pickedId)) {
                    them = row.side();
                }
            }
        }
        if (them == null) {
            for (ScheduledFight fight : fightsOf(you)) {
                Standings.Forecast forecast = standings.forecast(fight);
                if (forecast != null && StrengthMath.isBalak(fight.world()) == balak) {
                    them = isSide(you, fight.attackerAllianceId(), fight.attackerTownId()) ? forecast.defender() : forecast.attacker();
                    break;
                }
            }
        }
        if (them == null) {
            for (Standings.Row row : standings.ranking(world, you)) {
                if (!row.side().id().equals(you.id())) {
                    them = row.side();
                    break;
                }
            }
        }
        String key = you.id() + "|" + world + "|" + (them == null ? "" : them.id()) + "|" + you.strengthOn(world)
                + "|" + (them == null ? "" : them.strengthOn(world));
        if (key.equals(filledFor)) {
            return;
        }
        filledFor = key;
        double level = match.town() != null ? match.town().townLevel() : you.level();
        if (them != null) {
            level = Math.min(level, them.level());
        }
        set(youField, number(you.strengthOn(world)));
        set(themField, them == null ? "" : number(them.strengthOn(world)));
        set(levelField, number(Math.min(StrengthMath.MAX_TOWN_LEVEL, Math.floor(level))));
    }

    private static void set(EditBox box, String value) {
        if (box != null && !box.getValue().equals(value)) {
            box.setValue(value);
        }
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /** {@code +14}, {@code -11}, and a plain {@code 0} for nothing either way. */
    private static String gain(int points) {
        return points == 0 ? "0" : "+" + points;
    }

    private static String loss(int points) {
        return points == 0 ? "0" : "-" + points;
    }

    private static Double parse(String text) {
        try {
            return text == null || text.isBlank() ? null : Double.valueOf(text.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String continentName(String world) {
        String name = Territory.continentOf(world);
        return name == null ? "?" : name;
    }

    // ---- input ----

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && townField != null && townField.isFocused()) {
            BetterLokaClient.config().setCalculatorTown(query);
            BetterLokaClient.standings().refreshSoon();
            BetterLokaClient.fights().refreshSoon();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.y() >= scroll.viewportTop() && click.y() < scroll.viewportBottom()) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(click.x(), click.y())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (scroll.mouseClicked(click.x(), click.y())) {
            return true;
        }
        return super.mouseClicked(click, doubled);
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
    public void onClose() {
        if (devInstance == this) {
            devInstance = null;
        }
        this.minecraft.gui.setScreen(parent);
    }

    // ---- drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        // A shade under the list, so it reads over a bright sky too.
        int left = left();
        context.fill(left - 4, listTop() - 3, left + contentWidth() + 4, listBottom() + 3, 0x70000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 8, GuiTheme.TEXT);
        hits.clear();

        long now = System.currentTimeMillis();
        StandingsService service = BetterLokaClient.standings();
        FightWatch watch = BetterLokaClient.fights();
        service.poll(now);
        watch.poll(now);
        Standings standings = service.standings();
        resolve(standings);
        fillManual(standings);

        int left = left();
        int width = contentWidth();
        context.text(this.font, Component.translatable("betterloka.calculator.town"), left, TOP + 5, GuiTheme.MUTED);
        drawChecked(context, service, now, left + width);

        context.enableScissor(left, scroll.viewportTop(), left + width, scroll.viewportBottom());
        int height = drawContent(context, standings, service, watch, now, mouseX, mouseY);
        context.disableScissor();
        scroll.setContentHeight(height);
        scroll.render(context, mouseX, mouseY);

        drawManual(context, left, width);
    }

    /** {@code checked 12 s ago}, top right, red when Loka could not be reached. */
    private void drawChecked(GuiGraphicsExtractor context, StandingsService service, long now, int right) {
        Component text = service.updatedAt() == 0
                ? Component.translatable(service.failed() ? "betterloka.calculator.unreachable" : "betterloka.calculator.loading")
                : Component.translatable("betterloka.fights.checked", FightTiming.clock(now - service.updatedAt()));
        int x = Math.max(this.width / 2 + this.font.width(this.title) / 2 + 8, right - this.font.width(text));
        context.text(this.font, text, x, 8, service.failed() ? GuiTheme.BAD : GuiTheme.MUTED);
    }

    private int drawContent(GuiGraphicsExtractor context, Standings standings, StandingsService service,
                            FightWatch watch, long now, int mouseX, int mouseY) {
        int left = left();
        int width = scroll.contentWidth();
        int top = scroll.contentTop();
        int y = top;
        if (standings.isEmpty()) {
            return wrap(context, Component.translatable(service.failed() ? "betterloka.calculator.unreachable"
                    : "betterloka.calculator.loading"), left, y + 2, width, service.failed() ? GuiTheme.BAD : GuiTheme.MUTED) - top;
        }
        if (query.isBlank()) {
            return wrap(context, Component.translatable(lookingUp ? "betterloka.calculator.looking_up"
                    : "betterloka.calculator.intro"), left, y + 2, width, GuiTheme.MUTED) - top;
        }
        if (match == null) {
            List<String> names = standings.suggestions(query, 14);
            if (names.isEmpty()) {
                return wrap(context, Component.translatable("betterloka.calculator.no_match", query), left, y + 2,
                        width, GuiTheme.LIVE) - top;
            }
            return drawSuggestions(context, names, left, y + 2, width, mouseX, mouseY) - top;
        }
        y = drawYou(context, standings, left, y, width);
        y = drawFights(context, standings, watch, now, left, y + GAP, width);
        y = drawSimulation(context, standings, left, y + GAP + 2, width, mouseX, mouseY);
        y = drawTable(context, standings, left, y + GAP + 2, width, mouseX, mouseY);
        y = wrap(context, Component.translatable("betterloka.calculator.note"), left, y + GAP + 1, width, 0xFF7A7A88);
        return y + 2 - top;
    }

    /** Names that fit what was typed, to click on. */
    private int drawSuggestions(GuiGraphicsExtractor context, List<String> names, int left, int y, int width,
                                int mouseX, int mouseY) {
        context.text(this.font, Component.translatable("betterloka.calculator.did_you_mean", query), left, y, GuiTheme.MUTED);
        y += LINE + 2;
        int x = left;
        for (String name : names) {
            int chip = this.font.width(name) + 10;
            if (x + chip > left + width && x > left) {
                x = left;
                y += 16;
            }
            boolean hover = mouseX >= x && mouseX < x + chip && mouseY >= y && mouseY < y + 13;
            GuiTheme.chip(context, x, y, chip, 13, hover ? GuiTheme.ACCENT : 0xFF5A5A6E);
            context.text(this.font, name, x + 5, y + 3, hover ? GuiTheme.ACCENT : GuiTheme.TEXT);
            hits.add(new Hit(x, y, chip, 13, () -> {
                query = name;
                if (townField != null) {
                    townField.setValue(name);
                }
            }));
            x += chip + 4;
        }
        return y + 16;
    }

    /** Who the numbers are for: the side, its town, and where it stands. */
    private int drawYou(GuiGraphicsExtractor context, Standings standings, int left, int y, int width) {
        Standings.Side you = match.side();
        List<Component> lines = new ArrayList<>();
        Component kind;
        if (!you.alliance()) {
            kind = Component.translatable("betterloka.calculator.kind_town", number(Math.floor(you.level())));
        } else if (match.town() != null) {
            kind = Component.translatable("betterloka.calculator.kind_alliance_of", match.town().name(),
                    number(Math.floor(match.town().townLevel())), you.townIds().size());
        } else {
            kind = Component.translatable("betterloka.calculator.kind_alliance", you.townIds().size());
        }
        lines.add(kind);

        List<Standings.Row> home = standings.ranking(you.world());
        lines.add(Component.translatable("betterloka.calculator.stands", continentName(you.world()),
                number(you.strength()), standings.rankOf(you.world(), you), home.size()));
        if (you.balakStrength() != Standings.BASELINE) {
            lines.add(Component.translatable("betterloka.calculator.stands", "Balak", number(you.balakStrength()),
                    standings.rankOf("bigboi", you), standings.ranking("bigboi").size()));
        } else {
            lines.add(Component.translatable("betterloka.calculator.balak_untouched", number(you.balakStrength())));
        }

        int height = 2 * PAD + LINE + 2 + lines.size() * LINE - 1;
        GuiTheme.panel(context, left, y, width, height);
        context.fill(left, y, left + 2, y + height, GuiTheme.ACCENT);
        int lineY = y + PAD;
        context.text(this.font, Component.literal(you.name()).withStyle(ChatFormatting.BOLD), left + PAD + 2, lineY, GuiTheme.ACCENT);
        lineY += LINE + 2;
        for (int i = 0; i < lines.size(); i++) {
            context.text(this.font, lines.get(i), left + PAD + 2, lineY, i == 0 ? GuiTheme.MUTED : GuiTheme.TEXT);
            lineY += LINE;
        }
        return y + height;
    }

    /** Every fight on the books this side is in: which way round, against whom, and what is at stake. */
    private int drawFights(GuiGraphicsExtractor context, Standings standings, FightWatch watch, long now,
                           int left, int y, int width) {
        Standings.Side you = match.side();
        context.text(this.font, Component.translatable("betterloka.calculator.fights").withStyle(ChatFormatting.BOLD),
                left, y, GuiTheme.TEXT);
        y += LINE + 3;
        List<ScheduledFight> mine = fightsOf(you);
        if (mine.isEmpty()) {
            Component none = watch.updatedAt() == 0
                    ? Component.translatable(watch.failed() ? "betterloka.fights.unreachable" : "betterloka.fights.loading")
                    : Component.translatable("betterloka.calculator.no_fights", you.name());
            return wrap(context, none, left + PAD, y, width - PAD, watch.failed() ? GuiTheme.BAD : GuiTheme.MUTED) + 2;
        }
        for (ScheduledFight fight : mine) {
            y = drawFight(context, standings, fight, now, left, y, width) + 4;
        }
        return y - 4;
    }

    private int drawFight(GuiGraphicsExtractor context, Standings standings, ScheduledFight fight, long now,
                          int left, int y, int width) {
        Standings.Side you = match.side();
        boolean attacking = isSide(you, fight.attackerAllianceId(), fight.attackerTownId());
        Standings.Forecast forecast = standings.forecast(fight);
        int inner = width - 2 * PAD - 2;
        int tx = left + PAD + 2;

        // Measured first, so the card is drawn behind its own text.
        List<FormattedCharSequence> body = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String where = FightsPanel.where(fight, null);
        if (forecast == null) {
            add(body, colors, Component.literal(where), inner, GuiTheme.MUTED);
            String reason = !fight.defended()
                    ? (StrengthMath.isBalak(fight.world()) ? "betterloka.calculator.neutral_balak" : "betterloka.calculator.neutral")
                    : !StrengthMath.countsForStrength(fight.world()) ? "betterloka.calculator.rivina"
                    : "betterloka.calculator.unknown_strength";
            add(body, colors, Component.translatable(reason), inner, GuiTheme.MUTED);
        } else {
            Standings.Side them = attacking ? forecast.defender() : forecast.attacker();
            double yours = attacking ? forecast.attackerStrength() : forecast.defenderStrength();
            double theirs = attacking ? forecast.defenderStrength() : forecast.attackerStrength();
            int win = attacking ? forecast.attackerWin() : forecast.defenderWin();
            int lose = attacking ? forecast.defenderWin() : forecast.attackerWin();
            add(body, colors, Component.translatable("betterloka.calculator.fight_facts", where, number(yours),
                    number(theirs), forecast.k()), inner, GuiTheme.MUTED);

            List<Standings.Row> won = them == null ? List.of() : standings.rankingAfter(fight.world(), you, them, win);
            List<Standings.Row> lost = them == null ? List.of() : standings.rankingAfter(fight.world(), them, you, lose);
            String continent = continentName(fight.world());
            add(body, colors, Component.translatable("betterloka.calculator.win", gain(win), number(yours + win),
                    places(won, you), continent), inner, GuiTheme.GOOD);
            add(body, colors, Component.translatable("betterloka.calculator.lose", loss(lose), number(yours - lose),
                    places(lost, you), continent), inner, GuiTheme.BAD);
            if (!won.isEmpty()) {
                add(body, colors, Component.translatable("betterloka.calculator.top_after_win", top(won)), inner, GuiTheme.MUTED);
                add(body, colors, Component.translatable("betterloka.calculator.top_after_loss", top(lost)), inner, GuiTheme.MUTED);
            }
            if (forecast.tooLittle()) {
                add(body, colors, Component.translatable("betterloka.calculator.too_little"), inner, GuiTheme.LIVE);
            }
        }

        int height = 2 * PAD + LINE + 2 + body.size() * LINE - 1;
        int color = attacking ? FightsPanel.ATTACKER : FightsPanel.DEFENDER;
        GuiTheme.panel(context, left, y, width, height);
        context.fill(left, y, left + 2, y + height, color);

        String opponent = attacking ? (fight.defended() ? FightsPanel.defenderName(fight)
                : Component.translatable("betterloka.fights.neutral").getString()) : FightsPanel.attackerName(fight);
        Component headline = Component.translatable(attacking ? "betterloka.calculator.you_attack"
                : "betterloka.calculator.you_defend", opponent).withStyle(ChatFormatting.BOLD);
        String status = FightsPanel.status(fight, now);
        int statusWidth = this.font.width(status);
        context.text(this.font, this.font.split(headline, Math.max(40, inner - statusWidth - 6)).get(0), tx, y + PAD, color);
        context.text(this.font, status, left + width - PAD - statusWidth, y + PAD, FightsPanel.statusColor(fight, now));
        int lineY = y + PAD + LINE + 2;
        for (int i = 0; i < body.size(); i++) {
            context.text(this.font, body.get(i), tx, lineY, colors.get(i));
            lineY += LINE;
        }
        return y + height;
    }

    private void add(List<FormattedCharSequence> lines, List<Integer> colors, Component text, int width, int color) {
        for (FormattedCharSequence line : this.font.split(text, Math.max(40, width))) {
            lines.add(line);
            colors.add(color);
        }
    }

    /** {@code #2 → #1}, or just {@code #2} when the fight leaves the side where it was. */
    private static String places(List<Standings.Row> after, Standings.Side side) {
        for (Standings.Row row : after) {
            if (row.side().id().equals(side.id())) {
                return row.before() == row.rank() ? "#" + row.rank() : "#" + row.before() + " → #" + row.rank();
            }
        }
        return "?";
    }

    /** {@code 1. Primate Pact 421 · 2. Golden Hooligans 393 · 3. Crownlands 176}. */
    private static String top(List<Standings.Row> ranking) {
        StringBuilder out = new StringBuilder();
        for (Standings.Row row : ranking.subList(0, Math.min(TOP_SHOWN, ranking.size()))) {
            if (!out.isEmpty()) {
                out.append("  ·  ");
            }
            out.append(row.rank()).append(". ").append(row.side().name()).append(' ').append(number(row.strength()));
        }
        return out.toString();
    }

    // ---- simulation ----

    /**
     * Who the simulation's next fight is against: the side picked in the table, else the other side
     * of a declared fight, else the side just above in the ranking (the one to overtake), else the
     * one just below.
     */
    private Standings.Side simOpponent(Standings standings) {
        String world = world();
        Standings.Side you = match.side();
        List<Standings.Row> ranking = standings.ranking(world, you);
        if (pickedId != null) {
            for (Standings.Row row : ranking) {
                if (row.side().id().equals(pickedId)) {
                    return row.side();
                }
            }
        }
        for (ScheduledFight fight : fightsOf(you)) {
            Standings.Forecast forecast = standings.forecast(fight);
            if (forecast != null && StrengthMath.isBalak(fight.world()) == balak) {
                Standings.Side them = isSide(you, fight.attackerAllianceId(), fight.attackerTownId())
                        ? forecast.defender() : forecast.attacker();
                if (them != null) {
                    return them;
                }
            }
        }
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).side().id().equals(you.id())) {
                if (i > 0) {
                    return ranking.get(i - 1).side();
                }
                return i + 1 < ranking.size() ? ranking.get(i + 1).side() : null;
            }
        }
        return null;
    }

    /** The simulation starts over when the side or the world being counted changes. */
    private void keepSimulationFor(String key) {
        if (!key.equals(simulatedFor)) {
            simulated.clear();
            simulatedFor = key;
            simResult = null;
        }
    }

    private void simulate(Standings.Side opponent, int count, boolean win) {
        for (int i = 0; i < count && simulated.size() < Simulation.MAX_FIGHTS; i++) {
            simulated.add(new Simulation.Fight(opponent.id(), win));
        }
        simResult = null;
    }

    private Simulation.Result simulation(Standings standings) {
        String key = simulatedFor + "|" + simulated.size() + "|" + simulated.hashCode();
        if (simResult == null || standings != simStandings || !key.equals(simKey)) {
            simResult = Simulation.run(standings, world(), match.side(), match.town(), List.copyOf(simulated));
            simStandings = standings;
            simKey = key;
        }
        return simResult;
    }

    /** Fights played out in a row: buttons to add them, each fight as it went, and where they leave everybody. */
    private int drawSimulation(GuiGraphicsExtractor context, Standings standings, int left, int y, int width,
                               int mouseX, int mouseY) {
        String world = world();
        keepSimulationFor(match.side().id() + "|" + world);
        context.text(this.font, Component.translatable("betterloka.calculator.sim").withStyle(ChatFormatting.BOLD),
                left, y, GuiTheme.TEXT);
        y += LINE + 3;

        Standings.Side opponent = simOpponent(standings);
        if (opponent == null) {
            return wrap(context, Component.translatable("betterloka.calculator.sim_nobody", continentName(world)),
                    left + PAD, y, width - PAD, GuiTheme.MUTED) + 2;
        }
        Simulation.Result result = simulation(standings);
        double yours = result.end();
        double theirs = opponent.strengthOn(world);
        for (Simulation.Step step : result.steps()) {
            if (step.opponent().id().equals(opponent.id())) {
                theirs = step.themAfter();
            }
        }
        int k = kAgainst(standings, opponent);
        StrengthMath.Stakes next = StrengthMath.stakes(yours, theirs, k);
        Component against = Component.translatable("betterloka.calculator.sim_opponent", opponent.name(),
                number(theirs), k);
        context.text(this.font, against, left + PAD, y, GuiTheme.TEXT);
        Component change = Component.translatable("betterloka.calculator.sim_change_hint");
        if (this.font.width(against) + this.font.width(change) + PAD + 16 < width) {
            context.text(this.font, change, left + width - this.font.width(change), y, 0xFF7A7A88);
        }
        y += LINE + 1;
        Component nextFight = next.tooLittle() ? Component.translatable("betterloka.calculator.sim_next_blocked")
                : Component.translatable("betterloka.calculator.sim_next", gain(next.ifFirstWins()), loss(next.ifSecondWins()));
        context.text(this.font, nextFight, left + PAD, y, next.tooLittle() ? GuiTheme.LIVE : GuiTheme.MUTED);
        y += LINE + 4;

        // The buttons.
        int x = left + PAD;
        boolean full = simulated.size() >= Simulation.MAX_FIGHTS;
        x = simChip(context, "betterloka.calculator.sim_win1", GuiTheme.GOOD, x, y, mouseX, mouseY, !full,
                () -> simulate(opponent, 1, true));
        x = simChip(context, "betterloka.calculator.sim_lose1", GuiTheme.BAD, x, y, mouseX, mouseY, !full,
                () -> simulate(opponent, 1, false));
        x = simChip(context, "betterloka.calculator.sim_win5", GuiTheme.GOOD, x, y, mouseX, mouseY, !full,
                () -> simulate(opponent, 5, true));
        x = simChip(context, "betterloka.calculator.sim_lose5", GuiTheme.BAD, x, y, mouseX, mouseY, !full,
                () -> simulate(opponent, 5, false));
        x = simChip(context, "betterloka.calculator.sim_undo", GuiTheme.ACCENT, x + 6, y, mouseX, mouseY,
                !simulated.isEmpty(), () -> {
                    simulated.remove(simulated.size() - 1);
                    simResult = null;
                });
        simChip(context, "betterloka.calculator.sim_clear", GuiTheme.ACCENT, x, y, mouseX, mouseY,
                !simulated.isEmpty(), () -> {
                    simulated.clear();
                    simResult = null;
                });
        y += 17;

        if (result.steps().isEmpty()) {
            return wrap(context, Component.translatable("betterloka.calculator.sim_empty"), left + PAD, y,
                    width - PAD, GuiTheme.MUTED) + 2;
        }

        // Each fight: columns from the right — the place, the other side's Strength, yours, the change.
        int rankRight = left + width - PAD;
        int themRight = rankRight - 62;
        int youRight = themRight - 66;
        int changeRight = youRight - 58;
        int textX = left + PAD + 16;
        int textWidth = Math.max(40, changeRight - 34 - textX);
        for (Simulation.Step step : result.steps()) {
            int color = !step.allowed() ? GuiTheme.LIVE : step.win() ? GuiTheme.GOOD : GuiTheme.BAD;
            context.text(this.font, step.number() + ".", left + PAD, y, GuiTheme.MUTED);
            if (!step.allowed()) {
                Component blocked = Component.translatable("betterloka.calculator.sim_step_blocked", step.opponent().name());
                context.text(this.font, this.font.plainSubstrByWidth(blocked.getString(), width - PAD - 16 - PAD), textX, y, color);
                y += LINE + 1;
                continue;
            }
            Component what = Component.translatable(step.win() ? "betterloka.calculator.sim_step_win"
                    : "betterloka.calculator.sim_step_loss", step.opponent().name());
            context.text(this.font, this.font.plainSubstrByWidth(what.getString(), textWidth), textX, y, color);
            String moved = step.win() ? gain(step.change()) : loss(step.change());
            rightText(context, Component.literal(moved), changeRight, y, color);
            rightText(context, Component.translatable("betterloka.calculator.sim_you", number(step.youAfter())), youRight, y, GuiTheme.TEXT);
            rightText(context, Component.translatable("betterloka.calculator.sim_them", number(step.themAfter())), themRight, y, GuiTheme.MUTED);
            String place = step.rankBefore() == step.rankAfter() ? "#" + step.rankAfter()
                    : "#" + step.rankBefore() + " → #" + step.rankAfter();
            rightText(context, Component.literal(place), rankRight, y,
                    step.rankAfter() < step.rankBefore() ? GuiTheme.GOOD : step.rankAfter() > step.rankBefore() ? GuiTheme.BAD : GuiTheme.MUTED);
            y += LINE + 1;
        }

        // Where they leave everybody.
        y += 3;
        GuiTheme.separator(context, left + PAD, y - 2, width - 2 * PAD);
        int net = result.net();
        Component summary = Component.translatable("betterloka.calculator.sim_summary", number(result.start()),
                number(result.end()), net == 0 ? "0" : net > 0 ? "+" + net : String.valueOf(net), result.startRank(),
                result.endRank(), continentName(world), result.counted());
        y = wrap(context, summary.copy().withStyle(ChatFormatting.BOLD), left + PAD, y + 2, width - PAD,
                net > 0 ? GuiTheme.GOOD : net < 0 ? GuiTheme.BAD : GuiTheme.TEXT);
        StringBuilder top = new StringBuilder();
        boolean youShown = false;
        for (Standings.Row row : result.ranking()) {
            boolean self = row.side().id().equals(match.side().id());
            if (row.rank() > 3 && !(self && !youShown)) {
                continue;
            }
            youShown |= self;
            if (!top.isEmpty()) {
                top.append("  ·  ");
            }
            String arrow = row.before() == row.rank() ? "" : row.before() > row.rank() ? " ↑" : " ↓";
            top.append(row.rank()).append(". ").append(row.side().name()).append(' ').append(number(row.strength())).append(arrow);
        }
        y = wrap(context, Component.translatable("betterloka.calculator.sim_ranking", top.toString()), left + PAD, y,
                width - PAD, GuiTheme.MUTED);
        if (simulated.size() >= Simulation.MAX_FIGHTS) {
            y = wrap(context, Component.translatable("betterloka.calculator.sim_full", Simulation.MAX_FIGHTS), left + PAD,
                    y, width - PAD, GuiTheme.LIVE);
        }
        return y;
    }

    /** A clickable chip; returns where the next one goes. */
    private int simChip(GuiGraphicsExtractor context, String key, int color, int x, int y, int mouseX, int mouseY,
                        boolean active, Runnable action) {
        Component label = Component.translatable(key);
        int chipWidth = this.font.width(label) + 10;
        boolean hover = active && mouseX >= x && mouseX < x + chipWidth && mouseY >= y && mouseY < y + 13
                && mouseY >= scroll.viewportTop() && mouseY < scroll.viewportBottom();
        int shown = !active ? 0xFF4A4A5A : hover ? color : (color & 0x00FFFFFF) | 0xA0000000;
        GuiTheme.chip(context, x, y, chipWidth, 13, shown);
        context.text(this.font, label, x + 5, y + 3, !active ? 0xFF6A6A78 : hover ? color : GuiTheme.TEXT);
        if (active) {
            hits.add(new Hit(x, y, chipWidth, 13, action));
        }
        return x + chipWidth + 4;
    }

    /** The continent's ranking, with what a win or a loss against each side would be worth. */
    private int drawTable(GuiGraphicsExtractor context, Standings standings, int left, int y, int width,
                          int mouseX, int mouseY) {
        Standings.Side you = match.side();
        String world = world();
        context.text(this.font, Component.translatable("betterloka.calculator.table", continentName(world))
                .withStyle(ChatFormatting.BOLD), left, y, GuiTheme.TEXT);
        Component hint = Component.translatable("betterloka.calculator.table_hint");
        if (this.font.width(hint) + this.font.width(Component.translatable("betterloka.calculator.table",
                continentName(world)).withStyle(ChatFormatting.BOLD)) + 12 < width) {
            context.text(this.font, hint, left + width - this.font.width(hint), y, 0xFF7A7A88);
        }
        y += LINE + 3;

        // Columns, from the right: the note, what a loss costs, what a win gains, the Strength.
        // Each as wide as its heading needs in this language, and never narrower than its numbers.
        Component strengthHeading = Component.translatable("betterloka.town_finder.strength");
        Component winHeading = Component.translatable("betterloka.calculator.col_win");
        Component loseHeading = Component.translatable("betterloka.calculator.col_lose");
        int noteWidth = Math.max(90, Math.min(150, width / 3));
        int noteX = left + width - noteWidth;
        int loseRight = noteX - 10;
        int winRight = loseRight - Math.max(36, this.font.width(loseHeading) + 8);
        int strengthRight = winRight - Math.max(36, this.font.width(winHeading) + 8);
        int nameX = left + PAD + 20;
        int nameWidth = Math.max(40, strengthRight - Math.max(36, this.font.width(strengthHeading) + 8) - nameX);

        rightText(context, strengthHeading, strengthRight, y, GuiTheme.MUTED);
        rightText(context, winHeading, winRight, y, GuiTheme.MUTED);
        rightText(context, loseHeading, loseRight, y, GuiTheme.MUTED);
        context.text(this.font, "#", left + PAD, y, GuiTheme.MUTED);
        y += LINE + 1;
        GuiTheme.separator(context, left, y, width);
        y += 3;

        double yours = you.strengthOn(world);
        for (Standings.Row row : standings.ranking(world, you)) {
            boolean self = row.side().id().equals(you.id());
            boolean picked = row.side().id().equals(pickedId);
            boolean hover = !self && mouseX >= left && mouseX < left + width && mouseY >= y - 1 && mouseY < y + LINE + 1
                    && mouseY >= scroll.viewportTop() && mouseY < scroll.viewportBottom();
            if (self) {
                context.fill(left, y - 1, left + width, y + LINE, 0x304FC3F7);
                context.fill(left, y - 1, left + 2, y + LINE, GuiTheme.ACCENT);
            } else if (picked || hover) {
                context.fill(left, y - 1, left + width, y + LINE, picked ? 0x40FFFFFF : 0x20FFFFFF);
            }
            context.text(this.font, String.valueOf(row.rank()), left + PAD, y, self ? GuiTheme.ACCENT : GuiTheme.MUTED);
            context.text(this.font, this.font.plainSubstrByWidth(row.side().name(), nameWidth), nameX, y,
                    self ? GuiTheme.ACCENT : GuiTheme.TEXT);
            rightText(context, Component.literal(number(row.strength())), strengthRight, y, GuiTheme.TEXT);
            if (self) {
                context.text(this.font, Component.translatable("betterloka.calculator.you"), noteX, y, GuiTheme.ACCENT);
            } else {
                StrengthMath.Stakes stakes = StrengthMath.stakes(yours, row.strength(), kAgainst(standings, row.side()));
                rightText(context, Component.literal(gain(stakes.ifFirstWins())), winRight, y, GuiTheme.GOOD);
                rightText(context, Component.literal(loss(stakes.ifSecondWins())), loseRight, y, GuiTheme.BAD);
                Component note = null;
                int noteColor = GuiTheme.MUTED;
                if (stakes.tooLittle()) {
                    note = Component.translatable("betterloka.calculator.not_allowed");
                    noteColor = GuiTheme.LIVE;
                } else if (!balak && row.rank() <= 3) {
                    note = Component.translatable("betterloka.calculator.players_needed", 5 - row.rank());
                } else if (stakes.k() != StrengthMath.MAX_K && !balak) {
                    note = Component.literal("k " + stakes.k());
                }
                if (note != null) {
                    context.text(this.font, this.font.plainSubstrByWidth(note.getString(), noteWidth), noteX, y, noteColor);
                }
                Standings.Side side = row.side();
                hits.add(new Hit(left, y - 1, width, LINE + 1, () -> {
                    pickedId = side.id();
                    filledFor = null;
                }));
            }
            y += LINE + 1;
        }
        return y;
    }

    private void rightText(GuiGraphicsExtractor context, Component text, int right, int y, int color) {
        context.text(this.font, text, right - this.font.width(text), y, color);
    }

    /** The three boxes' labels, and what they come to. */
    private void drawManual(GuiGraphicsExtractor context, int left, int width) {
        int y = manualY();
        GuiTheme.separator(context, left, y - LINE - 2 - GAP, width);
        int x = left;
        for (int i = 0; i < (balak ? 2 : BOX_LABELS.length); i++) {
            context.text(this.font, Component.translatable(BOX_LABELS[i]), x, y - LINE - 1, GuiTheme.MUTED);
            x += column(i);
        }
        int resultX = x + 4;
        Double you = parse(manualYou);
        Double them = parse(manualThem);
        Double level = parse(manualLevel);
        if (you == null || them == null) {
            context.text(this.font, Component.translatable("betterloka.calculator.manual_hint"), resultX, y + 5, GuiTheme.MUTED);
            return;
        }
        int k = balak ? StrengthMath.BALAK_K : StrengthMath.kFactor(level == null ? StrengthMath.MAX_TOWN_LEVEL : level);
        StrengthMath.Stakes stakes = StrengthMath.stakes(you, them, k);
        Component win = Component.translatable("betterloka.calculator.manual_win", gain(stakes.ifFirstWins()));
        Component lose = Component.translatable("betterloka.calculator.manual_lose", loss(stakes.ifSecondWins()));
        context.text(this.font, win, resultX, y - 1, GuiTheme.GOOD);
        context.text(this.font, lose, resultX + this.font.width(win) + 10, y - 1, GuiTheme.BAD);
        Component under = stakes.tooLittle() ? Component.translatable("betterloka.calculator.not_allowed")
                : Component.translatable(balak ? "betterloka.calculator.manual_k_balak" : "betterloka.calculator.manual_k", k);
        context.text(this.font, under, resultX, y + LINE, stakes.tooLittle() ? GuiTheme.LIVE : GuiTheme.MUTED);
    }

    private int wrap(GuiGraphicsExtractor context, Component text, int x, int y, int width, int color) {
        for (FormattedCharSequence line : this.font.split(text, Math.max(40, width))) {
            context.text(this.font, line, x, y, color);
            y += LINE;
        }
        return y;
    }

    // ---- for the dev diagnostics run ----

    public static void devQuery(String town) {
        query = town;
        if (devInstance != null && devInstance.townField != null) {
            devInstance.townField.setValue(town);
        }
    }

    public static void devBalak(boolean on) {
        if (balak != on && devInstance != null) {
            balak = on;
            devInstance.filledFor = null;
            devInstance.pickedId = null;
            devInstance.scroll.reset();
            devInstance.rebuildWidgets();
        }
    }

    /** Picks the {@code index}th row of the table that is not the player's own. */
    public static void devPick(int index) {
        if (devInstance == null || devInstance.match == null) {
            return;
        }
        int seenRows = 0;
        for (Standings.Row row : BetterLokaClient.standings().standings().ranking(devInstance.world(), devInstance.match.side())) {
            if (!row.side().id().equals(devInstance.match.side().id()) && seenRows++ == index) {
                devInstance.pickedId = row.side().id();
                devInstance.filledFor = null;
            }
        }
    }

    /** Adds fights to the simulation against whoever it would fight next; 0 clears it. */
    public static void devSimulate(int count, boolean win) {
        if (devInstance == null || devInstance.match == null) {
            return;
        }
        if (count == 0) {
            simulated.clear();
            devInstance.simResult = null;
            return;
        }
        Standings.Side opponent = devInstance.simOpponent(BetterLokaClient.standings().standings());
        if (opponent != null) {
            devInstance.simulate(opponent, count, win);
        }
    }

    public static void devScroll(double amount) {
        if (devInstance != null) {
            devInstance.scroll.mouseScrolled(devInstance.left() + 10, devInstance.listTop() + 10, amount);
        }
    }

    public static String devState() {
        if (devInstance == null) {
            return "closed";
        }
        Standings.Match match = devInstance.match;
        return "query=" + query + " match=" + (match == null ? null : match.side().name() + " " + match.side().strength()
                + "/" + match.side().balakStrength()) + " fights=" + (match == null ? 0 : devInstance.fightsOf(match.side()).size())
                + " boxes=" + devInstance.manualYou + "," + devInstance.manualThem + "," + devInstance.manualLevel
                + " simulated=" + simulated.size() + (devInstance.simResult == null ? ""
                : " sim=" + devInstance.simResult.start() + "->" + devInstance.simResult.end() + " #"
                + devInstance.simResult.startRank() + "->#" + devInstance.simResult.endRank());
    }
}
