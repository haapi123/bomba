package com.betterloka.gui;

import com.betterloka.BetterLoka;
import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import com.betterloka.towns.TownLogStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Towns: who is recruiting right now, and everything about one in particular.
 *
 * <p>Opens on the towns looking for members, split by continent, because that is the question
 * somebody browsing towns actually has. Searching or clicking a row switches to the detail view.
 *
 * <p>The territories a town holds come from the Town Logger's standing sweep, so they cost nothing
 * extra here.
 */
public class TownFinderScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 400;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    private static final int SEARCH_ROW_Y = 26;
    private static final int SEARCH_ROW_HEIGHT = 18;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int FILTER_ROW_Y = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 4;
    private static final int FILTER_ROW_HEIGHT = 16;
    private static final int VIEWPORT_TOP = FILTER_ROW_Y + FILTER_ROW_HEIGHT + 6;

    /** How many past allies to name. Beyond a handful it stops being a summary. */
    private static final int MAX_PARTNERS = 5;

    /** A Loka vulnerability window runs eight hours from the hour the API publishes. */
    private static final int VULN_HOURS = 8;

    /** {@code null} means every continent. */
    private static final String[] CONTINENTS = {null, "north", "west", "south"};

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private TextFieldWidget queryField;
    private ButtonWidget searchButton;

    private LokaTown town;
    private List<Territory> held = List.of();
    private LokaAlliance alliance;
    private List<TownLogStore.TownPartner> partners = List.of();

    private List<LokaTown> recruiting = List.of();
    private int continent;
    private boolean recruitingLoaded;

    private Text message;
    /** The lookup for one town. Kept apart from the roster load so a slow roster cannot block it. */
    private boolean loading;
    private boolean loadingRecruiting;
    private int generation;

    /** Identity IDs resolve to names in the background; render draws whatever has landed. */
    private final Map<String, String> names = new ConcurrentHashMap<>();

    /** Where each browse row was drawn this frame, so a click can find which town it hit. */
    private final List<int[]> rowBounds = new ArrayList<>();
    private final List<LokaTown> rowTowns = new ArrayList<>();

    public TownFinderScreen(Screen parent) {
        super(Text.translatable("betterloka.module.town_finder"));
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

    private boolean browsing() {
        return town == null && message == null;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();

        scrollPanel.setViewport(left, VIEWPORT_TOP, width, Math.max(20, viewportBottom() - VIEWPORT_TOP));

        String previous = queryField != null ? queryField.getText() : "";
        queryField = new TextFieldWidget(this.textRenderer, left, SEARCH_ROW_Y,
                width - SEARCH_BUTTON_WIDTH - 4, SEARCH_ROW_HEIGHT,
                Text.translatable("betterloka.town_finder.field"));
        queryField.setMaxLength(32);
        queryField.setPlaceholder(Text.translatable("betterloka.town_finder.placeholder")
                .formatted(Formatting.DARK_GRAY));
        queryField.setText(previous);
        addDrawableChild(queryField);

        searchButton = ButtonWidget.builder(Text.translatable("betterloka.finder.search"), button -> search())
                .dimensions(left + width - SEARCH_BUTTON_WIDTH, SEARCH_ROW_Y, SEARCH_BUTTON_WIDTH, SEARCH_ROW_HEIGHT)
                .build();
        addDrawableChild(searchButton);

        if (town == null) {
            int filterWidth = (width - 12) / 4;
            int x = left;
            for (int i = 0; i < CONTINENTS.length; i++) {
                int target = i;
                int thisWidth = i == CONTINENTS.length - 1 ? left + width - x : filterWidth;
                addDrawableChild(ButtonWidget.builder(continentLabel(i), button -> {
                            continent = target;
                            scrollPanel.reset();
                            clearAndInit();
                        })
                        .dimensions(x, FILTER_ROW_Y, thisWidth, FILTER_ROW_HEIGHT).build());
                x += filterWidth + 4;
            }
        } else {
            addDrawableChild(ButtonWidget.builder(Text.translatable("betterloka.town_finder.back_to_list"),
                            button -> showList())
                    .dimensions(left, FILTER_ROW_Y, width, FILTER_ROW_HEIGHT).build());
        }

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 26, 200, 20).build());

        if (town == null) {
            setInitialFocus(queryField);
            loadRecruiting();
        }
    }

    private Text continentLabel(int index) {
        String world = CONTINENTS[index];
        Text label = world == null
                ? Text.translatable("betterloka.town_finder.all")
                : Text.literal(Territory.continentOf(world));
        return index == continent ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    private void showList() {
        town = null;
        held = List.of();
        alliance = null;
        partners = List.of();
        message = null;
        scrollPanel.reset();
        clearAndInit();
    }

    /**
     * The recruiting roster.
     *
     * <p>Loka's town list is the whole roster with every member on it, so this is a megabyte — hence
     * once per screen, off the shared cache the rest of the mod already fills.
     */
    private void loadRecruiting() {
        if (recruitingLoaded || loadingRecruiting) {
            return;
        }
        loadingRecruiting = true;
        // Deliberately not guarded by the search generation: the two are independent, and sharing it
        // would mean a search thrown in while this is running silently discards the roster.
        CompletableFuture
                .supplyAsync(() -> BetterLokaClient.towns().all(), BetterLokaClient.lokaExecutor())
                .whenComplete((all, error) -> onScreen(() -> {
                    loadingRecruiting = false;
                    recruitingLoaded = true;
                    if (error != null || all == null) {
                        message = Text.translatable("betterloka.town_finder.unreachable").formatted(Formatting.RED);
                        return;
                    }
                    List<LokaTown> open = new ArrayList<>();
                    for (LokaTown candidate : all) {
                        if (candidate.recruiting()) {
                            open.add(candidate);
                        }
                    }
                    open.sort(Comparator.comparingDouble(LokaTown::townLevel).reversed()
                            .thenComparing(LokaTown::name, String.CASE_INSENSITIVE_ORDER));
                    recruiting = List.copyOf(open);
                }));
    }

    private void search() {
        String query = queryField.getText().trim();
        if (query.isEmpty() || loading) {
            return;
        }
        loading = true;
        message = null;
        int mine = ++generation;

        CompletableFuture
                .supplyAsync(() -> BetterLokaClient.towns().byName(query), BetterLokaClient.lokaExecutor())
                .whenComplete((found, error) -> onClientThread(mine, () -> {
                    loading = false;
                    if (error != null) {
                        message = Text.translatable("betterloka.town_finder.unreachable").formatted(Formatting.RED);
                        return;
                    }
                    if (found != null) {
                        show(found);
                        return;
                    }
                    // A name Loka has no living town for is usually a town that was deleted, which the
                    // logger already knows the names of — worth saying so rather than "no such town".
                    LokaTown dead = BetterLokaClient.townLogger().deletedByName(query);
                    if (dead != null) {
                        show(dead);
                    } else {
                        message = Text.translatable("betterloka.town_finder.not_found", query)
                                .formatted(Formatting.RED);
                    }
                }));
    }

    private void show(LokaTown found) {
        town = found;
        message = null;
        held = BetterLokaClient.townLogger().heldBy(found.id());
        alliance = BetterLokaClient.townLogger().allianceOf(found.id());
        partners = BetterLokaClient.townLogger().partnersOf(found.id());
        scrollPanel.reset();
        resolveNames(found);
        clearAndInit();
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

    private void onClientThread(int mine, Runnable action) {
        onScreen(() -> {
            if (mine == generation) {
                action.run();
            }
        });
    }

    private void onScreen(Runnable action) {
        if (this.client == null) {
            return;
        }
        this.client.execute(() -> {
            if (this.client.currentScreen == this) {
                action.run();
            }
        });
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && queryField.isFocused()) {
            search();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (scrollPanel.mouseClicked(click.x(), click.y())) {
            return true;
        }
        if (browsing() && click.y() >= VIEWPORT_TOP && click.y() < viewportBottom()) {
            for (int i = 0; i < rowBounds.size(); i++) {
                int[] bounds = rowBounds.get(i);
                if (click.x() >= bounds[0] && click.x() < bounds[0] + bounds[2]
                        && click.y() >= bounds[1] && click.y() < bounds[1] + bounds[3]) {
                    show(rowTowns.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
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
        searchButton.active = !loading && !queryField.getText().trim().isEmpty();
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used;

        if (loading || (town == null && loadingRecruiting)) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(loading ? "betterloka.finder.searching"
                            : "betterloka.town_finder.loading_towns"),
                    left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else if (message != null) {
            context.drawTextWithShadow(this.textRenderer, message, left, y + 4, GuiTheme.BAD);
            used = 20;
        } else if (town == null) {
            used = renderRecruiting(context, left, y, cardWidth, mouseX, mouseY) - y;
        } else {
            used = renderTown(context, left, y, cardWidth) - y;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);
    }

    /** @return the y just past the last row. */
    private int renderRecruiting(DrawContext context, int left, int y, int width, int mouseX, int mouseY) {
        rowBounds.clear();
        rowTowns.clear();

        List<LokaTown> shown = new ArrayList<>();
        for (LokaTown candidate : recruiting) {
            if (CONTINENTS[continent] == null || CONTINENTS[continent].equals(candidate.world())) {
                shown.add(candidate);
            }
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.town_finder.recruiting_count", shown.size()), left, y + 2,
                GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        if (shown.isEmpty()) {
            GuiTheme.panel(context, left, y, width, CARD_PADDING * 2 + ROW_HEIGHT);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.town_finder.none_recruiting"), left + CARD_PADDING,
                    y + CARD_PADDING, GuiTheme.MUTED);
            return y + CARD_PADDING * 2 + ROW_HEIGHT + CARD_GAP;
        }

        int inner = width - CARD_PADDING * 2;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        for (LokaTown candidate : shown) {
            boolean hovered = mouseX >= left && mouseX < left + width && mouseY >= y && mouseY < y + height
                    && mouseY >= VIEWPORT_TOP && mouseY < viewportBottom();
            GuiTheme.panel(context, left, y, width, height);
            if (hovered) {
                context.fill(left, y, left + 2, y + height, GuiTheme.ACCENT);
            }

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;
            context.drawTextWithShadow(this.textRenderer, candidate.name(), textX, textY,
                    hovered ? GuiTheme.ACCENT : GuiTheme.TEXT);

            String level = Text.translatable("betterloka.town_finder.level_short",
                    (int) candidate.townLevel(), candidate.memberCount()).getString();
            context.drawTextWithShadow(this.textRenderer, level,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(level), textY, GuiTheme.MUTED);

            StringBuilder second = new StringBuilder();
            if (candidate.continentName() != null) {
                second.append(candidate.continentName());
            }
            if (candidate.vulnerabilityWindow() >= 0) {
                second.append("  ·  ").append(Text.translatable("betterloka.town_finder.vuln_short",
                        vulnText(candidate)).getString());
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(second.toString(), inner - 100), textX, textY + ROW_HEIGHT,
                    GuiTheme.MUTED);

            if (candidate.slogan() != null && !candidate.slogan().isBlank()) {
                String slogan = this.textRenderer.trimToWidth(candidate.slogan(), inner / 2);
                context.drawTextWithShadow(this.textRenderer, slogan,
                        left + width - CARD_PADDING - this.textRenderer.getWidth(slogan), textY + ROW_HEIGHT,
                        GuiTheme.MUTED);
            }

            rowBounds.add(new int[]{left, y, width, height});
            rowTowns.add(candidate);
            y += height + CARD_GAP;
        }
        return y;
    }

    private int renderTown(DrawContext context, int left, int y, int width) {
        int inner = width - CARD_PADDING * 2;

        int headHeight = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, headHeight);
        Text name = Text.literal(town.name()).formatted(Formatting.BOLD);
        context.drawTextWithShadow(this.textRenderer, name, left + CARD_PADDING, y + CARD_PADDING,
                town.deleted() ? GuiTheme.BAD : GuiTheme.ACCENT);
        String continentName = town.continentName() == null ? "?" : town.continentName();
        context.drawTextWithShadow(this.textRenderer, continentName,
                left + width - CARD_PADDING - this.textRenderer.getWidth(continentName), y + CARD_PADDING,
                GuiTheme.MUTED);
        String second = town.deleted()
                ? Text.translatable("betterloka.town_finder.deleted").getString()
                : town.slogan();
        if (second != null && !second.isBlank()) {
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(second, inner), left + CARD_PADDING,
                    y + CARD_PADDING + ROW_HEIGHT, town.deleted() ? GuiTheme.BAD : GuiTheme.MUTED);
        }
        y += headHeight + CARD_GAP;

        // Level, strength and roster do not survive deletion, so a dead town skips straight to the
        // territories it is still recorded against — which is exactly what makes it worth looking up.
        if (town.deleted()) {
            return renderTerritories(context, left, y, width, inner);
        }

        int statsHeight = CARD_PADDING * 2 + ROW_HEIGHT * 5;
        GuiTheme.panel(context, left, y, width, statsHeight);
        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                Text.translatable("betterloka.town_finder.level").getString(),
                String.format(Locale.ROOT, "%.0f", town.townLevel()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                Text.translatable("betterloka.town_finder.strength").getString(),
                String.format(Locale.ROOT, "%.0f", town.strength()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 2, inner,
                Text.translatable("betterloka.town_finder.members").getString(),
                String.valueOf(town.memberCount()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 3, inner,
                Text.translatable("betterloka.town_finder.recruiting").getString(),
                Text.translatable(town.recruiting() ? "betterloka.yes" : "betterloka.no").getString(),
                town.recruiting() ? GuiTheme.GOOD : GuiTheme.MUTED);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 4, inner,
                Text.translatable("betterloka.town_finder.vuln").getString(),
                vulnText(town), GuiTheme.LIVE);
        y += statsHeight + CARD_GAP;

        y = renderPeople(context, left, y, width, inner);
        y = renderAlliance(context, left, y, width, inner);
        return renderTerritories(context, left, y, width, inner);
    }

    /**
     * The vulnerability window, start to finish.
     *
     * <p>Loka publishes only the hour it opens; the eight-hour length is the server's rule, so the
     * end is worked out from it and wraps past midnight.
     */
    private static String vulnText(LokaTown of) {
        int hour = of.vulnerabilityWindow();
        if (hour < 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%02d:00 – %02d:00", hour, (hour + VULN_HOURS) % 24);
    }

    /**
     * Leader and sub-owners.
     *
     * <p>Every sub-owner gets a line of its own and the card grows to fit: a town with five of them
     * used to lose the last name to a trim, which is the one thing a list of officers must not do.
     */
    private int renderPeople(DrawContext context, int left, int y, int width, int inner) {
        List<String> subOwners = town.subOwnerIds();
        int height = CARD_PADDING * 2 + ROW_HEIGHT * (1 + subOwners.size());
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                Text.translatable("betterloka.town_finder.leader").getString(),
                nameOf(town.ownerId()), GuiTheme.TEXT);

        for (int i = 0; i < subOwners.size(); i++) {
            textY += ROW_HEIGHT;
            String label = i == 0
                    ? Text.translatable("betterloka.town_finder.subowners", subOwners.size()).getString()
                    : "";
            GuiTheme.statRow(context, this.textRenderer, textX, textY, inner, label,
                    nameOf(subOwners.get(i)), GuiTheme.MUTED);
        }
        return y + height + CARD_GAP;
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

    private int renderAlliance(DrawContext context, int left, int y, int width, int inner) {
        int rows = 1 + Math.min(MAX_PARTNERS, partners.size()) + (partners.isEmpty() ? 1 : 1);
        int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
        GuiTheme.panel(context, left, y, width, height);

        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;
        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                Text.translatable("betterloka.town_finder.alliance").getString(),
                alliance == null ? Text.translatable("betterloka.town_finder.no_alliance").getString()
                        : alliance.name(),
                alliance == null ? GuiTheme.MUTED : GuiTheme.ACCENT);
        textY += ROW_HEIGHT;

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.town_finder.usual_allies"), textX, textY, GuiTheme.MUTED);
        textY += ROW_HEIGHT;

        if (partners.isEmpty()) {
            // Loka publishes no alliance history, so this fills in from the Logger's own watching.
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(
                            Text.translatable("betterloka.town_finder.no_ally_history").getString(), inner),
                    textX, textY, GuiTheme.MUTED);
            return y + height + CARD_GAP;
        }

        for (TownLogStore.TownPartner partner : partners.subList(0, Math.min(MAX_PARTNERS, partners.size()))) {
            String label = partner.name() != null ? partner.name() : partner.townId();
            GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                    this.textRenderer.trimToWidth(label, inner / 2),
                    Text.translatable("betterloka.town_finder.ally_days", partner.days()).getString(),
                    GuiTheme.TEXT);
            textY += ROW_HEIGHT;
        }
        return y + height + CARD_GAP;
    }

    /** Territories come from the logger's standing sweep, so this costs nothing extra. */
    private int renderTerritories(DrawContext context, int left, int y, int width, int inner) {
        int headerHeight = CARD_PADDING * 2 + ROW_HEIGHT;
        GuiTheme.panel(context, left, y, width, headerHeight);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.town_finder.territories", held.size()),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.TEXT);
        y += headerHeight + CARD_GAP;

        if (held.isEmpty()) {
            GuiTheme.panel(context, left, y, width, headerHeight);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.town_finder.no_territories"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            return y + headerHeight + CARD_GAP;
        }

        for (Territory territory : held) {
            GuiTheme.panel(context, left, y, width, headerHeight);
            String where = Text.translatable("betterloka.town_log.where", territory.continent(),
                    territory.num(), territory.coordinates()).getString();
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(where, inner - 70), left + CARD_PADDING, y + CARD_PADDING,
                    GuiTheme.TEXT);
            if (territory.areaName() != null) {
                context.drawTextWithShadow(this.textRenderer, territory.areaName(),
                        left + width - CARD_PADDING - this.textRenderer.getWidth(territory.areaName()),
                        y + CARD_PADDING, GuiTheme.MUTED);
            }
            y += headerHeight + CARD_GAP;
        }
        return y;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
