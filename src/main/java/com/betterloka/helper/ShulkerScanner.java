package com.betterloka.helper;

import com.betterloka.BetterLokaClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loka Helper's Shulker Scanner: in every inventory and chest screen, each shulker box's slot is
 * tinted by how many splash Instant Health II potions it holds — green over fifteen, yellow fewer,
 * red none. Read from the shulker's own contents, which the game already has; nothing is sent.
 */
public final class ShulkerScanner {
    /** Counts per contents object: a shulker's contents are immutable, so one count serves until it changes. */
    private static final Map<ItemContainerContents, Integer> COUNTED = new IdentityHashMap<>();
    private static final int CACHE_LIMIT = 512;

    private ShulkerScanner() {
    }

    /** Called for every slot as a container screen draws it, before its item (AbstractContainerScreen). */
    public static void drawSlot(GuiGraphicsExtractor graphics, Slot slot) {
        if (!BetterLokaClient.config().shulkerScanner()) {
            return;
        }
        ItemStack stack = slot.getItem();
        if (!isShulker(stack)) {
            return;
        }
        int rgb = ShulkerScan.level(heals(stack)).rgb();
        int x = slot.x;
        int y = slot.y;
        graphics.fill(x, y, x + 16, y + 16, 0x70000000 | rgb);
        int edge = 0xFF000000 | rgb;
        graphics.fill(x, y, x + 16, y + 1, edge);
        graphics.fill(x, y + 15, x + 16, y + 16, edge);
        graphics.fill(x, y + 1, x + 1, y + 15, edge);
        graphics.fill(x + 15, y + 1, x + 16, y + 15, edge);
    }

    public static boolean isShulker(ItemStack stack) {
        return stack != null && !stack.isEmpty() && Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;
    }

    /** Splash Instant Health II (or stronger) in the shulker. */
    public static int heals(ItemStack shulker) {
        ItemContainerContents contents = shulker.get(DataComponents.CONTAINER);
        if (contents == null) {
            return 0;
        }
        Integer known = COUNTED.get(contents);
        if (known != null) {
            return known;
        }
        List<ShulkerScan.Stack> stacks = new ArrayList<>();
        contents.nonEmptyItemCopyStream().forEach(item -> stacks.add(
                new ShulkerScan.Stack(item.is(Items.SPLASH_POTION), instantHealthLevel(item), item.getCount())));
        int heals = ShulkerScan.heals(stacks);
        if (COUNTED.size() >= CACHE_LIMIT) {
            COUNTED.clear();
        }
        COUNTED.put(contents, heals);
        return heals;
    }

    /** The potion's Instant Health level (II is 2), or 0 without it. */
    private static int instantHealthLevel(ItemStack item) {
        PotionContents potion = item.get(DataComponents.POTION_CONTENTS);
        if (potion == null) {
            return 0;
        }
        int level = 0;
        for (MobEffectInstance effect : potion.getAllEffects()) {
            if (effect.is(MobEffects.INSTANT_HEALTH)) {
                level = Math.max(level, effect.getAmplifier() + 1);
            }
        }
        return level;
    }
}
