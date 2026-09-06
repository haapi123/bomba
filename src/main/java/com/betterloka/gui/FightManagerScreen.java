package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.ScheduledFight;
import com.betterloka.stats.PlayerTrait;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Every battle Loka has on its books: who declared on whom, how many are signed up, whether
 * reinforcements can be called, and when it goes off.
 *
 * <p>Loka publishes no start time for a declared fight — the field stays zero until it actually
 * begins — so the time comes from the rule that decides it: a town is attackable during its
 * vulnerability window, eight hours from the hour on its record.
 */
public class FightManagerScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 420;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    private static final int CONTROL_ROW_Y = 26;
    private static final int CONTROL_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = CONTROL_ROW_Y + CONTROL_ROW_HEIGHT + 16;

    /** A Loka vulnerability window runs eight hours from the hour the API publishes. */
    private static final int VULN_HOURS = 8;

    private static final int CHIP_HEIGHT = 12;
    private static final int CHIP_PADDING = 4;

    /** One battle with the names and windows its ids point at. */
    private record Row(ScheduledFight fight, String attacker, String defender,
                       String attackerAlliance, String defenderAlliance, int defenderVulnHour) {
    }

    /**
     * Development only: list battles already fought rather than ones declared.
     *
     * <p>Loka often has nothing declared at all, and a screenshot of an empty screen shows nothing
     * about how a battle is drawn. These are real records either way.
     */
    public static boolean devShowRecentBattles;

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private List<Row> rows = List.of();
    private Text message;
    private boolean loading;
    private long loadedAt;
    private int generation;

    public FightManagerScreen(Screen parent) {
        super(Text.translatable("betterloka.module.fight_manager"));
        this.parent = parent;
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

        addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.fights.refresh"), button -> load())
                .dimensions(left, CONTROL_ROW_Y, width, CONTROL_ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 26, 200, 20).build());

        load();
    }

    private void load() {
        if (loading) {
            return;
        }
        loading = true;
        message = null;
        int mine = ++generation;

        CompletableFuture.supplyAsync(this::fetch, BetterLokaClient.lokaExecutor())
                .whenComplete((found, error) -> onClientThread(mine, () -> {
                    loading = false;
                    loadedAt = System.currentTimeMillis();
                    if (error != null) {
                        message = Text.translatable("betterloka.fights.unreachable").formatted(Formatting.RED);
                        return;
                    }
                    rows = found;
                }));
    }

    /**
     * The battles, with every id resolved to a name.
     *
     * <p>Towns come from the shared cache and alliances from the Town Logger's standing read, so a
     * refresh is one request however many battles come back.
     */
    private List<Row> fetch() {
        List<ScheduledFight> fights;
        try {
            fights = devShowRecentBattles
                    ? BetterLokaClient.lokaApi().fetchRecentBattles()
                    : BetterLokaClient.lokaApi().fetchScheduledFights();
        } catch (ApiException e) {
            throw new java.util.concurrent.CompletionException(e);
        }

        List<Row> built = new ArrayList<>();
        for (ScheduledFight fight : fights) {
            LokaTown attacker = BetterLokaClient.towns().byId(fight.attackerTownId());
            LokaTown defender = BetterLokaClient.towns().byId(fight.defenderTownId());
            built.add(new Row(fight,
                    sideName(fight.attackerName(), allianceName(fight.attackerAllianceId()),
                            attacker),
                    sideName(fight.defenderName(), allianceName(fight.defenderAllianceId()),
                            defender),
                    allianceName(fight.attackerAllianceId()),
                    allianceName(fight.defenderAllianceId()),
                    defender == null ? -1 : defender.vulnerabilityWindow()));
        }
        // Under way first, then the biggest turnouts: what is happening now beats what might.
        built.sort(Comparator.comparing((Row row) -> !row.fight().started())
                .thenComparing(row -> -row.fight().total()));
        return List.copyOf(built);
    }

    /**
     * What to call one side of a fight.
     *
     * <p>Loka names both sides on the battle record itself — "Justice League", "Helian League" —
     * which is better than any id lookup and is what a person reads first. The alliance and the
     * town are fallbacks for the rare record that names nobody.
     */
    private static String sideName(String published, String alliance, LokaTown town) {
        if (published != null && !published.isBlank()) {
            return published;
        }
        if (alliance != null && !alliance.isBlank()) {
            return alliance;
        }
        return town == null ? null : town.name();
    }

    private static String allianceName(String allianceId) {
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

    private void onClientThread(int mine, Runnable action) {
        if (this.client == null) {
            return;
        }
        this.client.execute(() -> {
            if (mine == generation && this.client.currentScreen == this) {
                action.run();
            }
        });
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        return scrollPanel.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        Text status = loading
                ? Text.translatable("betterloka.fights.loading")
                : Text.translatable("betterloka.fights.count", rows.size(), TimeFormat.ago(loadedAt));
        context.drawTextWithShadow(this.textRenderer, status, left, VIEWPORT_TOP - 12, GuiTheme.MUTED);

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used;

        if (message != null) {
            context.drawTextWithShadow(this.textRenderer, message, left, y + 4, GuiTheme.BAD);
            used = 20;
        } else if (rows.isEmpty()) {
            GuiTheme.panel(context, left, y, cardWidth, CARD_PADDING * 2 + ROW_HEIGHT);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(loading ? "betterloka.fights.loading" : "betterloka.fights.none"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            used = CARD_PADDING * 2 + ROW_HEIGHT + CARD_GAP;
        } else {
            used = renderFights(context, left, y, cardWidth) - y;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /**
     * One side: its name, then how many came against how many signed up.
     *
     * <p>Drawn in pieces because the two numbers carry different meanings and so different colours,
     * while the name and the slash between them stay ordinary text.
     *
     * @return the x the next thing may start at
     */
    private int drawSide(DrawContext context, int x, int y, Text name,
                         com.betterloka.api.model.ScheduledFight.Side side) {
        context.drawTextWithShadow(this.textRenderer, name, x, y, GuiTheme.TEXT);
        x += this.textRenderer.getWidth(name) + 4;

        String present = String.valueOf(side.present());
        context.drawTextWithShadow(this.textRenderer, present, x, y, GuiTheme.TURNOUT_PRESENT);
        x += this.textRenderer.getWidth(present);

        context.drawTextWithShadow(this.textRenderer, "/", x, y, GuiTheme.MUTED);
        x += this.textRenderer.getWidth("/");

        String signedUp = String.valueOf(side.signedUp());
        context.drawTextWithShadow(this.textRenderer, signedUp, x, y, GuiTheme.TURNOUT_SIGNED_UP);
        return x + this.textRenderer.getWidth(signedUp);
    }

    private int renderFights(DrawContext context, int left, int y, int width) {
        int inner = width - CARD_PADDING * 2;
        for (Row row : rows) {
            ScheduledFight fight = row.fight();
            int height = CARD_PADDING * 2 + ROW_HEIGHT * 3;
            GuiTheme.panel(context, left, y, width, height);
            if (fight.started()) {
                context.fill(left, y, left + 2, y + height, GuiTheme.LIVE);
            }

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            // Who is fighting whom, which is the first thing anybody wants off this screen.
            // Attacker red, defender green — which way round a fight is going is the first thing
            // anybody reads off this screen, so it is carried by colour rather than word order.
            // Neither side named: fall back to the fight's own identifier, made readable, rather
            // than showing "the_verdant_hallows" or a bare question mark.
            String fallback = fight.readableName();
            Text attacker = Text.literal(row.attacker() != null ? row.attacker()
                    : fallback != null ? fallback : "?").formatted(Formatting.BOLD);
            Text defender = Text.literal(row.defender() == null ? "?" : row.defender())
                    .formatted(Formatting.BOLD);
            String arrow = "  vs  ";

            int attackerX = textX;
            int afterAttacker = drawSide(context, attackerX, textY, attacker, fight.attackers());
            int arrowX = afterAttacker;
            context.drawTextWithShadow(this.textRenderer, arrow, arrowX, textY, GuiTheme.MUTED);
            int defenderX = arrowX + this.textRenderer.getWidth(arrow);
            drawSide(context, defenderX, textY, defender, fight.defenders());

            Text when = fight.started()
                    ? Text.translatable("betterloka.fights.live")
                    : Text.literal(windowText(row.defenderVulnHour()));
            context.drawTextWithShadow(this.textRenderer, when,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(when), textY,
                    fight.started() ? GuiTheme.LIVE : GuiTheme.ACCENT);

            // Each town's alliance sits directly under its name, so the two never get mixed up.
            if (row.attackerAlliance() != null) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(row.attackerAlliance(), defenderX - attackerX - 4),
                        attackerX, textY + ROW_HEIGHT, GuiTheme.MUTED);
            }
            if (row.defenderAlliance() != null) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(row.defenderAlliance(), inner / 3),
                        defenderX, textY + ROW_HEIGHT, GuiTheme.MUTED);
            }

            // Where it is being fought. Loka leaves the world off some records, and "? - territory
            // #117" says nothing; the fight's own name, made readable, always says something.
            String where = fight.continent() != null
                    ? Text.translatable("betterloka.fights.where", fight.continent(),
                            fight.territoryNumber() == null ? "?" : fight.territoryNumber())
                            .getString()
                    : fight.readableName();
            if (where != null) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(where, inner - 90), textX,
                        textY + ROW_HEIGHT * 2, GuiTheme.MUTED);
            }

            // Whether reinforcements can be called decides whether a fight stays the size it looks,
            // so it gets a chip rather than a line of grey text.
            Text reins = Text.translatable(fight.reinforcementsAllowed()
                    ? "betterloka.fights.reins_on" : "betterloka.fights.reins_off");
            int reinsColor = fight.reinforcementsAllowed() ? GuiTheme.traitColor(PlayerTrait.Level.GOOD)
                    : GuiTheme.traitColor(PlayerTrait.Level.POOR);
            int chipWidth = this.textRenderer.getWidth(reins) + CHIP_PADDING * 2;
            int chipX = left + width - CARD_PADDING - chipWidth;
            GuiTheme.chip(context, chipX, textY + ROW_HEIGHT * 2 - 2, chipWidth, CHIP_HEIGHT, reinsColor);
            context.drawTextWithShadow(this.textRenderer, reins, chipX + CHIP_PADDING,
                    textY + ROW_HEIGHT * 2 + 1, reinsColor);

            y += height + CARD_GAP;
        }
        return y;
    }

    /**
     * When a declared fight can go off: the defending town's window.
     *
     * <p>Not a countdown, because Loka publishes the hour without a time zone — a made-up "starts in
     * 3h" would be wrong for most of the people reading it, where the window itself is exactly right.
     */
    private static String windowText(int hour) {
        if (hour < 0) {
            return Text.translatable("betterloka.fights.window_unknown").getString();
        }
        return String.format(Locale.ROOT, "%02d:00 – %02d:00", hour, (hour + VULN_HOURS) % 24);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
