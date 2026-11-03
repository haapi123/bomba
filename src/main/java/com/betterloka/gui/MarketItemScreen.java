package com.betterloka.gui;

import com.betterloka.api.model.MarketListing;
import com.betterloka.market.MarketCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One offer up close: the item large on a stand, turning slowly, and everything known about it —
 * its name and lore as the game writes them, its enchantments, who is selling it and since when,
 * and what it costs a unit, an item and all told.
 */
public class MarketItemScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 440;
    private static final int TOP = 26;
    private static final int STAND_WIDTH = 120;
    private static final int ITEM_SCALE = 4;
    private static final int LINE = 11;

    /** One label/value line of the details. */
    private record Detail(Component label, Component value, int color) {
    }

    private final Screen parent;
    private final MarketListing listing;
    private final ScrollPanel scroll = new ScrollPanel();
    private final long openedAt = System.currentTimeMillis();
    private long copiedAt;

    public MarketItemScreen(Screen parent, MarketListing listing) {
        super(Component.translatable("betterloka.market.profile"));
        this.parent = parent;
        this.listing = listing;
    }

    private int contentWidth() {
        return Math.min(this.width - 24, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    private int bottom() {
        return this.height - 52;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int detailsX = left + STAND_WIDTH + 10;
        scroll.setViewport(detailsX, TOP, left + width - detailsX, bottom() - TOP);

        int buttonsY = this.height - 47;
        int third = (width - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("betterloka.market.other_offers"),
                        button -> this.minecraft.gui.setScreen(new LokaMarketScreen(this, listing.type())))
                .bounds(left, buttonsY, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("betterloka.market.copy_seller"),
                        button -> copySeller())
                .bounds(left + third + 4, buttonsY, third, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("betterloka.market.copy_type"),
                        button -> copy(listing.type()))
                .bounds(left + (third + 4) * 2, buttonsY, width - (third + 4) * 2, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 25, 200, 20).build());
    }

    private void copySeller() {
        String seller = MarketLook.seller(listing);
        if (!"...".equals(seller) && !"?".equals(seller)) {
            copy(seller);
        }
    }

    private void copy(String text) {
        this.minecraft.keyboardHandler.setClipboard(text);
        copiedAt = System.currentTimeMillis();
    }

    private List<Detail> details() {
        List<Detail> lines = new ArrayList<>();
        int stack = listing.stackSize();
        lines.add(new Detail(label("price"), Component.literal(MarketLook.money(listing.price()))
                .append(Component.translatable(stack > 1 ? "betterloka.market.profile.per_stack" : "betterloka.market.profile.per_item",
                        stack).withStyle(ChatFormatting.GRAY)), GuiTheme.GOOD));
        if (stack > 1) {
            lines.add(new Detail(label("each"), Component.literal(MarketLook.money(listing.itemPrice())), GuiTheme.GOOD));
        }
        lines.add(new Detail(label("stock"), Component.translatable(stack > 1
                ? "betterloka.market.profile.stock_stacks" : "betterloka.market.profile.stock_items",
                listing.quantity(), listing.itemCount()), GuiTheme.TEXT));
        lines.add(new Detail(label("value"), Component.literal(MarketLook.money(listing.stockValue())), GuiTheme.MUTED));
        lines.add(new Detail(label("seller"), Component.literal(MarketLook.seller(listing)), GuiTheme.ACCENT));
        if (listing.listedAt() != null) {
            lines.add(new Detail(label("listed"), Component.literal(TimeFormat.dateTime(listing.listedAt())
                    + "  (" + TimeFormat.ago(listing.listedAt().toEpochMilli()) + ")"), GuiTheme.TEXT));
        }
        MarketCategory category = listing.category();
        lines.add(new Detail(label("stall"), Component.translatable(category.key()),
                0xFF000000 | MarketLook.shade(category.color(), 1.3f)));
        Component kind = Component.literal(MarketListing.pretty(listing.material() != null ? listing.material() : listing.type()));
        if (listing.isLokaItem()) {
            kind = kind.copy().append(Component.translatable("betterloka.market.profile.loka_item", listing.prettyType())
                    .withStyle(ChatFormatting.GOLD));
        }
        lines.add(new Detail(label("item"), kind, GuiTheme.TEXT));
        ItemStack stackLook = MarketLook.of(listing).stack();
        if (stackLook.isDamageableItem()) {
            int max = stackLook.getMaxDamage();
            int left = Math.max(0, max - listing.damage());
            lines.add(new Detail(label("durability"), Component.literal(left + " / " + max),
                    left == max ? GuiTheme.GOOD : left > max / 4 ? GuiTheme.LIVE : GuiTheme.BAD));
        }
        if (listing.potionType() != null) {
            lines.add(new Detail(label("effect"), Component.literal(MarketListing.pretty(listing.potionType())),
                    0xFF6FA8FF));
        }
        return lines;
    }

    private static Component label(String key) {
        return Component.translatable("betterloka.market.profile." + key);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scroll.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return scroll.mouseClicked(click.x(), click.y()) || super.mouseClicked(click, doubled);
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
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        MarketLook.Look look = MarketLook.of(listing);
        int left = contentLeft();
        int width = contentWidth();

        // The item's own name, coloured as the game colours it.
        int nameWidth = this.font.width(look.name());
        if (nameWidth <= width) {
            context.text(this.font, look.name(), this.width / 2 - nameWidth / 2, 9, GuiTheme.TEXT);
        } else {
            ListingList.drawClipped(context, this.font, look.name(), left, 9, width, GuiTheme.TEXT);
        }

        drawStand(context, look, left, mouseX, mouseY);
        drawDetails(context, look, mouseX, mouseY);

        if (System.currentTimeMillis() - copiedAt < MapStyle.COPIED_NOTICE_MILLIS) {
            context.centeredText(this.font, Component.translatable("betterloka.market.copied"),
                    this.width / 2, this.height - 58, GuiTheme.GOOD);
        }
    }

    /**
     * The item on its stand: four times its size, bobbing gently and turning on the spot, with the
     * stall's colour glowing behind it.
     */
    private void drawStand(GuiGraphicsExtractor context, MarketLook.Look look, int left, int mouseX, int mouseY) {
        int standTop = TOP;
        int standBottom = bottom();
        int color = listing.category().color();
        MapStyle.card(context, left, standTop, STAND_WIDTH, standBottom - standTop, 0xFF2C2C38);

        int size = 16 * ITEM_SCALE;
        int centreX = left + STAND_WIDTH / 2;
        int centreY = standTop + 14 + size / 2;
        // A soft round glow in the stall's colour, stronger towards the middle.
        for (int ring = 0; ring < 5; ring++) {
            int radius = size / 2 + 20 - ring * 5;
            disc(context, centreX, centreY, radius, (0x10 + ring * 0x06) << 24 | (color & 0xFFFFFF));
        }
        // The stand it sits on.
        int plinthY = centreY + size / 2 + 6;
        context.fill(centreX - 28, plinthY, centreX + 28, plinthY + 4, 0xFF5A4630);
        context.fill(centreX - 24, plinthY + 4, centreX + 24, plinthY + 10, 0xFF3C2E1E);
        context.fill(centreX - 28, plinthY, centreX + 28, plinthY + 1, 0xFF8A6A44);

        // Turning on the spot: the item narrows and widens as if seen spinning, and bobs a little.
        double seconds = (System.currentTimeMillis() - openedAt) / 1000.0;
        float turn = (float) Math.cos(seconds * 1.4);
        float squash = Math.max(0.12f, Math.abs(turn));
        float bob = (float) Math.sin(seconds * 2.0) * 2f;
        context.pose().pushMatrix();
        context.pose().translate(centreX, centreY + bob);
        context.pose().scale(ITEM_SCALE * squash, ITEM_SCALE);
        context.item(look.stack(), -8, -8);
        context.pose().popMatrix();

        // The count, as the slot would show it.
        int textY = plinthY + 16;
        if (listing.stackSize() > 1) {
            context.centeredText(this.font, "x" + listing.stackSize(), centreX, textY, GuiTheme.TEXT);
            textY += LINE;
        }
        context.centeredText(this.font, Component.translatable(listing.category().key()), centreX, textY,
                0xFF000000 | MarketLook.shade(color, 1.3f));
        if (listing.isSpecial()) {
            textY += LINE;
            context.centeredText(this.font, Component.translatable("betterloka.market.profile.named"), centreX,
                    textY, GuiTheme.LIVE);
        }

        // A price tag at the foot of the stand, as a stall would hang one.
        int tagHeight = 34;
        int tagY = standBottom - tagHeight - 6;
        if (tagY > textY + LINE + 4) {
            int tagX = left + 8;
            int tagW = STAND_WIDTH - 16;
            MapStyle.card(context, tagX, tagY, tagW, tagHeight, 0xFF000000 | MarketLook.shade(color, 0.9f));
            String price = MarketLook.money(listing.price());
            int priceScale = this.font.width(price) * 2 <= tagW - 8 ? 2 : 1;
            context.pose().pushMatrix();
            context.pose().translate(centreX - this.font.width(price) * priceScale / 2f, tagY + 6);
            context.pose().scale(priceScale, priceScale);
            context.text(this.font, price, 0, 0, GuiTheme.GOOD);
            context.pose().popMatrix();
            Component per = Component.translatable(listing.stackSize() > 1
                    ? "betterloka.market.per_stack" : "betterloka.market.profile.tag_each", listing.stackSize());
            context.centeredText(this.font, per, centreX, tagY + 8 + 8 * priceScale, GuiTheme.MUTED);
        }

        if (mouseX >= centreX - size / 2 && mouseX < centreX + size / 2
                && mouseY >= centreY - size / 2 && mouseY < centreY + size / 2) {
            context.setComponentTooltipForNextFrame(this.font, look.tooltip(), mouseX, mouseY);
        }
    }

    /** A filled circle, a row at a time. */
    private static void disc(GuiGraphicsExtractor context, int centreX, int centreY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = (int) Math.round(Math.sqrt((double) radius * radius - (double) dy * dy));
            context.fill(centreX - half, centreY + dy, centreX + half, centreY + dy + 1, color);
        }
    }

    /** The facts, then the enchantments, then the lore: a scrolling column right of the stand. */
    private void drawDetails(GuiGraphicsExtractor context, MarketLook.Look look, int mouseX, int mouseY) {
        int x = contentLeft() + STAND_WIDTH + 10;
        int width = scroll.contentWidth();
        context.enableScissor(x, TOP, x + width + ScrollPanel.BAR_WIDTH + 2, bottom());
        int y = scroll.contentTop();

        List<Detail> details = details();
        MapStyle.card(context, x, y, width, details.size() * LINE + 10, 0xFF2C2C38);
        int labelWidth = 0;
        for (Detail detail : details) {
            labelWidth = Math.max(labelWidth, this.font.width(detail.label()));
        }
        int rowY = y + 6;
        for (Detail detail : details) {
            context.text(this.font, detail.label(), x + 6, rowY, GuiTheme.MUTED);
            ListingList.drawClipped(context, this.font, detail.value(), x + 12 + labelWidth, rowY,
                    width - labelWidth - 18, detail.color());
            rowY += LINE;
        }
        y = rowY + 10;

        if (!listing.enchantments().isEmpty()) {
            y = section(context, x, y, width, Component.translatable("betterloka.market.profile.enchantments"));
            for (String enchantment : listing.enchantments()) {
                context.text(this.font, enchantment, x + 8, y, GuiTheme.ENCHANT);
                y += LINE;
            }
            y += 6;
        }

        if (!look.lore().isEmpty()) {
            y = section(context, x, y, width, Component.translatable("betterloka.market.profile.lore"));
            for (Component line : look.lore()) {
                List<FormattedCharSequence> wrapped = this.font.split(line, width - 12);
                for (FormattedCharSequence part : wrapped) {
                    context.text(this.font, part, x + 8, y, 0xFFAA00AA);
                    y += LINE - 1;
                }
                if (wrapped.isEmpty()) {
                    y += LINE - 1;
                }
            }
            y += 6;
        }
        context.disableScissor();
        scroll.setContentHeight(y - scroll.contentTop());
        scroll.render(context, mouseX, mouseY);
    }

    private int section(GuiGraphicsExtractor context, int x, int y, int width, Component title) {
        context.text(this.font, title.copy().withStyle(ChatFormatting.BOLD), x, y, GuiTheme.TEXT);
        context.fill(x, y + 10, x + width, y + 11, 0xFF33333F);
        return y + 15;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
