package com.betterloka.grind;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShrineLootTest {
    @Test
    void onlyWhatThereIsMoreOfCounts() {
        Map<String, Integer> before = Map.of("minecraft:gunpowder", 10, "minecraft:golden_apple", 4,
                "minecraft:ghast_tear", 1);
        Map<String, Integer> after = Map.of("minecraft:gunpowder", 16, "minecraft:golden_apple", 2,
                "minecraft:ghast_tear", 1, "minecraft:blaze_rod", 3);
        assertEquals(Map.of("minecraft:gunpowder", 6, "minecraft:blaze_rod", 3),
                ShrineLoot.gained(before, after),
                "apples eaten are not a loss to the shrine, and an unchanged stack is no gain");
    }

    @Test
    void aNamedItemIsKeptApartFromThePlainOne() {
        String named = ShrineLoot.key("minecraft:paper", "Shrine Scroll");
        assertEquals("minecraft:paper|Shrine Scroll", named);
        assertEquals("minecraft:paper", ShrineLoot.itemId(named));
        assertEquals("Shrine Scroll", ShrineLoot.customName(named));
        assertEquals("minecraft:ghast_tear", ShrineLoot.key("minecraft:ghast_tear", null));
        assertNull(ShrineLoot.customName("minecraft:ghast_tear"));
    }

    @Test
    void brewingIngredientsComeFirstInTheirOwnOrder() {
        assertTrue(ShrineLoot.brewing("minecraft:ghast_tear"));
        assertTrue(ShrineLoot.brewing("minecraft:nether_wart"));
        assertFalse(ShrineLoot.brewing("minecraft:ghast_tear|Cursed Tear"), "a named item is the server's own");
        assertFalse(ShrineLoot.brewing("minecraft:cobblestone"));

        List<String> order = ShrineLoot.ordered(Map.of(
                "minecraft:cobblestone", 64L,
                "minecraft:gunpowder", 5L,
                "minecraft:ghast_tear", 2L,
                "minecraft:paper|Shrine Scroll", 1L,
                "minecraft:nether_wart", 9L)).stream().map(Map.Entry::getKey).toList();
        assertEquals(List.of("minecraft:nether_wart", "minecraft:ghast_tear", "minecraft:gunpowder",
                "minecraft:cobblestone", "minecraft:paper|Shrine Scroll"), order);
    }

    @Test
    void statsAddRunByRun() {
        ShrineLoot.Stats stats = ShrineLoot.Stats.EMPTY
                .plus(Map.of("minecraft:magma_cream", 4), 10)
                .plus(Map.of("minecraft:magma_cream", 3, "minecraft:gold_nugget", 2), 20);
        assertEquals(2, stats.runs());
        assertEquals(7L, stats.totals().get("minecraft:magma_cream"));
        assertEquals(2L, stats.totals().get("minecraft:gold_nugget"));
        assertEquals(20, stats.lastRunAt());
        assertEquals(3, stats.lastRun().get("minecraft:magma_cream"));
    }
}
