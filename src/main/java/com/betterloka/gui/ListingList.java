package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.MarketListing;
import com.betterloka.api.model.MarketSort;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrolling list of offers, each a card with the item drawn in a slot, who sells it and for how
 * much — shared by a stall and the search.
 *
 * <p>Clicking a card opens that offer's profile; hovering its item shows the item's tooltip.
 */
final class ListingList {
    private static final int GAP = 3;
    private static final int PADDING = 4;
    private static final int SLOT = 22;
    private static final int LINE = 11;

    /** How many enchantments a card spells out before counting the rest. */
    private static final int ENCHANTS_SHOWN = 3;

    private record Row(MarketListing listing, int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private final ScrollPanel scroll = new ScrollPanel();
    private final List<Row> rows = new ArrayList<>();
    private final List<String> owners = new ArrayList<>();

    private int left;

    void setViewport(int x, int y, int width, int height) {
        this.left = x;
        scroll.setViewport(x, y, width, Math.max(20, height));
    }

    void reset() {
        scroll.reset();
    }

    int top() {
        return scroll.viewportTop();
    }

    /**
     * Draws the cards, or whatever {@code empty} says when there are none.
     *
     * @param menuOpen true while a menu lies over the list, so nothing under it lights up
     */
    void render(GuiGraphicsExtractor context, Font font, List<MarketListing> listings, MarketSort sort,
                Component empty, int mouseX, int mouseY, boolean menuOpen) {
        rows.clear();
        owners.clear();
        int width = scroll.contentWidth();
        context.enableScissor(left, scroll.viewportTop(), left + width + ScrollPanel.BAR_WIDTH + 2,
                scroll.viewportBottom());
        int y = scroll.contentTop();
        if (listings.isEmpty()) {
            context.text(font, empty, left + 2, y + 4, GuiTheme.MUTED);
            y += 20;
        }
        boolean pointerInView = !menuOpen && mouseY >= scroll.viewportTop() && mouseY < scroll.viewportBottom();
        MarketListing tooltipFor = null;
        for (MarketListing listing : listings) {
            boolean enchanted = !listing.enchantments().isEmpty();
            int height = PADDING * 2 + Math.max(SLOT, LINE * (enchanted ? 3 : 2));
            if (y + height < scroll.viewportTop() || y > scroll.viewportBottom()) {
                y += height + GAP;
                continue;
            }
            Row row = new Row(listing, left, y, width, height);
            rows.add(row);
            if (listing.ownerId() != null && !owners.contains(listing.ownerId())) {
                owners.add(listing.ownerId());
            }
            boolean hovered = pointerInView && row.contains(mouseX, mouseY);
            if (drawCard(context, font, row, sort, hovered, mouseX, mouseY)) {
                tooltipFor = listing;
            }
            y += height + GAP;
        }
        context.disableScissor();
        scroll.setContentHeight(y - scroll.contentTop());
        scroll.render(context, mouseX, mouseY);

        if (tooltipFor != null) {
            context.setComponentTooltipForNextFrame(font, MarketLook.of(tooltipFor).tooltip(), mouseX, mouseY);
        }
        // Only the sellers actually on screen are looked up: a screenful is about a dozen.
        if (!owners.isEmpty()) {
            BetterLokaClient.market().requestSellerNames(owners);
        }
    }

    /** @return whether the pointer is over the item, so its tooltip is wanted. */
    private boolean drawCard(GuiGraphicsExtractor context, Font font, Row row, MarketSort sort,
                             boolean hovered, int mouseX, int mouseY) {
        MarketListing listing = row.listing();
        MarketLook.Look look = MarketLook.of(listing);
        int x = row.x();
        int y = row.y();
        int width = row.width();

        MapStyle.card(context, x, y, width, row.height(), hovered ? 0xFF8A8AA8 : 0xFF2C2C38);

        // The slot, as an inventory draws one, with the item and its count and wear in it.
        int slotX = x + PADDING;
        int slotY = y + PADDING;
        context.fill(slotX, slotY, slotX + SLOT, slotY + SLOT, 0xFF8B8B8B);
        context.fill(slotX, slotY, slotX + SLOT - 1, slotY + 1, 0xFF373737);
        context.fill(slotX, slotY, slotX + 1, slotY + SLOT - 1, 0xFF373737);
        context.fill(slotX + 1, slotY + SLOT - 1, slotX + SLOT, slotY + SLOT, 0xFFFFFFFF);
        context.fill(slotX + SLOT - 1, slotY + 1, slotX + SLOT, slotY + SLOT, 0xFFFFFFFF);
        context.item(look.stack(), slotX + 3, slotY + 3);
        context.itemDecorations(font, look.stack(), slotX + 3, slotY + 3);
        boolean overItem = hovered && mouseX >= slotX && mouseX < slotX + SLOT
                && mouseY >= slotY && mouseY < slotY + SLOT;

        // The price on the right, and what it buys under it.
        String price = MarketLook.money(listing.price());
        String terms = terms(listing);
        int rightWidth = Math.max(font.width(price), font.width(terms));
        int right = x + width - PADDING - 2;
        context.text(font, price, right - font.width(price), y + PADDING + 2, GuiTheme.GOOD);
        context.text(font, terms, right - font.width(terms), y + PADDING + 2 + LINE, GuiTheme.MUTED);

        int textX = slotX + SLOT + 6;
        int textWidth = right - rightWidth - 8 - textX;
        int lineY = y + PADDING + 2;

        int badgeWidth = 0;
        if (listing.isLokaItem()) {
            String badge = Component.translatable("betterloka.market.loka_item").getString();
            badgeWidth = font.width(badge) + 6;
        }
        drawClipped(context, font, look.name(), textX, lineY, textWidth - badgeWidth, GuiTheme.TEXT);
        if (badgeWidth > 0) {
            int nameWidth = Math.min(font.width(look.name()), textWidth - badgeWidth);
            int badgeX = textX + nameWidth + 4;
            GuiTheme.chip(context, badgeX, lineY - 2, badgeWidth, 11, GuiTheme.LIVE);
            context.text(font, Component.translatable("betterloka.market.loka_item"), badgeX + 3, lineY, GuiTheme.LIVE);
        }

        String seller = Component.translatable("betterloka.market.seller", MarketLook.seller(listing)).getString();
        String when = listing.listedAt() == null ? ""
                : "  ·  " + TimeFormat.ago(listing.listedAt().toEpochMilli());
        context.text(font, font.plainSubstrByWidth(seller + when, textWidth), textX, lineY + LINE, GuiTheme.MUTED);

        if (!listing.enchantments().isEmpty()) {
            context.text(font,
                    font.plainSubstrByWidth(MarketLook.enchantSummary(listing.enchantments(), ENCHANTS_SHOWN),
                            right - textX),
                    textX, lineY + LINE * 2, GuiTheme.ENCHANT);
        }
        return overItem;
    }

    /** {@code per 64 · 11 left}, or {@code 11 left} for single items. */
    static String terms(MarketListing listing) {
        String left = Component.translatable("betterloka.market.stock", listing.quantity()).getString();
        if (listing.stackSize() > 1) {
            return Component.translatable("betterloka.market.per_stack", listing.stackSize()).getString()
                    + " · " + left;
        }
        return left;
    }

    /** Draws a coloured name cut to {@code width}, with an ellipsis if anything was cut. */
    static void drawClipped(GuiGraphicsExtractor context, Font font, Component text, int x, int y, int width,
                            int color) {
        if (width <= 0) {
            return;
        }
        if (font.width(text) <= width) {
            context.text(font, text, x, y, color);
            return;
        }
        int dots = font.width("...");
        List<FormattedCharSequence> lines = font.split(text, Math.max(1, width - dots));
        if (lines.isEmpty()) {
            return;
        }
        FormattedCharSequence first = lines.get(0);
        context.text(font, first, x, y, color);
        context.text(font, "...", x + font.width(first), y, GuiTheme.MUTED);
    }

    /** The offer under the pointer, if a card was clicked. */
    MarketListing clicked(double mouseX, double mouseY) {
        if (mouseY < scroll.viewportTop() || mouseY >= scroll.viewportBottom()) {
            return null;
        }
        for (Row row : rows) {
            if (row.contains(mouseX, mouseY)) {
                return row.listing();
            }
        }
        return null;
    }

    /** Where the first drawn card that {@code match} accepts is, for the dev harness to aim at. */
    int[] rowWhere(java.util.function.Predicate<MarketListing> match) {
        for (Row row : rows) {
            if (match.test(row.listing())) {
                return new int[]{row.x() + row.width() / 2, row.y() + row.height() / 2};
            }
        }
        return null;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scroll.mouseScrolled(mouseX, mouseY, amount);
    }

    boolean mouseClickedScrollbar(double mouseX, double mouseY) {
        return scroll.mouseClicked(mouseX, mouseY);
    }

    boolean mouseDragged(double mouseY) {
        return scroll.mouseDragged(mouseY);
    }

    void mouseReleased() {
        scroll.mouseReleased();
    }
}
