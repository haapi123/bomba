package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.MarketListing;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How an offer looks on screen: the item to draw, its name and lore as the game would colour them,
 * and its price written out.
 *
 * <p>Everything here is worked out once per offer and kept, since the same rows are drawn every
 * frame.
 */
final class MarketLook {
    /** How many offers' looks to keep: the whole market is about eight hundred. */
    private static final int CACHE_SIZE = 2048;

    private static final Map<String, Look> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Look> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private static final Map<String, ItemStack> ICONS = new LinkedHashMap<>();

    /** The game's own style for lore it has not been told to style otherwise. */
    private static final Style LORE_STYLE = Style.EMPTY.withColor(ChatFormatting.DARK_PURPLE).withItalic(true);

    /** What an offer is drawn with. */
    record Look(ItemStack stack, Component name, List<Component> lore, List<Component> tooltip) {
    }

    private MarketLook() {
    }

    static Look of(MarketListing listing) {
        String key = listing.id() != null ? listing.id() : listing.type() + "#" + System.identityHashCode(listing);
        Look look = CACHE.get(key);
        if (look == null) {
            look = build(listing);
            CACHE.put(key, look);
        }
        return look;
    }

    /** A plain stack of a game item, for a stall's counter. */
    static ItemStack icon(String itemId) {
        return ICONS.computeIfAbsent(itemId, id -> new ItemStack(item(id)));
    }

    private static Look build(MarketListing listing) {
        Item item = item(listing.itemId());
        ItemStack stack = new ItemStack(item);
        if (listing.potionType() != null) {
            Identifier potion = Identifier.tryParse("minecraft:" + listing.potionType());
            if (potion != null) {
                stack = BuiltInRegistries.POTION.get(potion)
                        .map(holder -> PotionContents.createItemStack(item, holder))
                        .orElse(stack);
            }
        }
        stack.setCount(Math.max(1, Math.min(99, listing.stackSize())));
        if (!listing.enchantments().isEmpty()) {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        if (listing.damage() > 0 && stack.isDamageableItem()) {
            stack.set(DataComponents.DAMAGE, Math.min(listing.damage(), stack.getMaxDamage() - 1));
        }

        Component name = component(listing.customNameJson());
        if (name == null) {
            name = Component.literal(listing.displayName());
        }
        List<Component> lore = new ArrayList<>();
        for (String line : listing.loreJson()) {
            Component decoded = component(line);
            lore.add(Component.empty().withStyle(LORE_STYLE).append(decoded == null ? Component.empty() : decoded));
        }
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(lore));
        }

        // What hovering the item shows: its name, enchantments, lore — the parts of the game's own
        // tooltip that tell one sword from another.
        List<Component> tooltip = new ArrayList<>();
        tooltip.add(name);
        for (String enchantment : listing.enchantments()) {
            tooltip.add(Component.literal(enchantment).withStyle(ChatFormatting.GRAY));
        }
        if (listing.potionType() != null) {
            tooltip.add(Component.literal(MarketListing.pretty(listing.potionType())).withStyle(ChatFormatting.BLUE));
        }
        tooltip.addAll(lore);
        return new Look(stack, name, List.copyOf(lore), List.copyOf(tooltip));
    }

    private static Item item(String itemId) {
        if (itemId == null) {
            return Items.PAPER;
        }
        Identifier id = Identifier.tryParse("minecraft:" + itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? Items.PAPER : item;
    }

    /** A text component from the game's JSON for one, or {@code null} if it does not read. */
    static Component component(String json) {
        if (json == null) {
            return null;
        }
        try {
            return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                    .result().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The seller's name, {@code ...} while it is being looked up, {@code ?} if nobody came back. */
    static String seller(MarketListing listing) {
        String name = BetterLokaClient.market().sellerNameIfKnown(listing.ownerId());
        if (name == null) {
            return "...";
        }
        return name.isEmpty() ? "?" : name;
    }

    /**
     * A price as it reads best at its size: {@code 5.06}, {@code 390}, {@code 12,000}, {@code 1.25M}.
     *
     * <p>Shards are whole, but a stack's price spread over sixty-four items is not, and rounding a
     * five-and-a-bit down to five makes two different offers look the same.
     */
    static String money(double amount) {
        if (amount >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2fM", amount / 1_000_000);
        }
        if (amount >= 100 || amount == Math.rint(amount)) {
            return String.format(Locale.ROOT, "%,.0f", amount);
        }
        if (amount >= 10) {
            return String.format(Locale.ROOT, "%.1f", amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    /** {@code Sharpness 5 · Unbreaking 3 · +2}: as many as fit in {@code shown}. */
    static String enchantSummary(List<String> enchantments, int shown) {
        if (enchantments.size() <= shown) {
            return String.join(" · ", enchantments);
        }
        return String.join(" · ", enchantments.subList(0, shown)) + " · +" + (enchantments.size() - shown);
    }

    /** The colour of a stall's awning, lightened or darkened by {@code factor} (1 leaves it). */
    static int shade(int rgb, float factor) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (factor >= 1) {
            float toward = factor - 1;
            r = (int) (r + (255 - r) * toward);
            g = (int) (g + (255 - g) * toward);
            b = (int) (b + (255 - b) * toward);
        } else {
            r = (int) (r * factor);
            g = (int) (g * factor);
            b = (int) (b * factor);
        }
        return 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }
}
