package com.betterloka.grind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a shrine run brought in, worked out from the inventory before and after.
 *
 * <p>The client is never told what a kill dropped or who picked it up, but it always knows what is
 * in the player's own inventory. So a run is counted as the inventory when the player walked onto
 * the shrine's ground against the inventory just after it was purged: whatever there is more of is
 * what the shrine gave. Only gains count — potions drunk and blocks placed during the fight are not
 * losses to the shrine.
 *
 * <p>Items are counted by id, and by name too when they carry one of their own, so a server's named
 * item made from, say, paper is not added to the player's plain paper.
 *
 * <p>Plain Java with no Minecraft in it, so it is tested outside the game.
 */
public final class ShrineLoot {
    /** Brewing ingredients, which are what shrines are farmed for, in the order a list shows them. */
    public static final List<String> BREWING = List.of(
            "minecraft:nether_wart",
            "minecraft:ghast_tear",
            "minecraft:blaze_rod",
            "minecraft:blaze_powder",
            "minecraft:magma_cream",
            "minecraft:glowstone_dust",
            "minecraft:glowstone",
            "minecraft:gunpowder",
            "minecraft:redstone",
            "minecraft:fermented_spider_eye",
            "minecraft:spider_eye",
            "minecraft:sugar",
            "minecraft:glistering_melon_slice",
            "minecraft:golden_carrot",
            "minecraft:rabbit_foot",
            "minecraft:pufferfish",
            "minecraft:phantom_membrane",
            "minecraft:turtle_scute",
            "minecraft:breeze_rod",
            "minecraft:dragon_breath");

    /**
     * Everything one shrine has given over all its runs.
     *
     * @param runs      how many runs were counted
     * @param lastRunAt when the last one ended, as a wall-clock time
     * @param totals    every item gained, summed over the runs
     * @param lastRun   what the last run alone gave
     */
    public record Stats(int runs, long lastRunAt, Map<String, Long> totals, Map<String, Integer> lastRun) {
        public static final Stats EMPTY = new Stats(0, 0, Map.of(), Map.of());

        /** These stats with one more run added. */
        public Stats plus(Map<String, Integer> gained, long at) {
            Map<String, Long> sum = new HashMap<>(totals == null ? Map.of() : totals);
            gained.forEach((key, count) -> sum.merge(key, (long) count, Long::sum));
            return new Stats(runs + 1, at, Map.copyOf(sum), Map.copyOf(gained));
        }
    }

    private ShrineLoot() {
    }

    /** The key an item is counted under: its id, and its own name after a bar when it has one. */
    public static String key(String itemId, String customName) {
        return customName == null || customName.isBlank() ? itemId : itemId + "|" + customName;
    }

    public static String itemId(String key) {
        int bar = key.indexOf('|');
        return bar < 0 ? key : key.substring(0, bar);
    }

    /** The item's own name, or {@code null} for a plain one. */
    public static String customName(String key) {
        int bar = key.indexOf('|');
        return bar < 0 ? null : key.substring(bar + 1);
    }

    /** Whether an item is a plain brewing ingredient. A named one is the server's own item. */
    public static boolean brewing(String key) {
        return customName(key) == null && BREWING.contains(key);
    }

    /** What there is more of afterwards, item by item. */
    public static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> gained = new HashMap<>();
        after.forEach((key, count) -> {
            int more = count - before.getOrDefault(key, 0);
            if (more > 0) {
                gained.put(key, more);
            }
        });
        return gained;
    }

    /**
     * Totals in the order a list shows them: brewing ingredients first, in {@link #BREWING}'s order,
     * then everything else, most first.
     */
    public static List<Map.Entry<String, Long>> ordered(Map<String, Long> totals) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, Long> e) -> brewing(e.getKey())
                        ? BREWING.indexOf(e.getKey()) : BREWING.size())
                .thenComparing(Map.Entry<String, Long>::getValue, Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        return entries;
    }
}
