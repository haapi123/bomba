package com.betterloka.market;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Which stall an item belongs on.
 *
 * <p>Worked out from the item's id alone — {@code diamond_sword}, {@code lime_wool} — by rules taken
 * in order, the first that matches deciding. Most of the game's 1,650 items follow its own naming
 * ({@code _sword}, {@code _wool}, {@code _ore}), so a handful of suffix rules places most of them and
 * the lists below catch the rest.
 *
 * <p>Plain Java with no Minecraft in it, so every item in the game is checked by a test.
 */
public final class MarketCategories {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    private static final List<String> COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
            "cyan", "purple", "blue", "brown", "green", "red", "black");

    /** What a colour in front makes a coloured block of, rather than a red mushroom or red sand. */
    private static final List<String> COLORED_KINDS = List.of(
            "wool", "carpet", "concrete", "concrete_powder", "terracotta", "glazed_terracotta",
            "stained_glass", "stained_glass_pane", "bed", "banner", "candle", "dye", "cushion");

    private record Rule(MarketCategory category, Predicate<String> matches) {
    }

    private static final List<Rule> RULES = List.of(
            // Nothing a player sells, and named like things they do.
            new Rule(MarketCategory.MISC, id -> id.endsWith("_spawn_egg") || id.startsWith("music_disc")
                    || id.contains("command_block")
                    || id.startsWith("structure_") || Set.of("jigsaw", "barrier", "light", "debug_stick",
                    "test_block", "test_instance_block", "knowledge_book").contains(id)),

            // Gear first, by the suffix the game gives every tier of it.
            new Rule(MarketCategory.ARMOR, id -> endsWithAny(id, "_helmet", "_chestplate", "_leggings",
                    "_boots", "_horse_armor", "_nautilus_armor", "_armor_trim_smithing_template")
                    || Set.of("elytra", "shield", "wolf_armor", "totem_of_undying", "turtle_scute",
                            "armadillo_scute", "netherite_upgrade_smithing_template").contains(id)),
            new Rule(MarketCategory.WEAPONS, id -> endsWithAny(id, "_sword", "_spear", "arrow")
                    || Set.of("bow", "crossbow", "trident", "mace", "wind_charge", "breeze_rod",
                            "heavy_core").contains(id)),
            new Rule(MarketCategory.TOOLS, id -> endsWithAny(id, "_pickaxe", "_shovel", "_axe", "_hoe",
                    "_boat", "_raft", "bucket", "_on_a_stick", "_map", "compass", "_bundle", "_harness")
                    || Set.of("shears", "fishing_rod", "flint_and_steel", "clock", "spyglass", "brush",
                            "lead", "name_tag", "saddle", "map", "bundle", "goat_horn", "firework_rocket",
                            "firework_star", "fire_charge", "writable_book", "written_book").contains(id)),

            // Shulker boxes are the End's, whatever colour they are.
            new Rule(MarketCategory.END, id -> id.startsWith("end_") || id.startsWith("ender_")
                    || id.startsWith("purpur") || id.startsWith("chorus_") || id.endsWith("shulker_box")
                    || Set.of("shulker_shell", "dragon_egg", "dragon_head", "dragon_breath",
                            "popped_chorus_fruit").contains(id)),

            new Rule(MarketCategory.ENCHANTING, id -> id.endsWith("anvil") || Set.of("enchanted_book",
                    "experience_bottle", "enchanting_table", "bookshelf", "book", "grindstone",
                    "lapis_lazuli", "lapis_block").contains(id)),

            new Rule(MarketCategory.BREWING, id -> id.endsWith("potion") || Set.of("brewing_stand",
                    "cauldron", "glass_bottle", "blaze_powder", "blaze_rod", "ghast_tear", "magma_cream",
                    "nether_wart", "glistering_melon_slice", "fermented_spider_eye", "spider_eye",
                    "rabbit_foot", "phantom_membrane", "glowstone_dust", "sugar", "ominous_bottle",
                    "pufferfish").contains(id)),

            new Rule(MarketCategory.COLORED, id -> isColoredBlock(id) || id.endsWith("_banner_pattern")),

            new Rule(MarketCategory.MATERIALS, id -> endsWithAny(id, "_ore", "_ingot", "_nugget",
                    "_shard", "_scrap", "_crystals", "_sherd", "_smithing_template")
                    || id.startsWith("raw_") || Set.of("diamond", "emerald", "coal", "charcoal",
                    "diamond_block", "emerald_block", "coal_block", "iron_block", "gold_block",
                    "netherite_block", "copper_block", "redstone_ore", "ancient_debris", "nether_star",
                    "string", "bone", "leather", "rabbit_hide", "feather", "slime_ball", "gunpowder",
                    "ink_sac", "glow_ink_sac", "rotten_flesh", "flint", "clay_ball", "brick", "stick",
                    "nautilus_shell", "heart_of_the_sea", "honeycomb", "paper", "disc_fragment_5",
                    "resin_clump", "quartz", "sulfur", "potent_sulfur", "cinnabar").contains(id)),

            new Rule(MarketCategory.NETHER, id -> id.contains("nether") && !id.equals("nether_star")
                    && !id.startsWith("netherite") || id.contains("crimson") || id.contains("warped")
                    || id.contains("blackstone") || id.contains("basalt") || id.startsWith("soul_")
                    || id.endsWith("_nylium") || Set.of("magma_block", "glowstone", "shroomlight",
                    "crying_obsidian", "respawn_anchor", "wither_skeleton_skull", "wither_rose",
                    "weeping_vines", "twisting_vines", "lodestone").contains(id)),

            new Rule(MarketCategory.REDSTONE, id -> id.contains("redstone") || id.endsWith("rail")
                    || id.endsWith("lightning_rod")
                    || id.endsWith("minecart") || id.endsWith("_button") || id.endsWith("pressure_plate")
                    || id.endsWith("piston") || id.endsWith("copper_bulb") || id.endsWith("sculk_sensor")
                    || Set.of("repeater", "comparator", "observer", "dispenser", "dropper", "hopper",
                    "tnt", "note_block", "lever", "daylight_detector", "target", "tripwire_hook",
                    "trapped_chest", "crafter", "slime_block", "honey_block", "lightning_rod",
                    "lectern", "sculk_shrieker").contains(id)),

            new Rule(MarketCategory.FOOD, id -> endsWithAny(id, "_stew", "_soup", "_seeds", "beef",
                    "porkchop", "chicken", "mutton", "_cod", "_salmon", "berries", "apple")
                    || id.startsWith("cooked_") || Set.of("bread", "cookie", "cake", "pumpkin_pie",
                    "rabbit", "cod", "salmon", "tropical_fish", "carrot", "golden_carrot", "potato",
                    "baked_potato", "poisonous_potato", "beetroot", "melon_slice", "melon", "pumpkin",
                    "honey_bottle", "dried_kelp", "dried_kelp_block", "wheat", "hay_block", "sugar_cane",
                    "cocoa_beans", "egg", "blue_egg", "brown_egg", "chorus_fruit", "milk_bucket",
                    "carved_pumpkin", "jack_o_lantern", "kelp", "pitcher_pod", "bowl").contains(id)),

            new Rule(MarketCategory.NATURE, id -> endsWithAny(id, "_leaves", "_sapling", "_propagule",
                    "mushroom", "_mushroom_block", "_tulip", "dripleaf", "_roots", "_vines", "_vine",
                    "_flower", "_petals", "_bush", "grass", "_fern", "_moss", "moss_carpet", "moss_block")
                    || id.contains("coral") || id.contains("dirt") || id.contains("sculk")
                    || id.contains("dripstone") || id.contains("amethyst") || id.contains("_ice")
                    || Set.of("grass_block", "podzol", "mycelium", "mud", "sand", "red_sand", "gravel",
                    "clay", "snow", "snow_block", "ice", "seagrass", "sea_pickle", "lily_pad", "vine",
                    "azalea", "flowering_azalea", "cactus", "cactus_flower", "dandelion", "poppy",
                    "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
                    "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "torchflower",
                    "pitcher_plant", "spore_blossom", "mushroom_stem", "cobweb", "bone_meal",
                    "fern", "large_fern", "dead_bush", "hanging_roots", "pale_hanging_moss",
                    "closed_eyeblossom", "open_eyeblossom", "wildflowers", "leaf_litter", "firefly_bush",
                    "bush", "short_dry_grass", "tall_dry_grass", "frogspawn", "turtle_egg", "sniffer_egg",
                    "bee_nest", "pointed_dripstone", "big_dripleaf", "glow_lichen", "bamboo", "golden_dandelion",
                    "red_shrub", "sulfur_spike", "suspicious_sand", "suspicious_gravel", "farmland")
                    .contains(id)),

            new Rule(MarketCategory.MISC_BUILDING, id -> endsWithAny(id, "glass", "glass_pane", "_door",
                    "_trapdoor", "_fence", "_fence_gate", "_sign", "_hanging_sign", "lantern", "torch",
                    "campfire", "_shelf", "chain", "_bars")
                    || id.endsWith("item_frame") || id.endsWith("_golem_statue") || id.endsWith("froglight")
                    || Set.of("ladder", "scaffolding", "sea_lantern", "flower_pot", "decorated_pot",
                    "painting", "armor_stand", "chest", "barrel", "crafting_table", "furnace",
                    "blast_furnace", "smoker", "smithing_table", "cartography_table", "fletching_table",
                    "loom", "stonecutter", "composter", "bell", "beehive", "jukebox", "beacon",
                    "conduit", "end_rod", "chiseled_bookshelf", "candle", "glow_berries_block",
                    "vault", "trial_spawner", "spawner", "heavy_core_block", "copper_chest", "creaking_heart",
                    "dried_ghast", "straw_bed").contains(id)),

            new Rule(MarketCategory.BUILDING, id -> endsWithAny(id, "_planks", "_log", "_wood", "_stem",
                    "_hyphae", "_slab", "_stairs", "_wall", "bricks", "_tiles", "_pillar", "sandstone",
                    "_block", "copper", "_mosaic")
                    || id.startsWith("stripped_") || id.startsWith("polished_") || id.startsWith("smooth_")
                    || id.startsWith("chiseled_") || id.startsWith("cut_") || id.startsWith("cracked_")
                    || id.startsWith("mossy_") || id.startsWith("infested_") || id.contains("deepslate")
                    || id.contains("tuff") || id.contains("prismarine") || id.contains("quartz")
                    || id.contains("copper") || id.contains("resin")
                    || Set.of("stone", "cobblestone", "granite", "diorite", "andesite", "calcite",
                    "packed_mud", "terracotta", "bricks", "mud_bricks", "dark_prismarine", "sponge",
                    "wet_sponge", "obsidian").contains(id)));

    private MarketCategories() {
    }

    /**
     * The stall an offer goes on.
     *
     * @param type     the API's item type: usually the material's name, {@code DIAMOND_SWORD}, but
     *                 Loka's own items have their own, {@code RECALL_STONE}, and a few are free text,
     *                 {@code Potion of Invisibility 1}
     * @param material what the item actually is in the game, read from the item stack, or
     *                 {@code null} when it could not be
     */
    public static MarketCategory classify(String type, String material) {
        String typeId = id(type);
        String materialId = id(material);
        if (isLokaItem(type, material)) {
            // A potion or experience bottle under a name of Loka's own is still sold for what it is.
            if (materialId != null && materialId.endsWith("potion")) {
                return MarketCategory.BREWING;
            }
            return MarketCategory.MISC;
        }
        String id = typeId != null ? typeId : materialId;
        return id == null ? MarketCategory.MISC : classifyId(id);
    }

    /** The stall for one of the game's own item ids, such as {@code lime_wool}. */
    public static MarketCategory classifyId(String id) {
        String plain = id.toLowerCase(Locale.ROOT);
        int colon = plain.indexOf(':');
        if (colon >= 0) {
            plain = plain.substring(colon + 1);
        }
        for (Rule rule : RULES) {
            if (rule.matches().test(plain)) {
                return rule.category();
            }
        }
        return MarketCategory.MISC;
    }

    /**
     * Whether an offer is one of Loka's own items — a Recall Stone, a Reinforced Hopper — rather than
     * a game item under its own name.
     *
     * <p>Those are listed under a type of Loka's ({@code RECALL_STONE}) while the stack underneath is
     * an ordinary material ({@code EMERALD}). Free-text types ({@code §3Xp Bottles}) are only a game
     * item with a label, and are not counted.
     */
    public static boolean isLokaItem(String type, String material) {
        String typeId = id(type);
        String materialId = id(material);
        return typeId != null && materialId != null && !typeId.equals(materialId);
    }

    private static boolean isColoredBlock(String id) {
        for (String color : COLORS) {
            if (id.startsWith(color + "_")) {
                String rest = id.substring(color.length() + 1);
                for (String kind : COLORED_KINDS) {
                    if (rest.equals(kind) || rest.equals(kind + "_slab") || rest.equals(kind + "_stairs")
                            || rest.equals(kind + "_wall")) {
                        return true;
                    }
                }
            }
        }
        return id.equals("terracotta");
    }

    /** A material or type name as an id — {@code DIAMOND_SWORD} to {@code diamond_sword} — or null for free text. */
    static String id(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.strip().toLowerCase(Locale.ROOT);
        return ID.matcher(lower).matches() ? lower : null;
    }

    private static boolean endsWithAny(String id, String... suffixes) {
        for (String suffix : suffixes) {
            if (id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
