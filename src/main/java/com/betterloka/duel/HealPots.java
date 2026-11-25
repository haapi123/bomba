package com.betterloka.duel;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** What counts as a heal pot in a duel's numbers: a splash Instant Health II, or stronger. */
public final class HealPots {
    private HealPots() {
    }

    /** Whether this is a splash Instant Health II (or stronger): a heal pot. */
    public static boolean isHealPot(ItemStack item) {
        return item != null && item.is(Items.SPLASH_POTION) && instantHealthLevel(item) >= 2;
    }

    /** The potion's Instant Health level (II is 2), or 0 without it. */
    public static int instantHealthLevel(ItemStack item) {
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
