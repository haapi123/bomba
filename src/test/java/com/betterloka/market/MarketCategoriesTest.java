package com.betterloka.market;

import com.betterloka.api.model.MarketListing;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which stall each item goes on.
 *
 * <p>{@code vanilla_items.txt} is every item in the game, from the 26.3 client's own item
 * definitions; {@code market_sample.json} is one real offer of each type that was on Loka's market,
 * sellers' ids replaced.
 */
public class MarketCategoriesTest {
    private static List<String> vanillaItems() throws IOException {
        try (InputStream in = MarketCategoriesTest.class.getResourceAsStream("/vanilla_items.txt")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank()).toList();
        }
    }

    /** One real offer of each type that was on the market. */
    public static List<MarketListing> sample() throws IOException {
        try (InputStream in = MarketCategoriesTest.class.getResourceAsStream("/market_sample.json")) {
            assertNotNull(in);
            JsonArray array = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonArray();
            List<MarketListing> listings = new ArrayList<>();
            for (JsonElement element : array) {
                listings.add(MarketListing.fromJson(element.getAsJsonObject()));
            }
            return listings;
        }
    }

    private static void assertStall(MarketCategory expected, String... ids) {
        for (String id : ids) {
            assertEquals(expected, MarketCategories.classifyId(id), id);
        }
    }

    @Test
    void gearGoesByWhatItIs() {
        assertStall(MarketCategory.WEAPONS, "diamond_sword", "netherite_spear", "bow", "crossbow", "trident",
                "mace", "tipped_arrow", "spectral_arrow", "arrow");
        assertStall(MarketCategory.ARMOR, "diamond_boots", "netherite_helmet", "elytra", "shield",
                "turtle_helmet", "coast_armor_trim_smithing_template", "diamond_horse_armor", "totem_of_undying");
        assertStall(MarketCategory.TOOLS, "diamond_pickaxe", "netherite_axe", "diamond_shovel", "iron_hoe",
                "fishing_rod", "shears", "flint_and_steel", "water_bucket", "oak_boat", "firework_rocket");
    }

    @Test
    void theColouredBlocksAreEveryDyedBlockButNotWhatMerelyHasAColourInItsName() {
        assertStall(MarketCategory.COLORED, "lime_wool", "blue_wool", "red_concrete", "black_concrete_powder",
                "orange_terracotta", "terracotta", "cyan_glazed_terracotta", "purple_stained_glass",
                "white_bed", "black_dye", "yellow_candle", "pink_carpet");
        assertEquals(MarketCategory.NATURE, MarketCategories.classifyId("red_mushroom"));
        assertEquals(MarketCategory.NATURE, MarketCategories.classifyId("red_sand"));
        assertEquals(MarketCategory.NETHER, MarketCategories.classifyId("red_nether_bricks"));
        assertEquals(MarketCategory.BUILDING, MarketCategories.classifyId("red_sandstone"));
    }

    @Test
    void theEndAndTheNetherKeepTheirOwn() {
        assertStall(MarketCategory.END, "end_stone", "purpur_block", "chorus_fruit", "shulker_shell",
                "shulker_box", "purple_shulker_box", "dragon_egg", "dragon_head", "end_crystal", "ender_pearl",
                "ender_chest", "ender_eye");
        assertStall(MarketCategory.NETHER, "netherrack", "basalt", "polished_basalt", "smooth_basalt",
                "warped_hyphae", "crimson_stem", "soul_sand", "magma_block", "glowstone", "wither_rose",
                "wither_skeleton_skull", "crying_obsidian", "nether_bricks");
    }

    @Test
    void theRestOfTheMarketsStalls() {
        assertStall(MarketCategory.BUILDING, "stone", "cobbled_deepslate", "mossy_stone_bricks", "oak_log",
                "cherry_wood", "smooth_sandstone", "polished_granite", "smooth_stone", "oak_planks", "obsidian");
        assertStall(MarketCategory.MISC_BUILDING, "glass", "lantern", "oak_door", "ladder", "chest", "beacon",
                "jukebox", "item_frame", "sea_lantern");
        assertStall(MarketCategory.NATURE, "dirt", "coarse_dirt", "mud", "mycelium", "azalea_leaves",
                "cherry_leaves", "lilac", "peony", "sunflower", "seagrass", "brain_coral_block",
                "tube_coral_fan", "pointed_dripstone", "dripstone_block", "ice", "snow_block", "cobweb",
                "pink_petals", "brown_mushroom_block", "mushroom_stem");
        assertStall(MarketCategory.REDSTONE, "redstone", "redstone_block", "observer", "dispenser", "dropper",
                "sticky_piston", "rail", "powered_rail", "tnt", "redstone_lamp", "note_block", "hopper",
                "honey_block");
        assertStall(MarketCategory.MATERIALS, "diamond", "diamond_block", "iron_block", "raw_gold_block",
                "gunpowder", "echo_shard", "netherite_ingot", "string", "paper");
        assertStall(MarketCategory.FOOD, "bread", "cooked_beef", "beef", "golden_carrot", "baked_potato",
                "carrot", "potato", "pumpkin", "melon_slice", "melon_seeds", "wheat_seeds", "sugar_cane", "egg");
        assertStall(MarketCategory.BREWING, "potion", "splash_potion", "ghast_tear", "magma_cream",
                "nether_wart", "glistering_melon_slice", "spider_eye", "glowstone_dust", "sugar", "brewing_stand");
        assertStall(MarketCategory.ENCHANTING, "enchanted_book", "experience_bottle", "enchanting_table",
                "anvil", "lapis_lazuli");
        assertStall(MarketCategory.MISC, "music_disc_5", "player_head", "trial_key", "zombie_spawn_egg");
    }

    @Test
    void lokasOwnItemsAreKnownByTheirTypeNotTheirMaterial() {
        assertTrue(MarketCategories.isLokaItem("RECALL_STONE", "EMERALD"));
        assertEquals(MarketCategory.MISC, MarketCategories.classify("RECALL_STONE", "EMERALD"),
                "a Recall Stone is not an emerald to buy");
        assertEquals(MarketCategory.MISC, MarketCategories.classify("REINFORCED_HOPPER", "HOPPER"));
        assertEquals(MarketCategory.BREWING, MarketCategories.classify("NULL_POTION_SPLASH_LONG", "SPLASH_POTION"),
                "a potion under a name of Loka's is still a potion");
        assertFalse(MarketCategories.isLokaItem("DIAMOND_SWORD", "DIAMOND_SWORD"));
        assertFalse(MarketCategories.isLokaItem("§3Xp Bottles", "EXPERIENCE_BOTTLE"), "free text is just a label");
    }

    @Test
    void aFreeTextTypeGoesByTheItemUnderneath() {
        assertEquals(MarketCategory.ENCHANTING, MarketCategories.classify("§dLarge Xp Bottles", "EXPERIENCE_BOTTLE"));
        assertEquals(MarketCategory.BREWING, MarketCategories.classify("Potion of Invisibility 1", "POTION"));
        assertEquals(MarketCategory.MISC, MarketCategories.classify("Something odd", null));
        assertEquals(MarketCategory.WEAPONS, MarketCategories.classify("DIAMOND_SWORD", null),
                "an unreadable stack still goes by its type");
    }

    @Test
    void nothingTheGamePlayersCanSellIsLeftWithoutAStall() throws IOException {
        Map<MarketCategory, List<String>> stalls = new EnumMap<>(MarketCategory.class);
        for (String id : vanillaItems()) {
            stalls.computeIfAbsent(MarketCategories.classifyId(id), key -> new ArrayList<>()).add(id);
        }
        for (MarketCategory category : MarketCategory.values()) {
            assertTrue(stalls.containsKey(category), category + " sells nothing at all");
        }
        // What is left over is what no player sells: operator blocks, spawn eggs, heads, discs.
        List<String> misc = stalls.get(MarketCategory.MISC).stream()
                .filter(id -> !id.endsWith("_spawn_egg") && !id.startsWith("music_disc"))
                .toList();
        assertTrue(misc.size() <= 25, "too much falls through to Misc: " + misc);
    }

    @Test
    void everyTypeOnTheMarketLandsWhereAPlayerWouldLook() throws IOException {
        Map<String, MarketCategory> byType = new java.util.HashMap<>();
        for (MarketListing listing : sample()) {
            byType.put(listing.type(), listing.category());
        }
        assertEquals(168, byType.size());
        assertEquals(MarketCategory.WEAPONS, byType.get("DIAMOND_SPEAR"));
        assertEquals(MarketCategory.ARMOR, byType.get("WILD_ARMOR_TRIM_SMITHING_TEMPLATE"));
        assertEquals(MarketCategory.ENCHANTING, byType.get("§3Xp Bottles"));
        assertEquals(MarketCategory.BREWING, byType.get("Potion of Invisibility 1"));
        assertEquals(MarketCategory.MISC, byType.get("RECALL_STONE"));
        assertEquals(MarketCategory.MISC, byType.get("INPUT_CHEST_ITEM_FRAME"));
        assertEquals(MarketCategory.END, byType.get("BLACK_SHULKER_BOX"));
        assertEquals(MarketCategory.COLORED, byType.get("MAGENTA_WOOL"));
        long misc = byType.values().stream().filter(category -> category == MarketCategory.MISC).count();
        assertTrue(misc <= 12, "only Loka's own items and oddments should be Misc, got " + misc);
    }
}
