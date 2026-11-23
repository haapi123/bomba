package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.MarketSweep;
import com.betterloka.api.model.MarketListing;
import com.betterloka.api.model.MarketSort;
import com.betterloka.market.MarketCategory;
import com.betterloka.market.MarketIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One stall of the market square: everything it has on offer, newest first unless the Filter says
 * otherwise, with a field to narrow it down by name.
 */
public class MarketStallScreen extends Screen implements LokaStyled {
    private static final int MAX_CONTENT_WIDTH = 460;
    private static final int AWNING_HEIGHT = 12;


    private final Screen parent;
    private final LokaWindow window;
    private final MarketCategory category;
    private final ListingList list = new ListingList();
    private final FilterMenu filter = new FilterMenu(MarketSort.NEWEST, sort -> list.reset());
    private EditBox narrowField;

    private MarketSweep sweep;
    private int shownVersion = -1;
    private MarketSort shownSort;
    private String shownNarrow;
    private MarketIndex.Stall stall;
    private List<MarketListing> shown = List.of();

    public MarketStallScreen(Screen parent, MarketCategory category) {
        super(Component.translatable(category.key()));
        this.parent = parent;
        this.window = new LokaWindow(this.title, Component.translatable("betterloka.market.stall_subtitle"),
                this::onClose).content(MAX_CONTENT_WIDTH, 420);
        this.category = category;
    }

    private int contentWidth() {
        return window.innerWidth();
    }

    private int contentLeft() {
        return window.left();
    }

    /** The awning hangs from the header, right across the window. */
    private int awningTop() {
        return window.y() + LokaWindow.HEADER + 1;
    }

    private int barY() {
        return awningTop() + AWNING_HEIGHT + 6;
    }

    @Override
    protected void init() {
        window.layout(this.width, this.height);
        int left = contentLeft();
        int width = contentWidth();
        BetterLokaClient.market().sweep(false);
        sweep = BetterLokaClient.market().shown();
        int filterWidth = FilterMenu.preferredWidth(this.font);
        filter.place(left + width - filterWidth, barY(), filterWidth);

        String previous = narrowField != null ? narrowField.getValue() : "";
        narrowField = new EditBox(this.font, left, barY(), Math.min(180, width - filterWidth - 8), FilterMenu.HEIGHT,
                Component.translatable("betterloka.market.narrow"));
        narrowField.setMaxLength(40);
        narrowField.setHint(Component.translatable("betterloka.market.narrow_hint").withStyle(ChatFormatting.DARK_GRAY));
        narrowField.setValue(previous);
        narrowField.setResponder(text -> list.reset());
        addRenderableWidget(narrowField);

        list.setViewport(left, barY() + FilterMenu.HEIGHT + 6, width, window.bottom() - (barY() + FilterMenu.HEIGHT + 6));
    }

    /** What the stall shows, worked out again only when the market, the order or the filter changes. */
    private List<MarketListing> shown() {
        MarketSweep current = BetterLokaClient.market().shown();
        if (current != null && current != sweep) {
            sweep = current;
            shownVersion = -1;
        }
        String narrow = narrowField.getValue().trim().toLowerCase(Locale.ROOT);
        if (sweep != null && (sweep.version() != shownVersion || filter.sort() != shownSort
                || !narrow.equals(shownNarrow))) {
            shownVersion = sweep.version();
            shownSort = filter.sort();
            shownNarrow = narrow;
            stall = MarketIndex.of(sweep.listings()).stall(category);
            List<MarketListing> matching = new ArrayList<>();
            String[] words = narrow.isEmpty() ? new String[0] : narrow.split("\\s+");
            for (MarketListing listing : stall.listings()) {
                String text = listing.searchText();
                boolean all = true;
                for (String word : words) {
                    if (!text.contains(word)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    matching.add(listing);
                }
            }
            shown = filter.sort().sort(matching);
        }
        return shown;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (window.mouseClicked(click.x(), click.y())) {
            return true;
        }
        if (filter.mouseClicked(click.x(), click.y())) {
            return true;
        }
        if (list.mouseClickedScrollbar(click.x(), click.y())) {
            return true;
        }
        if (click.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            MarketListing listing = list.clicked(click.x(), click.y());
            if (listing != null) {
                this.minecraft.gui.setScreen(new MarketItemScreen(this, listing));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return list.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return list.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        list.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        window.draw(context, this.font, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        List<MarketListing> listings = shown();
        super.extractRenderState(context, mouseX, mouseY, delta);
        int left = contentLeft();
        int width = contentWidth();

        drawAwning(context, window.x() + 1, window.width() - 2);
        // The item the stall is known for, in the header beside its name.
        int iconX = window.headerRight() - 20;
        int iconY = window.y() + (LokaWindow.HEADER - 20) / 2;
        HelperUi.round(context, iconX, iconY, 20, 20, 4, HelperUi.mix(HelperUi.ICON_TILE, 0xFF000000 | category.color(), 0.25f));
        context.item(MarketLook.icon(stall == null || stall.showcase().isEmpty() ? category.icon()
                : stall.showcase().get(0)), iconX + 2, iconY + 2);

        boolean loading = sweep != null && sweep.running();
        String count = stall == null ? "..." : Component.translatable("betterloka.market.stall_count",
                listings.size(), stall.types()).getString() + (loading ? " ..." : "");
        int countX = narrowField.getX() + narrowField.getWidth() + 8;
        if (countX + this.font.width(count) < filter.x() - 6) {
            context.text(this.font, count, countX, barY() + 5, GuiTheme.MUTED);
        }

        Component empty = loading
                ? Component.translatable("betterloka.market.fetching", sweep.pagesDone(), Math.max(1, sweep.pagesTotal()))
                : narrowField.getValue().isBlank()
                ? Component.translatable("betterloka.market.stall_empty")
                : Component.translatable("betterloka.market.narrow_none", narrowField.getValue().trim());
        list.render(context, this.font, listings, filter.sort(), empty, mouseX, mouseY, filter.isOpen());
        filter.render(context, this.font, mouseX, mouseY);
        filter.renderMenu(context, this.font, mouseX, mouseY);
    }

    /** The stall's striped awning across the top, so a stall looks like the one clicked. */
    private void drawAwning(GuiGraphicsExtractor context, int left, int width) {
        int stripes = Math.max(8, (width / 14) & ~1);
        float stripeW = width / (float) stripes;
        int color = 0xFF000000 | category.color();
        for (int s = 0; s < stripes; s++) {
            int x0 = left + Math.round(s * stripeW);
            int x1 = left + Math.round((s + 1) * stripeW);
            int stripe = s % 2 == 0 ? color : 0xFFF2EEE4;
            int top = awningTop();
            context.fill(x0, top, x1, top + AWNING_HEIGHT - 2, stripe);
            context.fill(x0 + 1, top + AWNING_HEIGHT - 2, x1 - 1, top + AWNING_HEIGHT, stripe);
        }
        context.fill(left, awningTop(), left + width, awningTop() + 1, MarketLook.shade(category.color(), 0.5f));
    }

    // --- for the dev harness ---

    public int[] devRowWhere(java.util.function.Predicate<MarketListing> match) {
        return list.rowWhere(match);
    }

    public int[] devFilterButton() {
        return filter.centre();
    }

    public int[] devFilterOption(int i) {
        return filter.optionCentre(i);
    }

    public String devState() {
        return "stall=" + category + " shown=" + shown.size() + " sort=" + filter.sort()
                + (sweep == null ? "" : " sweep=" + sweep.pagesDone() + "/" + sweep.pagesTotal());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
