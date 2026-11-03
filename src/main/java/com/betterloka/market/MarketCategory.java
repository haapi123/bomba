package com.betterloka.market;

/**
 * The stalls of the market square: the groups the Market's offers are sorted into.
 *
 * <p>Loka's API has no categories — an offer is an item type, a price and a seller — and its
 * in-game market only says it is "several chests organized into categories". These are modelled on
 * the game's own creative tabs, which the in-game chests resemble ("Colored Blocks" is a tab name),
 * with combat split into weapons and armour, and the Nether and the End kept apart as the Loka
 * market does.
 *
 * <p>Declared in the order the stalls stand on the square, back row first.
 */
public enum MarketCategory {
    BUILDING("building", "stone_bricks", 0x8A8F98),
    COLORED("colored", "lime_wool", 0x3FB8C9),
    MISC_BUILDING("misc_building", "lantern", 0xC98A3F),
    NATURE("nature", "oak_sapling", 0x5DAA3C),
    REDSTONE("redstone", "redstone", 0xD03A30),

    MATERIALS("materials", "diamond", 0x4FD3C4),
    FOOD("food", "golden_carrot", 0xE0A030),
    BREWING("brewing", "brewing_stand", 0xB04FD0),
    ENCHANTING("enchanting", "enchanted_book", 0x7A5CE0),
    MISC("misc", "emerald", 0x6E7B60),

    TOOLS("tools", "diamond_pickaxe", 0x6FA8DC),
    WEAPONS("weapons", "diamond_sword", 0xC0392B),
    ARMOR("armor", "diamond_chestplate", 0x3A6FD8),
    NETHER("nether", "netherrack", 0x9E2B25),
    END("end", "end_stone", 0xD8D27A);

    /** How many stalls stand in each row of the square. */
    public static final int PER_ROW = 5;

    private final String id;
    private final String icon;
    private final int color;

    MarketCategory(String id, String icon, int color) {
        this.id = id;
        this.icon = icon;
        this.color = color;
    }

    public String id() {
        return id;
    }

    /** The translation key for the stall's name. */
    public String key() {
        return "betterloka.market.category." + id;
    }

    /** The translation key for the line saying what the stall sells. */
    public String descriptionKey() {
        return key() + ".description";
    }

    /** The item a stall shows when it has nothing on its counter yet. */
    public String icon() {
        return icon;
    }

    /** The colour of the stall's awning, as RGB. */
    public int color() {
        return color;
    }

    public static MarketCategory byId(String id) {
        for (MarketCategory category : values()) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        return null;
    }
}
