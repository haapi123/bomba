package com.betterloka.miner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map from an inventory index to the slot number a click has to name.
 *
 * <p>Pinned because the two numberings disagree in a way that is silent when wrong: the hotbar is
 * the first nine of the inventory and the last nine of the screen, so an off-by-27 does not throw —
 * it swaps the player's boots into their off-hand and carries on.
 */
class MinerSlotMappingTest {
    /** Slot numbers in {@code PlayerScreenHandler}, which is what a click is addressed against. */
    private static final int FIRST_MAIN_SLOT = 9;
    private static final int LAST_MAIN_SLOT = 35;
    private static final int FIRST_HOTBAR_SLOT = 36;
    private static final int LAST_HOTBAR_SLOT = 44;

    @Test
    void theHotbarIsTheFirstNineOfTheInventoryAndTheLastNineOfTheScreen() {
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            assertEquals(FIRST_HOTBAR_SLOT + hotbar, OreMiner.playerSlotOf(hotbar),
                    "hotbar index " + hotbar);
        }
    }

    @Test
    void theRestOfTheInventoryKeepsItsOwnNumber() {
        for (int index = 9; index <= 35; index++) {
            assertEquals(index, OreMiner.playerSlotOf(index), "inventory index " + index);
        }
    }

    /** Every index maps somewhere a player actually keeps items, and nowhere else. */
    @Test
    void nothingMapsOntoArmourOrTheCraftingCorner() {
        for (int index = 0; index <= 35; index++) {
            int slot = OreMiner.playerSlotOf(index);
            assertTrue(slot >= FIRST_MAIN_SLOT && slot <= LAST_HOTBAR_SLOT,
                    "inventory index " + index + " mapped to slot " + slot
                            + ", which is armour, the crafting corner or off the end");
        }
    }

    /** And no two indices land on the same slot, or one of them is unreachable. */
    @Test
    void theMappingIsOneToOne() {
        boolean[] seen = new boolean[LAST_HOTBAR_SLOT + 1];
        for (int index = 0; index <= 35; index++) {
            int slot = OreMiner.playerSlotOf(index);
            assertTrue(!seen[slot], "slot " + slot + " claimed twice, second time by index " + index);
            seen[slot] = true;
        }
        for (int slot = FIRST_MAIN_SLOT; slot <= LAST_MAIN_SLOT; slot++) {
            assertTrue(seen[slot], "slot " + slot + " is never reachable");
        }
        for (int slot = FIRST_HOTBAR_SLOT; slot <= LAST_HOTBAR_SLOT; slot++) {
            assertTrue(seen[slot], "slot " + slot + " is never reachable");
        }
    }
}
