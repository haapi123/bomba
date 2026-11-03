package com.betterloka.api.model;

import com.betterloka.market.MarketCategory;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading an offer out of the API's item strings, on real offers from Loka's market. */
class MarketListingTest {
    private static MarketListing ofType(String type) throws IOException {
        return com.betterloka.market.MarketCategoriesTest.sample().stream()
                .filter(listing -> type.equals(listing.type()))
                .findFirst().orElseThrow();
    }

    private static MarketListing listing(String itemStack, double price, int quantity, int stackSize) {
        JsonObject json = new JsonObject();
        json.addProperty("id", "6ae0cbb52cb85b4e1cbebb74");
        json.addProperty("ownerId", "owner");
        json.addProperty("price", price);
        json.addProperty("quantity", quantity);
        json.addProperty("stackSize", stackSize);
        json.addProperty("type", "TEST");
        json.addProperty("itemStack", itemStack);
        return MarketListing.fromJson(json);
    }

    @Test
    void thePriceIsForOneStackOfTheStackSize() throws IOException {
        MarketListing gunpowder = ofType("GUNPOWDER");
        assertEquals(390, gunpowder.price());
        assertEquals(64, gunpowder.stackSize());
        assertEquals(390 / 64.0, gunpowder.itemPrice(), 1e-9);
        assertEquals(11, gunpowder.quantity());
        assertEquals(11 * 64, gunpowder.itemCount());
        assertEquals(390 * 11, gunpowder.stockValue(), 1e-9);
    }

    @Test
    void aNamedSwordKeepsItsNameLoreAndEnchantments() throws IOException {
        MarketListing sword = ofType("DIAMOND_SWORD");
        assertEquals("DIAMOND_SWORD", sword.material());
        assertEquals("diamond_sword", sword.itemId());
        assertEquals("✭ Voids Whispr ✭", sword.customName());
        assertTrue(sword.customNameJson().contains("dark_blue"), "the colours are kept for drawing");
        assertTrue(sword.lore().contains("Forged where the ocean floor"), sword.lore().toString());
        assertTrue(sword.loreJson().size() > sword.lore().size(), "rules and spacers are kept for the profile");
        assertFalse(sword.enchantments().isEmpty());
        assertEquals(MarketCategory.WEAPONS, sword.category());
        assertTrue(sword.isSpecial());
    }

    @Test
    void aBooksStoredEnchantmentsAreItsEnchantments() throws IOException {
        assertEquals(List.of("Mending 1"), ofType("ENCHANTED_BOOK").enchantments());
    }

    @Test
    void aTippedArrowSaysWhatItIsTippedWith() throws IOException {
        MarketListing arrows = ofType("TIPPED_ARROW");
        assertEquals("strong_harming", arrows.potionType());
        assertEquals(MarketCategory.WEAPONS, arrows.category());
    }

    @Test
    void lokasOwnItemIsDrawnAsItsMaterialButNamedAsItself() throws IOException {
        MarketListing stone = ofType("RECALL_STONE");
        assertEquals("emerald", stone.itemId());
        assertEquals("Recall Stone", stone.displayName());
        assertTrue(stone.isLokaItem());
        assertEquals(MarketCategory.MISC, stone.category());
    }

    @Test
    void freeTextTypesReadAsWritten() throws IOException {
        assertEquals("Xp Bottles", ofType("§3Xp Bottles").prettyType());
        assertEquals("experience_bottle", ofType("§3Xp Bottles").itemId());
        assertEquals("Potion of Invisibility 1", ofType("Potion of Invisibility 1").prettyType());
        assertEquals("Diamond Sword", MarketListing.pretty("DIAMOND_SWORD"));
        assertEquals("Sharpness", MarketListing.pretty("minecraft:sharpness"));
    }

    @Test
    void aNameWrittenAsABareStringIsStillAName() {
        MarketListing box = listing("ItemStack{SHULKER_BOX x 1, TILE_ENTITY_META:{meta-type=TILE_ENTITY, "
                + "display-name=\"⋇༺-=-⊰Sunspear boxes⊱--=-༻⋇\", blockMaterial=SHULKER_BOX}}", 1000, 1, 1);
        assertEquals("⋇༺-=-⊰Sunspear boxes⊱--=-༻⋇", box.customName());
        assertNotNull(box.customNameJson());
    }

    @Test
    void wearIsRead() {
        MarketListing worn = listing("ItemStack{DIAMOND_SWORD x 1, UNSPECIFIC_META:{meta-type=UNSPECIFIC, "
                + "enchants={minecraft:unbreaking=3}, Damage=6, repair-cost=1}}", 100, 1, 1);
        assertEquals(6, worn.damage());
        assertEquals(List.of("Unbreaking 3"), worn.enchantments());
        assertEquals(0, ofTypeQuietly("GUNPOWDER").damage());
    }

    @Test
    void aPlainStackHasNoNameOrLore() {
        MarketListing plain = listing("ItemStack{SHULKER_BOX x 1}", 1000, 36, 1);
        assertNull(plain.customName());
        assertTrue(plain.lore().isEmpty());
        assertEquals("SHULKER_BOX", plain.material());
        assertTrue(plain.searchText().contains("shulker box"), plain.searchText());
    }

    private static MarketListing ofTypeQuietly(String type) {
        try {
            return ofType(type);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
