package com.betterloka.gui;

import com.betterloka.BetterLoka;
import com.betterloka.BetterLokaClient;
import com.betterloka.api.MarketApi;
import com.betterloka.api.model.MarketListing;
import com.betterloka.api.model.MarketSort;
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
import java.util.List;
import java.util.Locale;

/**
 * Looks one item type up on Loka's market and lists its offers.
 *
 * <p>Search is the whole screen. It used to have two more views — Deals swept the market for
 * listings under the going rate, and Special picked out named gear — and both are gone, along with
 * the fifty-eight-request sweep they needed. Opening the Market now costs one request.
 */
public class LokaMarketScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 400;
    private static final int ROW_HEIGHT = 10;
    private static final int CARD_PADDING = 5;
    private static final int CARD_GAP = 4;

    private static final int SORT_ROW_Y = 26;
    private static final int SORT_ROW_HEIGHT = 16;
    private static final int SEARCH_ROW_Y = SORT_ROW_Y + SORT_ROW_HEIGHT + 4;
    private static final int SEARCH_ROW_HEIGHT = 18;
    private static final int SEARCH_BUTTON_WIDTH = 54;
    private static final int VIEWPORT_TOP = SEARCH_ROW_Y + SEARCH_ROW_HEIGHT + 6;

    /** Gap between the four sort buttons. */
    private static final int SORT_GAP = 3;

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

    private TextFieldWidget queryField;
    private ButtonWidget searchButton;

    /**
     * The search and its suggestions hold a slot each, so a late answer to one cannot be written
     * into the other, and one that no longer matches the field is dropped before it is applied.
     */
    private final AsyncSlot<Found> searchSlot = new AsyncSlot<>("market search", this::onClientThread);
    private final AsyncSlot<List<String>> suggestSlot =
            new AsyncSlot<>("market suggestions", this::onClientThread);

    private final Debounce suggestDebounce = new Debounce(DEBOUNCE_MILLIS, DEDUPE_MILLIS);

    private MarketSort sort = MarketSort.CHEAPEST;
    /** Ordered once per search or change of order, rather than on every frame. */
    private List<MarketListing> ordered = List.of();
    private Found orderedFrom;
    private MarketSort orderedBy;

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

        // Four buttons rather than one that cycles: any order is one press away, and the one in
        // force is the one picked out in yellow.
        MarketSort[] orders = MarketSort.values();
        int buttonWidth = (width - SORT_GAP * (orders.length - 1)) / orders.length;
        int x = left;
        for (int i = 0; i < orders.length; i++) {
            MarketSort target = orders[i];
            int thisWidth = i == orders.length - 1 ? left + width - x : buttonWidth;
            addDrawableChild(ButtonWidget.builder(sortLabel(target), button -> selectSort(target))
                    .dimensions(x, SORT_ROW_Y, thisWidth, SORT_ROW_HEIGHT).build());
            x += buttonWidth + SORT_GAP;
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

        setInitialFocus(queryField);
    }

    private Text sortLabel(MarketSort value) {
        Text label = Text.translatable(value.key());
        return value == sort ? label.copy().formatted(Formatting.YELLOW) : label;
    }

    /**
     * Changes the order.
     *
     * <p>Reorders what is already loaded rather than searching again: the offers are in hand, and
     * the order they go in is arithmetic.
     */
    private void selectSort(MarketSort target) {
        if (sort == target) {
            return;
        }
        sort = target;
        scrollPanel.reset();
        clearAndInit();
    }

    private void search() {
        String query = queryField.getText().trim();
        if (query.isEmpty()) {
            return;
        }
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

    /** The offers in the chosen order, worked out once per search or change of order. */
    private List<MarketListing> ordered() {
        Found found = searchSlot.value();
        if (found == null) {
            return List.of();
        }
        if (orderedFrom != found || orderedBy != sort) {
            long start = System.nanoTime();
            ordered = sort.sort(found.listings());
            orderedFrom = found;
            orderedBy = sort;
            timing("sort " + sort, ordered.size(), start);
        }
        return ordered;
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

        if (searchSlot.loading()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.searching"), left, y + 4, GuiTheme.MUTED);
            used = 20;
        } else if (searchSlot.failed()) {
            // A failed search says so and offers the way out, rather than sitting on "loading" for
            // a request that is never coming back.
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.unreachable").formatted(Formatting.RED),
                    left, y + 4, GuiTheme.BAD);
            context.drawTextWithShadow(this.textRenderer,
                    Text.translatable("betterloka.market.retry_search"),
                    left, y + 4 + ROW_HEIGHT + 2, GuiTheme.MUTED);
            used = 20 + ROW_HEIGHT;
        } else {
            used = renderSearch(context, left, y, cardWidth) - y;
        }
        context.disableScissor();

        scrollPanel.setContentHeight(used);
        scrollPanel.render(context, mouseX, mouseY);

        // Drawn after the content so the list sits over it rather than under.
        renderSuggestions(context, left, width);

        // Only the rows that were actually drawn: a screenful is about fifteen sellers.
        if (!visibleOwners.isEmpty()) {
            BetterLokaClient.market().requestSellerNames(visibleOwners);
        }
        timing("render", used, frameStart);
    }

    /** The offers, or why there are none. */
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
        return renderListings(context, left, y, width, ordered(), found.type());
    }

    /** Whether a card at this y is inside the viewport, so off-screen rows cost nothing to skip. */
    private boolean visible(int y, int height) {
        return y + height >= scrollPanel.viewportTop() && y <= scrollPanel.viewportBottom();
    }

    /**
     * Type names matching what has been typed so far.
     *
     * <p>Held back until typing stops, so "Diamond Sword" is one lookup rather than thirteen.
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

    /**
     * What the field currently offers, if anything landed.
     *
     * <p>A single suggestion that is already what the field says is not a suggestion, and the list
     * floats over the results — so it would cover a row of offers to tell the reader nothing.
     */
    private List<String> suggestions() {
        List<String> found = suggestSlot.valueOr(List.of());
        if (found.size() == 1
                && found.get(0).equalsIgnoreCase(queryField.getText().trim().replace(' ', '_'))) {
            return List.of();
        }
        return found;
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
        // Opaque, because the list floats over whatever is drawing underneath it.
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
    private int renderListings(DrawContext context, int left, int y, int width,
                               List<MarketListing> listings, String type) {
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.market.offers", listings.get(0).prettyType(),
                        listings.size(), Text.translatable(sort.orderKey())),
                left, y + 2, GuiTheme.MUTED);
        y += ROW_HEIGHT + 3;

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
            // When the order is by date, the date is what the reader is comparing, so it takes the
            // second line; otherwise that line is what the offer works out at each.
            String second = sort == MarketSort.NEWEST || sort == MarketSort.OLDEST
                    ? Text.translatable("betterloka.market.listed",
                            TimeFormat.ago(listedAtMillis(listing))).getString()
                    : Text.translatable("betterloka.market.stock", listing.quantity()).getString()
                            + "  ·  " + money(listing.pricePerUnit())
                            + Text.translatable("betterloka.market.each").getString();

            int rightWidth = Math.max(this.textRenderer.getWidth(price), this.textRenderer.getWidth(second)) + COLUMN_GAP;
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
            context.drawTextWithShadow(this.textRenderer, second,
                    left + width - CARD_PADDING - this.textRenderer.getWidth(second), textY + ROW_HEIGHT,
                    GuiTheme.MUTED);

            y += height + CARD_GAP;
        }
        return y;
    }

    /** @return when the offer went up, or 0 if its id carries no date. */
    private static long listedAtMillis(MarketListing listing) {
        return listing.listedAt() == null ? 0 : listing.listedAt().toEpochMilli();
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
     * and this is where they stop.
     */
    @Override
    public void removed() {
        searchSlot.cancel();
        suggestSlot.cancel();
        super.removed();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
