package com.betterloka.gui;

import com.betterloka.BetterLoka;
import com.betterloka.BetterLokaClient;
import com.betterloka.api.MarketApi;
import com.betterloka.api.model.MarketDeal;
import com.betterloka.api.model.MarketListing;
import com.betterloka.async.AsyncSlot;
import com.betterloka.async.Debounce;
import net.minecraft.client.MinecraftClient;
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

/**
 * Browses Loka's market: what is on sale, from whom and for how much.
 *
 * <p>Three views. <em>Search</em> looks one item type up and lists its offers cheapest first.
 * <em>Deals</em> sweeps the whole market for listings priced under the going rate for their item.
 * <em>Special</em> picks out the named gear — the one-off swords and armour rather than stacks of
 * cobblestone.
 */
public class LokaMarketScreen extends Screen {
    private enum Tab {
        SEARCH("betterloka.market.tab.search"),
        DEALS("betterloka.market.tab.deals"),
        SPECIAL("betterloka.market.tab.special");

        private final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final int MAX_CONTENT_WIDTH = 400;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    private static final int TAB_ROW_Y = 26;
    private static final int TAB_ROW_HEIGHT = 16;
    private static final int SEARCH_ROW_Y = TAB_ROW_Y + TAB_ROW_HEIGHT + 4;
    private static final int SEARCH_ROW_HEIGHT = 18;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int VIEWPORT_TOP = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 6;

    /** Loading every listing is around fifty requests, so the special view is capped for sanity. */
    private static final int MAX_SPECIAL_ROWS = 120;

    /** How many type names to offer under the search field. */
    private static final int MAX_SUGGESTIONS = 6;

    /** Gap between the three columns of a listing card. */
    private static final int COLUMN_GAP = 8;

    /** The enchantment column never gets narrower than this, however long the item name is. */
    private static final int MIN_ENCHANT_WIDTH = 72;

    /** How many enchantments to spell out before the rest are just counted. */
    private static final int MAX_ENCHANT_ROWS = 5;

    /** How long the field must stand still before a lookup goes out. */
    private static final long DEBOUNCE_MILLIS = 250;

    /** How long the same query counts as already answered, so a retype does not refetch. */
    private static final long DEDUPE_MILLIS = 2000;

    /** What one search found: the type it settled on, and its offers. */
    private record Found(String type, List<MarketListing> listings) {
    }

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private Tab tab = Tab.SEARCH;
    private TextFieldWidget queryField;
    private ButtonWidget searchButton;

    /**
     * One slot per view, and that is the whole point.
     *
     * <p>All three views shared a single result field, a single loading flag and a single generation
     * counter. Starting a search and switching to Deals before it landed put the search's answer in
     * Deals — measured in seventeen of twenty attempts — while the shared flag made Deals' own load
     * return without starting, leaving it permanently empty. Separate slots make both impossible
     * rather than unlikely: a slot only ever writes its own view, and an answer whose view has moved
     * on is dropped before it is applied.
     */
    private final AsyncSlot<Found> searchSlot = new AsyncSlot<>("market search", this::onClientThread);
    private final AsyncSlot<MarketApi.Snapshot> dealsSlot =
            new AsyncSlot<>("market deals", this::onClientThread);
    private final AsyncSlot<MarketApi.Snapshot> specialSlot =
            new AsyncSlot<>("market special", this::onClientThread);
    private final AsyncSlot<List<String>> suggestSlot =
            new AsyncSlot<>("market suggestions", this::onClientThread);

    private final Debounce suggestDebounce = new Debounce(DEBOUNCE_MILLIS, DEDUPE_MILLIS);

    private int snapshotPages;
    /** Worked out once per snapshot: a sweep of every listing is not something to redo each frame. */
    private List<MarketDeal> cachedDeals;
    private MarketApi.Snapshot cachedDealsFor;
    private List<MarketListing> cachedSpecial;
    private MarketApi.Snapshot cachedSpecialFor;

    /** Type names matching what has been typed, offered under the field. */
    private final List<int[]> suggestionBounds = new ArrayList<>();
    private int lastMouseX;
    private int lastMouseY;

    /** Identity IDs of the rows actually drawn this frame, so only those are looked up. */
    private final List<String> visibleOwners = new ArrayList<>();

    public LokaMarketScreen(Screen parent) {
        super(Text.translatable("betterloka.module.loka_market"));
        this.parent = parent;
    }

    /**
     * Runs an action on the client thread, and only while this screen is still the open one.
     *
     * <p>Every slot replies through here, which is what keeps their state single-threaded: it is
     * read from {@code render} on every frame and written only from this queue.
     */
    private void onClientThread(Runnable action) {
        MinecraftClient client = this.client;
        if (client == null) {
            return;
        }
        client.execute(() -> {
            if (client.currentScreen == this) {
                action.run();
            }
        });
    }

    /** The slot behind whatever is on screen. */
    private AsyncSlot<?> currentSlot() {
        return switch (tab) {
            case SEARCH -> searchSlot;
            case DEALS -> dealsSlot;
            case SPECIAL -> specialSlot;
        };
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

        int tabWidth = (width - 8) / 3;
        int x = left;
        for (Tab value : Tab.values()) {
            Tab target = value;
            int thisWidth = value == Tab.SPECIAL ? left + width - x : tabWidth;
            addDrawableChild(ButtonWidget.builder(tabLabel(value), button -> selectTab(target))
                    .dimensions(x, TAB_ROW_Y, thisWidth, TAB_ROW_HEIGHT).build());
            x += tabWidth + 4;
        }

        String previous = queryField != null ? queryField.getText() : "";
        queryField = new TextFieldWidget(this.textRenderer, left, SEARCH_ROW_Y,
                width - SEARCH_BUTTON_WIDTH - 4, SEARCH_ROW_HEIGHT, Text.translatable("betterloka.market.field"));
        queryField.setMaxLength(48);
        queryField.setPlaceholder(Text.translatable("betterloka.market.placeholder").formatted(Formatting.DARK_GRAY));
        queryField.setText(previous);
        addDrawableChild(queryField);

        searchButton = ButtonWidget.builder(Text.translatable("betterloka.market.search"), button -> search())
                .dimensions(left + width - SEARCH_BUTTON_WIDTH, SEARCH_ROW_Y, SEARCH_BUTTON_WIDTH, SEARCH_ROW_HEIGHT)
                .build();
        addDrawableChild(searchButton);

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 26, 200, 20).build());

        if (tab == Tab.SEARCH) {
            setInitialFocus(queryField);
        }
    }

    private Text tabLabel(Tab value) {
        Text label = Text.translatable(value.key);
        return value == tab ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    /**
     * Moves to another view, abandoning what the one being left was fetching.
     *
     * <p>Cancelling rather than ignoring is the difference that matters: an ignored request still
     * holds a worker and a permit from the budget every screen shares, and that is how one abandoned
     * search used to make every other tab slow.
     */
    private void selectTab(Tab target) {
        if (tab == target) {
            // Pressing the open tab again is the retry. A view that failed says so and says this,
            // which beats a button that only exists in the one state it is needed.
            if (currentSlot().failed()) {
                currentSlot().reset();
                loadCurrentTab();
            }
            return;
        }
        currentSlot().cancel();
        tab = target;
        scrollPanel.reset();
        suggestSlot.cancel();
        suggestDebounce.clear();
        clearAndInit();
        loadCurrentTab();
    }

    /** Starts the open view's own fetch, if it has not got one and is not already loading. */
    private void loadCurrentTab() {
        switch (tab) {
            case DEALS -> loadSweep(dealsSlot);
            case SPECIAL -> loadSweep(specialSlot);
            case SEARCH -> { }
        }
    }

    /**
     * Loads the market-wide sweep into one view's slot.
     *
     * <p>Deals and Special get a slot each, so neither can write into the other and each shows its
     * own loading and error state. They cost one sweep between them regardless: {@link
     * MarketApi#snapshot} hands both the same in-flight request.
     */
    private void loadSweep(AsyncSlot<MarketApi.Snapshot> slot) {
        if (slot.value() != null || slot.loading()) {
            return;
        }
        snapshotPages = 0;
        MarketApi market = BetterLokaClient.market();
        slot.start(market.executor(),
                // get, not join: join ignores interruption, and cancelling has to actually stop it.
                () -> market.snapshot(pages -> snapshotPages = pages).get());
    }

    private void search() {
        String query = queryField.getText().trim();
        if (query.isEmpty()) {
            return;
        }
        tab = Tab.SEARCH;
        suggestSlot.cancel();
        suggestDebounce.accept(query, System.currentTimeMillis());
        scrollPanel.reset();

        MarketApi market = BetterLokaClient.market();
        searchSlot.start(market.executor(), () -> {
            List<String> matches = market.matchTypes(query, 1);
            if (matches.isEmpty()) {
                return null;
            }
            String type = matches.get(0);
            return new Found(type, market.fetchListings(type));
        });
    }

    /**
     * The underpriced listings across the whole market.
     *
     * <p>Worked out once per snapshot rather than per frame, and keyed on the snapshot itself so a
     * refreshed sweep replaces it without anybody having to remember to clear it.
     */
    private List<MarketDeal> deals() {
        MarketApi.Snapshot snapshot = dealsSlot.value();
        if (snapshot == null) {
            return List.of();
        }
        if (cachedDeals == null || cachedDealsFor != snapshot) {
            long start = System.nanoTime();
            cachedDeals = MarketDeal.find(snapshot.listings());
            cachedDealsFor = snapshot;
            timing("parse deals", snapshot.listings().size(), start);
        }
        return cachedDeals;
    }

    /**
     * Every named item on sale, dearest first — the gear worth looking at.
     *
     * <p>Cached like the deals are. This used to be rebuilt and re-sorted on every frame the Special
     * tab was open, which is work the answer to does not change between frames.
     */
    private List<MarketListing> specialRows() {
        MarketApi.Snapshot snapshot = specialSlot.value();
        if (snapshot == null) {
            return List.of();
        }
        if (cachedSpecial == null || cachedSpecialFor != snapshot) {
            long start = System.nanoTime();
            List<MarketListing> special = new ArrayList<>();
            for (MarketListing listing : snapshot.listings()) {
                if (listing.isSpecial()) {
                    special.add(listing);
                }
            }
            special.sort(Comparator.comparingDouble(MarketListing::price).reversed());
            cachedSpecial = special.size() > MAX_SPECIAL_ROWS
                    ? List.copyOf(special.subList(0, MAX_SPECIAL_ROWS))
                    : List.copyOf(special);
            cachedSpecialFor = snapshot;
            timing("parse special", snapshot.listings().size(), start);
        }
        return cachedSpecial;
    }

    /** Where the time went, for tracing a slow screen. */
    private static void timing(String phase, int items, long startNanos) {
        if (BetterLoka.LOGGER.isDebugEnabled()) {
            BetterLoka.LOGGER.debug("[betterloka] timing {} {} items {} ms", phase, items,
                    (System.nanoTime() - startNanos) / 1_000_000);
        }
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
        List<String> suggestions = suggestions();
        for (int i = 0; i < suggestionBounds.size() && i < suggestions.size(); i++) {
            int[] bounds = suggestionBounds.get(i);
            if (click.x() >= bounds[0] && click.x() < bounds[0] + bounds[2]
                    && click.y() >= bounds[1] && click.y() < bounds[1] + bounds[3]) {
                queryField.setText(suggestions.get(i));
                // Dropped, or the list would reopen on the very text just chosen.
                suggestSlot.reset();
                search();
                return true;
            }
        }
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
        long frameStart = System.nanoTime();
        super.render(context, mouseX, mouseY, delta);
        AsyncSlot<?> slot = currentSlot();
        searchButton.active = !searchSlot.loading() && !queryField.getText().trim().isEmpty();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        refreshSuggestions();
        visibleOwners.clear();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used;

        if (slot.loading()) {
            String text = tab == Tab.SEARCH
                    ? Text.translatable("betterloka.market.searching").getString()
                    : Text.translatable("betterloka.market.loading", snapshotPages).getString();
            context.drawTextWithShadow(this.textRenderer, text, left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else if (slot.failed()) {
            // A failed view says so and offers the way out, rather than sitting on "loading" for a
            // request that is never coming back.
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.unreachable").formatted(Formatting.RED),
                    left, y + 4, GuiTheme.BAD);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.retry_hint").formatted(Formatting.GRAY),
                    left, y + 4 + ROW_HEIGHT + 2, GuiTheme.MUTED);
            used = 20 + ROW_HEIGHT;
        } else {
            used = switch (tab) {
                case SEARCH -> renderSearch(context, left, y, cardWidth);
                case DEALS -> renderDeals(context, left, y, cardWidth);
                case SPECIAL -> renderListings(context, left, y, cardWidth, specialRows(), null);
            } - y;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);

        // Drawn after the content so the list sits over it rather than under.
        renderSuggestions(context, left, width);

        // Only the rows that were actually drawn. Resolving every seller on the market was 147
        // requests admitted by one click; a screenful is about fifteen.
        if (!visibleOwners.isEmpty()) {
            BetterLokaClient.market().requestSellerNames(visibleOwners);
        }
        timing("render " + tab, used, frameStart);
    }

    /** The Search view: its offers, or why there are none. */
    private int renderSearch(DrawContext context, int left, int y, int width) {
        Found found = searchSlot.value();
        if (found == null) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(searchSlot.state() == AsyncSlot.State.READY
                            ? "betterloka.market.no_type" : "betterloka.market.hint",
                            queryField.getText().trim()),
                    left, y + 4, GuiTheme.MUTED);
            return y + 20;
        }
        if (found.listings().isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.no_offers", found.type()),
                    left, y + 4, GuiTheme.MUTED);
            return y + 20;
        }
        return renderListings(context, left, y, width, found.listings(), found.type());
    }

    /** Whether a card at this y is inside the viewport, so off-screen rows cost nothing to skip. */
    private boolean visible(int y, int height) {
        return y + height >= scrollPanel.viewportTop() && y <= scrollPanel.viewportBottom();
    }

    /**
     * The bargains: one card each, with how far under the going rate it is.
     *
     * @return the y just past the last card
     */
    private int renderDeals(DrawContext context, int left, int y, int width) {
        List<MarketDeal> deals = deals();
        if (deals.isEmpty()) {
            GuiTheme.panel(context, left, y, width, CARD_PADDING * 2 + ROW_HEIGHT);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(dealsSlot.value() == null
                            ? "betterloka.market.hint" : "betterloka.market.no_deals"),
                    left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
            return y + CARD_PADDING * 2 + ROW_HEIGHT + CARD_GAP;
        }

        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.market.deals_count", deals.size()), left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

        int inner = width - CARD_PADDING * 2;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        for (MarketDeal deal : deals) {
            MarketListing listing = deal.listing();
            // Cards are a fixed height here, so anything off-screen can be stepped over rather than
            // formatted and drawn into a scissor that throws it away.
            if (!visible(y, height)) {
                y += height + CARD_GAP;
                continue;
            }
            noteOwner(listing);
            GuiTheme.panel(context, left, y, width, height);
            context.fill(left, y, left + 2, y + height, GuiTheme.GOOD);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(listing.displayName(), inner - 90), textX, textY,
                    GuiTheme.TEXT);

            String discount = deal.discountText();
            context.drawTextWithShadow(this.textRenderer, discount,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(discount), textY, GuiTheme.GOOD);

            String seller = Text.translatable("betterloka.market.seller", sellerOf(listing)).getString();
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(seller, inner / 2), textX, textY + ROW_HEIGHT, GuiTheme.MUTED);

            // Both prices, because the discount only means something next to what it is measured on.
            String prices = Text.translatable("betterloka.market.deal_price",
                    money(listing.pricePerUnit()), money(deal.typicalPricePerUnit()),
                    listing.quantity()).getString();
            context.drawTextWithShadow(this.textRenderer, prices,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(prices), textY + ROW_HEIGHT,
                    GuiTheme.MUTED);

            y += height + CARD_GAP;
        }
        return y;
    }

    /**
     * Type names matching what has been typed so far.
     *
     * <p>Recomputed only when the text changes: the type list is cached in the API, but scanning
     * hundreds of names every frame is still work worth not doing.
     */
    private void refreshSuggestions() {
        String query = queryField.getText().trim();
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

    /** What the field currently offers, if anything landed. */
    private List<String> suggestions() {
        return suggestSlot.valueOr(List.of());
    }

    /** Draws the suggestion list over the content, and remembers where each row landed. */
    private void renderSuggestions(DrawContext context, int left, int width) {
        suggestionBounds.clear();
        List<String> suggestions = suggestions();
        if (suggestions.isEmpty() || !queryField.isFocused()) {
            return;
        }
        int rowHeight = ROW_HEIGHT + 2;
        int y = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 1;
        int panelWidth = width - SEARCH_BUTTON_WIDTH - 4;
        int height = suggestions.size() * rowHeight + 2;
        // Opaque, because the list floats over whatever the tab is drawing underneath it.
        context.fill(left, y, left + panelWidth, y + height, 0xFF121218);
        GuiTheme.panel(context, left, y, panelWidth, height);

        for (int i = 0; i < suggestions.size(); i++) {
            int rowY = y + 1 + i * rowHeight;
            boolean hovered = lastMouseX >= left && lastMouseX < left + panelWidth
                    && lastMouseY >= rowY && lastMouseY < rowY + rowHeight;
            if (hovered) {
                context.fill(left + 1, rowY, left + panelWidth - 1, rowY + rowHeight, 0x40FFFFFF);
            }
            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(prettify(suggestions.get(i)), panelWidth - 10),
                    left + 5, rowY + 2, hovered ? GuiTheme.ACCENT : GuiTheme.TEXT);
            suggestionBounds.add(new int[]{left, rowY, panelWidth, rowHeight});
        }
    }

    /** {@code DIAMOND_SWORD} reads better as {@code Diamond Sword} in a suggestion list. */
    private static String prettify(String type) {
        StringBuilder out = new StringBuilder(type.length());
        for (String word : type.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    /** @return the y just past the last row. */
    private int renderListings(DrawContext context, int left, int y, int width, List<MarketListing> listings, String header) {
        if (listings.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable(tab == Tab.SEARCH ? "betterloka.market.hint" : "betterloka.market.no_special"),
                    left, y + 4, GuiTheme.MUTED);
            return y + 20;
        }

        if (header != null) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.offers", listings.get(0).prettyType(), listings.size()),
                    left, y + 2, GuiTheme.MUTED);
            y += ROW_HEIGHT + 3;
        }

        int inner = width - CARD_PADDING * 2;
        for (MarketListing listing : listings) {
            boolean special = listing.isSpecial();
            List<String> enchantLines = enchantLines(listing.enchantments());

            // Worked out before anything is formatted, because a card off-screen should cost the
            // height arithmetic and nothing else. Rows here vary in height with their enchantments,
            // so the height has to come first.
            int rows = Math.max(2, Math.max(special ? 3 : 2, enchantLines.size()));
            int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
            if (!visible(y, height)) {
                y += height + CARD_GAP;
                continue;
            }
            noteOwner(listing);

            // Three columns: what it is and who is selling it on the left, its enchantments down the
            // middle, what it costs on the right.
            List<String> leftLines = new ArrayList<>();
            leftLines.add(listing.displayName());
            leftLines.add(Text.translatable("betterloka.market.seller", sellerOf(listing)).getString());
            if (special) {
                leftLines.add(listing.prettyType());
            }

            String price = money(listing.price());
            String stock = Text.translatable("betterloka.market.stock", listing.quantity()).getString()
                    + "  ·  " + money(listing.pricePerUnit()) + Text.translatable("betterloka.market.each").getString();

            int rightWidth = Math.max(this.textRenderer.getWidth(price), this.textRenderer.getWidth(stock)) + COLUMN_GAP;
            int middleWidth = enchantLines.isEmpty()
                    ? 0
                    : Math.max(MIN_ENCHANT_WIDTH, (inner - rightWidth) * 2 / 5);
            int leftWidth = Math.max(40, inner - rightWidth - middleWidth - (middleWidth == 0 ? 0 : COLUMN_GAP));

            GuiTheme.panel(context, left, y, width, height);

            int textX = left + CARD_PADDING;
            int textY = y + CARD_PADDING;

            context.drawTextWithShadow(this.textRenderer,
                    this.textRenderer.trimToWidth(leftLines.get(0), leftWidth), textX, textY,
                    special ? GuiTheme.ACCENT : GuiTheme.TEXT);
            for (int i = 1; i < leftLines.size(); i++) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(leftLines.get(i), leftWidth),
                        textX, textY + ROW_HEIGHT * i, GuiTheme.MUTED);
            }

            int middleX = textX + leftWidth + COLUMN_GAP;
            for (int i = 0; i < enchantLines.size(); i++) {
                context.drawTextWithShadow(this.textRenderer,
                        this.textRenderer.trimToWidth(enchantLines.get(i), middleWidth),
                        middleX, textY + ROW_HEIGHT * i, GuiTheme.ENCHANT);
            }

            context.drawTextWithShadow(this.textRenderer, price,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(price), textY, GuiTheme.GOOD);
            context.drawTextWithShadow(this.textRenderer, stock,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(stock), textY + ROW_HEIGHT,
                    GuiTheme.MUTED);

            y += height + CARD_GAP;
        }
        return y;
    }

    /**
     * The enchantment column, one per line so a fully kitted sword stays readable.
     *
     * <p>Beyond {@link #MAX_ENCHANT_ROWS} the rest are counted rather than listed — a card that grows
     * to a dozen lines pushes everything else off the screen.
     */
    private static List<String> enchantLines(List<String> enchantments) {
        if (enchantments.isEmpty()) {
            return List.of();
        }
        if (enchantments.size() <= MAX_ENCHANT_ROWS) {
            return enchantments;
        }
        List<String> shown = new ArrayList<>(enchantments.subList(0, MAX_ENCHANT_ROWS - 1));
        shown.add(Text.translatable("betterloka.market.more_enchants",
                enchantments.size() - (MAX_ENCHANT_ROWS - 1)).getString());
        return shown;
    }

    /**
     * The seller's name if it has landed, without asking for it here.
     *
     * <p>Never blocks and never starts a request: this runs while drawing, and the lookups are
     * started once per frame from {@link #render} for the rows that were actually on screen.
     */
    private String sellerOf(MarketListing listing) {
        String name = BetterLokaClient.market().sellerNameIfKnown(listing.ownerId());
        if (name == null) {
            return "...";
        }
        return name.isEmpty() ? "?" : name;
    }

    /** Remembers a drawn row's seller, to be looked up once the frame is done. */
    private void noteOwner(MarketListing listing) {
        String owner = listing.ownerId();
        if (owner != null && !owner.isEmpty() && !visibleOwners.contains(owner)) {
            visibleOwners.add(owner);
        }
    }

    /** Loka's prices run to the millions, so large numbers are abbreviated. */
    private static String money(double amount) {
        if (amount >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2fM", amount / 1_000_000);
        }
        if (amount >= 10_000) {
            return String.format(Locale.ROOT, "%.1fk", amount / 1_000);
        }
        return String.format(Locale.ROOT, "%.0f", amount);
    }

    /**
     * Drops everything in flight when the screen goes away.
     *
     * <p>Screens register no global listeners — every widget goes through {@code addDrawableChild},
     * which Minecraft clears itself — so requests are the only thing that outlives a closed screen,
     * and this is where they stop. Without it, closing the Market left its work holding a worker and
     * a permit for an answer that had nowhere to go.
     */
    @Override
    public void removed() {
        searchSlot.cancel();
        dealsSlot.cancel();
        specialSlot.cancel();
        suggestSlot.cancel();
        super.removed();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
