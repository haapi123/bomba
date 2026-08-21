package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.ApiException;
import com.betterloka.api.MarketApi;
import com.betterloka.api.model.MarketDeal;
import com.betterloka.api.model.MarketListing;
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
 * Browses Loka's market: what is on sale, from whom and for how much.
 *
 * <p>Three views. <em>Search</em> looks one item type up and lists its offers cheapest first.
 * <em>Special</em> sweeps the whole market for named and lore-bearing gear — the one-off swords and
 * armour rather than stacks of cobblestone. <em>Overview</em> totals what is listed.
 */
public class LokaMarketScreen extends Screen {
    private enum Tab {
        SEARCH("betterloka.market.tab.search"),
        DEALS("betterloka.market.tab.deals"),
        SPECIAL("betterloka.market.tab.special"),
        OVERVIEW("betterloka.market.tab.overview");

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

    private final Screen parent;
    private final ScrollPanel scrollPanel = new ScrollPanel();

    private Tab tab = Tab.SEARCH;
    private TextFieldWidget queryField;
    private ButtonWidget searchButton;

    private List<MarketListing> results = List.of();
    private String resolvedType;
    private Text message;
    private boolean loading;
    private int generation;

    private MarketApi.Snapshot snapshot;
    private int snapshotPages;
    /** Worked out once per snapshot: a sweep of every listing is not something to redo each frame. */
    private List<MarketDeal> cachedDeals;

    /** Type names matching what has been typed, offered under the field. */
    private List<String> suggestions = List.of();
    private String suggestedFor = "";
    private final List<int[]> suggestionBounds = new ArrayList<>();
    private int lastMouseX;
    private int lastMouseY;

    /** Seller names arrive after the listings; render reads whatever has landed. */
    private final Map<String, String> sellers = new ConcurrentHashMap<>();

    public LokaMarketScreen(Screen parent) {
        super(Text.translatable("betterloka.module.loka_market"));
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

        int tabWidth = (width - 12) / 4;
        int x = left;
        for (Tab value : Tab.values()) {
            Tab target = value;
            int thisWidth = value == Tab.OVERVIEW ? left + width - x : tabWidth;
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

    private void selectTab(Tab target) {
        if (tab == target) {
            return;
        }
        tab = target;
        scrollPanel.reset();
        message = null;
        suggestions = List.of();
        clearAndInit();
        if (target != Tab.SEARCH) {
            loadSnapshot();
        }
    }




    private void search() {
        String query = queryField.getText().trim();
        if (query.isEmpty() || loading) {
            return;
        }
        tab = Tab.SEARCH;
        suggestions = List.of();
        loading = true;
        message = null;
        results = List.of();
        resolvedType = null;
        scrollPanel.reset();
        int mine = ++generation;

        MarketApi market = BetterLokaClient.market();
        CompletableFuture.supplyAsync(() -> {
            try {
                List<String> matches = market.matchTypes(query, 1);
                if (matches.isEmpty()) {
                    return null;
                }
                String type = matches.get(0);
                return Map.entry(type, market.fetchListings(type));
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, market.executor()).whenComplete((found, error) -> onClientThread(mine, () -> {
            loading = false;
            if (error != null) {
                message = Text.translatable("betterloka.market.unreachable").formatted(Formatting.RED);
            } else if (found == null) {
                message = Text.translatable("betterloka.market.no_type", query).formatted(Formatting.RED);
            } else {
                resolvedType = found.getKey();
                results = found.getValue();
                if (results.isEmpty()) {
                    message = Text.translatable("betterloka.market.no_offers", resolvedType);
                }
                resolveSellers(results);
            }
        }));
    }

    private void loadSnapshot() {
        if (snapshot != null || loading) {
            return;
        }
        loading = true;
        snapshotPages = 0;
        int mine = ++generation;

        BetterLokaClient.market().snapshot(pages -> snapshotPages = pages)
                .whenComplete((loaded, error) -> onClientThread(mine, () -> {
                    loading = false;
                    if (error != null) {
                        message = Text.translatable("betterloka.market.unreachable").formatted(Formatting.RED);
                        return;
                    }
                    snapshot = loaded;
                    cachedDeals = null;
                    resolveSellers(specialRows());
                    resolveSellers(dealListings());
                }));
    }

    /**
     * The underpriced listings across the whole market.
     *
     * <p>Off the same snapshot the Special tab uses, so switching between them costs nothing.
     */
    private List<MarketDeal> deals() {
        if (snapshot == null) {
            return List.of();
        }
        if (cachedDeals == null) {
            cachedDeals = MarketDeal.find(snapshot.listings());
        }
        return cachedDeals;
    }

    /** Every named item on sale, dearest first — the gear worth looking at. */
    private List<MarketListing> specialListings() {
        if (snapshot == null) {
            return List.of();
        }
        List<MarketListing> special = new ArrayList<>();
        for (MarketListing listing : snapshot.listings()) {
            if (listing.isSpecial()) {
                special.add(listing);
            }
        }
        special.sort(Comparator.comparingDouble(MarketListing::price).reversed());
        return special;
    }

    /** The Special tab's rows, capped so a huge market cannot make the screen crawl. */
    private List<MarketListing> specialRows() {
        List<MarketListing> special = specialListings();
        return special.size() > MAX_SPECIAL_ROWS ? special.subList(0, MAX_SPECIAL_ROWS) : special;
    }

    /** Listings carry only the seller's identity ID, so names are looked up behind the rendering. */
    private void resolveSellers(List<MarketListing> listings) {
        MarketApi market = BetterLokaClient.market();
        List<String> pending = new ArrayList<>();
        for (MarketListing listing : listings) {
            String owner = listing.ownerId();
            if (owner != null && !sellers.containsKey(owner) && !pending.contains(owner)) {
                pending.add(owner);
            }
        }
        for (String owner : pending) {
            CompletableFuture.runAsync(() -> {
                String name = market.sellerName(owner);
                sellers.put(owner, name == null ? "" : name);
            }, market.executor());
        }
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
        for (int i = 0; i < suggestionBounds.size(); i++) {
            int[] bounds = suggestionBounds.get(i);
            if (click.x() >= bounds[0] && click.x() < bounds[0] + bounds[2]
                    && click.y() >= bounds[1] && click.y() < bounds[1] + bounds[3]) {
                queryField.setText(suggestions.get(i));
                // Marked as already suggested for, or the list would reopen on the text just chosen.
                suggestedFor = queryField.getText().trim();
                suggestions = List.of();
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
        super.render(context, mouseX, mouseY, delta);
        searchButton.active = !loading && !queryField.getText().trim().isEmpty();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        refreshSuggestions();

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 10, GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();

        context.enableScissor(left, VIEWPORT_TOP, left + width, viewportBottom());
        int y = scrollPanel.contentTop();
        int cardWidth = scrollPanel.contentWidth();
        int used;

        if (loading) {
            String text = tab == Tab.SEARCH
                    ? Text.translatable("betterloka.market.searching").getString()
                    : Text.translatable("betterloka.market.loading", snapshotPages).getString();
            context.drawTextWithShadow(this.textRenderer, text, left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else if (message != null && tab == Tab.SEARCH && results.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, message, left, y + 4, GuiTheme.BAD);
            used = 20;
        } else {
            used = switch (tab) {
                case SEARCH -> renderListings(context, left, y, cardWidth, results, resolvedType);
                case DEALS -> renderDeals(context, left, y, cardWidth);
                case SPECIAL -> renderListings(context, left, y, cardWidth, specialRows(), null);
                case OVERVIEW -> renderOverview(context, left, y, cardWidth);
            } - y;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);

        // Drawn after the content so the list sits over it rather than under.
        renderSuggestions(context, left, width);
    }

    /** The listings behind the deals, so their sellers can be resolved with everything else. */
    private List<MarketListing> dealListings() {
        List<MarketListing> listings = new ArrayList<>();
        for (MarketDeal deal : deals()) {
            listings.add(deal.listing());
        }
        return listings;
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
                    Text.translatable(snapshot == null ? "betterloka.market.hint" : "betterloka.market.no_deals"),
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
        if (query.equals(suggestedFor)) {
            return;
        }
        suggestedFor = query;
        if (query.length() < 2 || !queryField.isFocused()) {
            suggestions = List.of();
            return;
        }
        MarketApi market = BetterLokaClient.market();
        CompletableFuture.supplyAsync(() -> {
            try {
                return market.matchTypes(query, MAX_SUGGESTIONS);
            } catch (ApiException e) {
                return List.<String>of();
            }
        }, market.executor()).thenAccept(found -> {
            if (this.client != null) {
                this.client.execute(() -> {
                    if (this.client.currentScreen == this && query.equals(suggestedFor)) {
                        suggestions = found;
                    }
                });
            }
        });
    }

    /** Draws the suggestion list over the content, and remembers where each row landed. */
    private void renderSuggestions(DrawContext context, int left, int width) {
        suggestionBounds.clear();
        if (suggestions.isEmpty()) {
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

            // Three columns: what it is and who is selling it on the left, its enchantments down the
            // middle, what it costs on the right.
            List<String> leftLines = new ArrayList<>();
            leftLines.add(listing.displayName());
            leftLines.add(Text.translatable("betterloka.market.seller", sellerOf(listing)).getString());
            if (special) {
                leftLines.add(listing.prettyType());
            }

            List<String> enchantLines = enchantLines(listing.enchantments());

            String price = money(listing.price());
            String stock = Text.translatable("betterloka.market.stock", listing.quantity()).getString()
                    + "  ·  " + money(listing.pricePerUnit()) + Text.translatable("betterloka.market.each").getString();

            int rightWidth = Math.max(this.textRenderer.getWidth(price), this.textRenderer.getWidth(stock)) + COLUMN_GAP;
            int middleWidth = enchantLines.isEmpty()
                    ? 0
                    : Math.max(MIN_ENCHANT_WIDTH, (inner - rightWidth) * 2 / 5);
            int leftWidth = Math.max(40, inner - rightWidth - middleWidth - (middleWidth == 0 ? 0 : COLUMN_GAP));

            int rows = Math.max(2, Math.max(leftLines.size(), enchantLines.size()));
            int height = CARD_PADDING * 2 + ROW_HEIGHT * rows;
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

    private int renderOverview(DrawContext context, int left, int y, int width) {
        if (snapshot == null) {
            context.drawTextWithShadow(this.textRenderer, Text.translatable("betterloka.market.hint"),
                    left, y + 4, GuiTheme.MUTED);
            return y + 20;
        }

        int inner = width - CARD_PADDING * 2;
        int height = CARD_PADDING * 2 + ROW_HEIGHT * 5;
        GuiTheme.panel(context, left, y, width, height);
        int textX = left + CARD_PADDING;
        int textY = y + CARD_PADDING;

        GuiTheme.statRow(context, this.textRenderer, textX, textY, inner,
                Text.translatable("betterloka.market.total_value").getString(),
                money(snapshot.totalValue()), GuiTheme.GOOD);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT, inner,
                Text.translatable("betterloka.market.total_listings").getString(),
                String.valueOf(snapshot.listings().size()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 2, inner,
                Text.translatable("betterloka.market.total_items").getString(),
                String.valueOf(snapshot.totalItems()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 3, inner,
                Text.translatable("betterloka.market.sellers").getString(),
                String.valueOf(snapshot.distinctSellers()), GuiTheme.TEXT);
        GuiTheme.statRow(context, this.textRenderer, textX, textY + ROW_HEIGHT * 4, inner,
                Text.translatable("betterloka.market.special_count").getString(),
                String.valueOf(specialListings().size()), GuiTheme.ACCENT);

        y += height + CARD_GAP;

        // The API publishes what is on sale, not how much currency exists; saying so beats implying
        // the figure above is the server's money supply.
        int noteHeight = CARD_PADDING * 2 + ROW_HEIGHT * 2;
        GuiTheme.panel(context, left, y, width, noteHeight);
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(Text.translatable("betterloka.market.no_supply_1").getString(), inner),
                left + CARD_PADDING, y + CARD_PADDING, GuiTheme.MUTED);
        context.drawTextWithShadow(this.textRenderer,
                this.textRenderer.trimToWidth(Text.translatable("betterloka.market.no_supply_2").getString(), inner),
                left + CARD_PADDING, y + CARD_PADDING + ROW_HEIGHT, GuiTheme.MUTED);
        return y + noteHeight;
    }

    private String sellerOf(MarketListing listing) {
        String name = listing.ownerId() == null ? null : sellers.get(listing.ownerId());
        if (name == null) {
            return "...";
        }
        return name.isEmpty() ? "?" : name;
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

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
