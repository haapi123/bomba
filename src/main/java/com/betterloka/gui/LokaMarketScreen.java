package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.MarketApi;
import com.betterloka.api.MarketSweep;
import com.betterloka.api.model.MarketListing;
import com.betterloka.api.model.MarketSort;
import com.betterloka.async.AsyncSlot;
import com.betterloka.async.Debounce;
import com.betterloka.market.MarketCategory;
import com.betterloka.market.MarketIndex;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Loka Market: a market square with a stall for each kind of goods, and a search for one item.
 *
 * <p>The square is the whole market, fetched once in the background and kept a few minutes; a
 * stall opens the list of what it sells. The search asks the API for one item type, which is one
 * request and always current.
 */
public class LokaMarketScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 460;
    private static final int TAB_Y = 22;
    private static final int TAB_HEIGHT = 16;
    private static final int TAB_WIDTH = 110;
    private static final int BAR_Y = TAB_Y + TAB_HEIGHT + 5;
    private static final int BAR_HEIGHT = 18;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int REFRESH_WIDTH = 56;
    private static final int MAX_SUGGESTIONS = 6;
    private static final int ROW_HEIGHT = 10;
    private static final long DEBOUNCE_MILLIS = 250;
    private static final long DEDUPE_MILLIS = 2000;

    private enum Tab {
        SQUARE("betterloka.market.tab.square"),
        SEARCH("betterloka.market.tab.search");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    /** What one search found: the type it settled on, and its offers. */
    private record Found(String type, List<MarketListing> listings) {
    }

    private final Screen parent;
    private Tab tab;

    // --- the square ---
    private final MarketSquare square = new MarketSquare();
    private MarketSweep sweep;
    private int indexedVersion = -1;
    private MarketIndex index = MarketIndex.empty();

    // --- the search ---
    private EditBox queryField;
    private Button searchButton;
    private String pendingQuery;
    private final ListingList results = new ListingList();
    private final FilterMenu filter = new FilterMenu(MarketSort.CHEAPEST, sort -> results.reset());
    private final AsyncSlot<Found> searchSlot = new AsyncSlot<>("market search", this::onClientThread);
    private final AsyncSlot<List<String>> suggestSlot =
            new AsyncSlot<>("market suggestions", this::onClientThread);
    private final Debounce suggestDebounce = new Debounce(DEBOUNCE_MILLIS, DEDUPE_MILLIS);
    private List<MarketListing> ordered = List.of();
    private Found orderedFrom;
    private MarketSort orderedBy;
    private final List<int[]> suggestionBounds = new ArrayList<>();
    /** What the field said when it was last searched, so the suggestions stay shut until it changes. */
    private String searchedText;
    /** The answer the market in hand gave the current search, shown until the request lands. */
    private Found instant;

    private int lastMouseX;
    private int lastMouseY;

    public LokaMarketScreen(Screen parent) {
        this(parent, null);
    }

    /**
     * @param search an item type to look up straight away, from an offer's "other offers" button,
     *               or {@code null} to open on the square
     */
    public LokaMarketScreen(Screen parent, String search) {
        super(Component.translatable("betterloka.module.loka_market"));
        this.parent = parent;
        this.tab = search == null ? Tab.SQUARE : Tab.SEARCH;
        this.pendingQuery = search;
    }

    private void onClientThread(Runnable action) {
        Minecraft client = this.minecraft;
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.gui.screen() == this) {
                action.run();
            }
        });
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int contentBottom() {
        return this.height - 30;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        BetterLokaClient.market().sweep(false);
        sweep = BetterLokaClient.market().shown();

        int tabsX = this.width / 2 - TAB_WIDTH - 2;
        for (Tab value : Tab.values()) {
            Tab target = value;
            addRenderableWidget(Button.builder(tabLabel(value), button -> selectTab(target))
                    .bounds(tabsX + value.ordinal() * (TAB_WIDTH + 4), TAB_Y, TAB_WIDTH, TAB_HEIGHT).build());
        }

        if (tab == Tab.SQUARE) {
            square.place(left, BAR_Y + 14, width, contentBottom() - (BAR_Y + 14));
            addRenderableWidget(Button.builder(Component.translatable("betterloka.market.refresh"), button -> refresh())
                    .bounds(left + width - REFRESH_WIDTH, BAR_Y - 2, REFRESH_WIDTH, 14).build());
        } else {
            int filterWidth = FilterMenu.preferredWidth(this.font);
            filter.place(left + width - filterWidth, BAR_Y, filterWidth);
            String previous = queryField != null ? queryField.getValue() : "";
            int fieldWidth = width - filterWidth - SEARCH_BUTTON_WIDTH - 8;
            queryField = new EditBox(this.font, left, BAR_Y, fieldWidth, BAR_HEIGHT,
                    Component.translatable("betterloka.market.field"));
            queryField.setMaxLength(48);
            queryField.setHint(Component.translatable("betterloka.market.placeholder")
                    .withStyle(ChatFormatting.DARK_GRAY));
            queryField.setValue(previous);
            addRenderableWidget(queryField);
            searchButton = Button.builder(Component.translatable("betterloka.market.search"), button -> search())
                    .bounds(left + fieldWidth + 4, BAR_Y, SEARCH_BUTTON_WIDTH, BAR_HEIGHT).build();
            addRenderableWidget(searchButton);
            results.setViewport(left, BAR_Y + BAR_HEIGHT + 16, width, contentBottom() - (BAR_Y + BAR_HEIGHT + 16));
            setInitialFocus(queryField);
            if (pendingQuery != null) {
                queryField.setValue(MarketListing.pretty(pendingQuery));
                searchedText = queryField.getValue().trim();
                String exact = pendingQuery;
                pendingQuery = null;
                searchType(exact);
            }
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 25, 200, 20).build());
    }

    private Component tabLabel(Tab value) {
        Component label = Component.translatable(value.key);
        return value == tab ? label.copy().withStyle(ChatFormatting.YELLOW) : label;
    }

    private void selectTab(Tab target) {
        if (tab != target) {
            tab = target;
            filter.close();
            rebuildWidgets();
        }
    }

    private void refresh() {
        BetterLokaClient.market().sweep(true);
        indexedVersion = -1;
    }

    /** The square's stalls, worked out again only when another page of the market has come in. */
    private MarketIndex index() {
        // The newest market that finished, kept on screen while a fresher one comes in behind it.
        MarketSweep shown = BetterLokaClient.market().shown();
        if (shown != null && shown != sweep) {
            sweep = shown;
            indexedVersion = -1;
        }
        if (sweep != null && sweep.version() != indexedVersion) {
            indexedVersion = sweep.version();
            index = MarketIndex.of(sweep.listings());
        }
        return index;
    }

    // --- the search ---

    private void search() {
        String query = queryField.getValue().trim();
        if (query.isEmpty()) {
            return;
        }
        searchedText = query;
        suggestSlot.cancel();
        suggestDebounce.accept(query, System.currentTimeMillis());
        results.reset();
        MarketApi market = BetterLokaClient.market();
        // The market already in hand answers at once; the request below brings the current offers.
        instant = instantFor(query, null);
        searchSlot.start(market.executor(), () -> {
            List<String> matches = market.matchTypes(query, 1);
            if (matches.isEmpty()) {
                return null;
            }
            String type = matches.get(0);
            return new Found(type, market.fetchListings(type));
        });
    }

    /** Looks up one exact type, as "other offers" from a profile asks. */
    private void searchType(String type) {
        results.reset();
        instant = instantFor(null, type);
        MarketApi market = BetterLokaClient.market();
        searchSlot.start(market.executor(), () -> new Found(type, market.fetchListings(type)));
    }

    /**
     * What the market already fetched says about a search, to show while the request goes out.
     *
     * @param query what was typed, matched against the types on sale; or {@code null} with
     * @param type  an exact type
     */
    private Found instantFor(String query, String type) {
        MarketSweep shown = BetterLokaClient.market().shown();
        if (shown == null || shown.listings().isEmpty()) {
            return null;
        }
        String wanted = type;
        if (wanted == null) {
            java.util.Set<String> types = new java.util.LinkedHashSet<>();
            for (MarketListing listing : shown.listings()) {
                types.add(listing.type());
            }
            List<String> matches = MarketApi.matchTypes(List.copyOf(types), query, 1);
            if (matches.isEmpty()) {
                return null;
            }
            wanted = matches.get(0);
        }
        List<MarketListing> offers = new ArrayList<>();
        for (MarketListing listing : shown.listings()) {
            if (wanted.equals(listing.type())) {
                offers.add(listing);
            }
        }
        return offers.isEmpty() ? null : new Found(wanted, List.copyOf(offers));
    }

    /** The search's answer, or while it is on its way, what the market in hand said. */
    private Found found() {
        Found found = searchSlot.value();
        return found == null && (searchSlot.loading() || searchSlot.failed()) ? instant : found;
    }

    private List<MarketListing> ordered() {
        Found found = found();
        if (found == null) {
            return List.of();
        }
        if (orderedFrom != found || orderedBy != filter.sort()) {
            ordered = filter.sort().sort(found.listings());
            orderedFrom = found;
            orderedBy = filter.sort();
        }
        return ordered;
    }

    private void refreshSuggestions() {
        String query = queryField.getValue().trim();
        if (query.length() < 2 || !queryField.isFocused()) {
            suggestSlot.reset();
            suggestDebounce.clear();
            return;
        }
        long now = System.currentTimeMillis();
        suggestDebounce.offer(query, now);
        String due = suggestDebounce.take(now);
        if (due == null) {
            return;
        }
        MarketApi market = BetterLokaClient.market();
        suggestSlot.start(market.executor(), () -> market.matchTypes(due, MAX_SUGGESTIONS));
    }

    private List<String> suggestions() {
        if (queryField.getValue().trim().equals(searchedText)) {
            return List.of();
        }
        List<String> found = suggestSlot.valueOr(List.of());
        if (found.size() == 1 && MarketListing.pretty(found.get(0)).equalsIgnoreCase(queryField.getValue().trim())) {
            return List.of();
        }
        return found;
    }

    // --- input ---

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (tab == Tab.SEARCH && input.isConfirmation() && queryField.isFocused()) {
            search();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return tab == Tab.SEARCH && results.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (tab == Tab.SQUARE) {
            if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                MarketCategory stall = square.stallAt(click.x(), click.y());
                if (stall != null) {
                    this.minecraft.gui.setScreen(new MarketStallScreen(this, stall));
                    return true;
                }
            }
            return super.mouseClicked(click, doubled);
        }

        if (filter.mouseClicked(click.x(), click.y())) {
            return true;
        }
        List<String> suggestions = suggestions();
        for (int i = 0; i < suggestionBounds.size() && i < suggestions.size(); i++) {
            int[] bounds = suggestionBounds.get(i);
            if (click.x() >= bounds[0] && click.x() < bounds[0] + bounds[2]
                    && click.y() >= bounds[1] && click.y() < bounds[1] + bounds[3]) {
                String type = suggestions.get(i);
                queryField.setValue(MarketListing.pretty(type));
                searchedText = queryField.getValue().trim();
                suggestSlot.reset();
                suggestDebounce.accept(queryField.getValue().trim(), System.currentTimeMillis());
                searchType(type);
                return true;
            }
        }
        if (results.mouseClickedScrollbar(click.x(), click.y())) {
            return true;
        }
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            MarketListing listing = results.clicked(click.x(), click.y());
            if (listing != null) {
                this.minecraft.gui.setScreen(new MarketItemScreen(this, listing));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return tab == Tab.SEARCH && results.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        results.mouseReleased();
        return super.mouseReleased(click);
    }

    // --- drawing ---

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        if (tab == Tab.SEARCH) {
            searchButton.active = !searchSlot.loading() && !queryField.getValue().trim().isEmpty();
            refreshSuggestions();
        }
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(this.font, this.title, this.width / 2, 8, GuiTheme.TEXT);
        if (tab == Tab.SQUARE) {
            renderSquare(context, mouseX, mouseY);
        } else {
            renderSearch(context, mouseX, mouseY);
        }
    }

    private void renderSquare(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        MarketIndex stalls = index();
        MarketSweep current = sweep;
        boolean loading = current != null && current.running();
        int left = contentLeft();

        Component status;
        int statusColor = GuiTheme.MUTED;
        if (current == null || current.failed()) {
            status = Component.translatable("betterloka.market.unreachable");
            statusColor = GuiTheme.BAD;
        } else if (loading) {
            status = Component.translatable("betterloka.market.fetching", current.pagesDone(),
                    Math.max(1, current.pagesTotal()));
            statusColor = GuiTheme.LIVE;
        } else {
            status = Component.translatable("betterloka.market.square_status", stalls.total(),
                    TimeFormat.ago(current.finishedAt()));
            if (current.pagesFailed() > 0) {
                status = status.copy().append(Component.translatable("betterloka.market.pages_missing",
                        current.pagesFailed()).withStyle(ChatFormatting.RED));
            }
            MarketSweep coming = BetterLokaClient.market().currentSweep();
            if (BetterLokaClient.market().refreshing() && coming != current) {
                status = status.copy().append((coming.pagesTotal() == 0
                        ? Component.translatable("betterloka.market.refreshing_start")
                        : Component.translatable("betterloka.market.refreshing", coming.pagesDone(),
                                coming.pagesTotal())).withStyle(ChatFormatting.YELLOW));
            }
        }
        context.text(this.font, status, left, BAR_Y + 1, statusColor);
        if (loading) {
            int barX = left + this.font.width(status) + 6;
            int barW = Math.max(20, contentLeft() + contentWidth() - REFRESH_WIDTH - 8 - barX);
            GuiTheme.progressBar(context, barX, BAR_Y + 2, Math.min(barW, 120), 5, current.progress());
        }

        MarketCategory hovered = devHovered != null ? devHovered : square.stallAt(mouseX, mouseY);
        square.render(context, this.font, stalls, loading, hovered);
        if (hovered != null) {
            if (devHovered != null) {
                int[] centre = square.centreOf(devHovered);
                mouseX = centre[0];
                mouseY = centre[1];
            }
            MarketIndex.Stall stall = stalls.stall(hovered);
            List<Component> lines = List.of(
                    Component.translatable(hovered.key()).withStyle(ChatFormatting.YELLOW),
                    Component.translatable(hovered.descriptionKey()).withStyle(ChatFormatting.GRAY),
                    Component.translatable("betterloka.market.stall_tooltip", stall.offers(), stall.types())
                            .withStyle(ChatFormatting.DARK_GRAY));
            context.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        }
    }

    private void renderSearch(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int left = contentLeft();
        int headerY = BAR_Y + BAR_HEIGHT + 4;
        Found found = found();
        Component header;
        Component empty;
        List<MarketListing> listings = List.of();
        if (found != null && found == instant) {
            // Shown at once from the market in hand while the current offers are asked for.
            listings = ordered();
            header = Component.translatable("betterloka.market.offers", MarketListing.pretty(found.type()),
                    listings.size(), Component.translatable(filter.sort().orderKey()))
                    .append(Component.translatable(searchSlot.loading() ? "betterloka.market.updating"
                            : "betterloka.market.from_cache").withStyle(ChatFormatting.YELLOW));
            empty = Component.empty();
        } else if (searchSlot.loading()) {
            header = Component.translatable("betterloka.market.searching");
            empty = Component.empty();
        } else if (searchSlot.failed()) {
            header = Component.translatable("betterloka.market.unreachable").withStyle(ChatFormatting.RED);
            empty = Component.translatable("betterloka.market.retry_search");
        } else if (found == null) {
            header = Component.translatable(searchSlot.state() == AsyncSlot.State.READY
                    ? "betterloka.market.no_type" : "betterloka.market.hint", queryField.getValue().trim());
            empty = Component.empty();
        } else {
            listings = ordered();
            header = Component.translatable("betterloka.market.offers", MarketListing.pretty(found.type()),
                    listings.size(), Component.translatable(filter.sort().orderKey()));
            empty = Component.translatable("betterloka.market.no_offers", MarketListing.pretty(found.type()));
        }
        context.text(this.font, header, left, headerY, GuiTheme.MUTED);
        results.render(context, this.font, listings, filter.sort(), empty, mouseX, mouseY,
                filter.isOpen() || !suggestions().isEmpty() && queryField.isFocused());

        filter.render(context, this.font, mouseX, mouseY);
        renderSuggestions(context, left);
        filter.renderMenu(context, this.font, mouseX, mouseY);
    }

    private void renderSuggestions(GuiGraphicsExtractor context, int left) {
        suggestionBounds.clear();
        List<String> suggestions = suggestions();
        if (suggestions.isEmpty() || !queryField.isFocused()) {
            return;
        }
        context.nextStratum();
        int rowHeight = ROW_HEIGHT + 2;
        int y = BAR_Y + BAR_HEIGHT + 1;
        int panelWidth = queryField.getWidth();
        int height = suggestions.size() * rowHeight + 2;
        context.fill(left, y, left + panelWidth, y + height, 0xFF121218);
        GuiTheme.panel(context, left, y, panelWidth, height);
        for (int i = 0; i < suggestions.size(); i++) {
            int rowY = y + 1 + i * rowHeight;
            boolean hovered = lastMouseX >= left && lastMouseX < left + panelWidth
                    && lastMouseY >= rowY && lastMouseY < rowY + rowHeight;
            if (hovered) {
                context.fill(left + 1, rowY, left + panelWidth - 1, rowY + rowHeight, 0x40FFFFFF);
            }
            context.text(this.font,
                    this.font.plainSubstrByWidth(MarketListing.pretty(suggestions.get(i)), panelWidth - 10),
                    left + 5, rowY + 2, hovered ? GuiTheme.ACCENT : GuiTheme.TEXT);
            suggestionBounds.add(new int[]{left, rowY, panelWidth, rowHeight});
        }
    }

    // --- for the dev harness ---

    /** Where a stall stands on screen. */
    public int[] devStallCentre(String categoryId) {
        return square.centreOf(MarketCategory.byId(categoryId));
    }

    /** Puts text in the search field and runs the search, as typing and Enter would. */
    public void devSearch(String query) {
        if (tab != Tab.SEARCH) {
            selectTab(Tab.SEARCH);
        }
        queryField.setValue(query);
        search();
    }

    /** Where the first result card {@code match} accepts is drawn. */
    public int[] devRowWhere(java.util.function.Predicate<MarketListing> match) {
        return results.rowWhere(match);
    }

    public int[] devFilterButton() {
        return filter.centre();
    }

    public int[] devFilterOption(int i) {
        return filter.optionCentre(i);
    }

    /** Lights a stall up as if the pointer were on it, which a headless window cannot do. */
    public void devHover(String categoryId) {
        devHovered = categoryId == null ? null : MarketCategory.byId(categoryId);
    }

    private MarketCategory devHovered;

    public String devState() {
        MarketSweep current = sweep;
        return "tab=" + tab + " sweep=" + (current == null ? "none"
                : current.pagesDone() + "/" + current.pagesTotal() + " listings=" + current.listings().size()
                + (current.running() ? " running" : " done")) + " search=" + searchSlot.state()
                + " sort=" + filter.sort() + " shown=" + ordered.size();
    }

    @Override
    public void removed() {
        searchSlot.cancel();
        suggestSlot.cancel();
        super.removed();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
