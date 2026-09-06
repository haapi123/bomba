package com.betterloka.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A town's colour has to be the same tomorrow, and different from its neighbour's. */
class TownPaletteTest {
    /** Every town holding ground on Kalros, so the spread is measured on names that get drawn. */
    private static final List<String> KALROS = List.of(
            "Corvus", "Grand Daselia", "Hanamura", "Invicta", "Krallar", "Perfodia", "Targon");

    /** More than a continent has ever had at once, to check the ring copes with growth. */
    private static final List<String> MANY = List.of(
            "Corvus", "Vanguard", "Invicta", "Terra Incognita", "Duskfall", "Grand Daselia",
            "Aqronso's Town", "Veiyn", "Falcon Fury", "Moor", "Hilo", "Ashlands",
            "Hanamura", "Krallar", "Perfodia", "Targon", "Bell & Sons", "Northwatch",
            "Ironhold", "Saltmarsh", "Emberfall", "Highgarden");

    @Test
    void everyTownOnAContinentGetsItsOwnColour() {
        for (List<String> towns : List.of(KALROS, MANY)) {
            TownPalette palette = TownPalette.of(towns);
            Set<Integer> seen = new HashSet<>();
            for (String town : towns) {
                assertTrue(seen.add(palette.colorOf(town)), town + " collided in a list of " + towns.size());
            }
        }
    }

    /** The order territories arrive in changes between fetches and must not change the map. */
    @Test
    void theOrderTownsArriveInDoesNotMatter() {
        TownPalette first = TownPalette.of(MANY);
        List<String> shuffled = new ArrayList<>(MANY);
        Collections.reverse(shuffled);
        TownPalette second = TownPalette.of(shuffled);

        for (String town : MANY) {
            assertEquals(first.colorOf(town), second.colorOf(town), town);
        }
    }

    /** Written down, so a change to the palette is a failing test rather than a repainted map. */
    @Test
    void coloursArePinnedToTheirNames() {
        TownPalette palette = TownPalette.of(KALROS);
        assertEquals(0x0D7A9E, palette.colorOf("Corvus"));
        assertEquals(0x00C230, palette.colorOf("Invicta"));
        assertEquals(0xC200C2, palette.colorOf("Targon"));
    }

    @Test
    void caseAndNothingElseIsIgnored() {
        TownPalette palette = TownPalette.of(KALROS);
        assertEquals(palette.colorOf("Corvus"), palette.colorOf("corvus"));
        assertNotEquals(palette.colorOf("Corvus"), palette.colorOf("Invicta"));
    }

    @Test
    void groundNobodyHoldsIsGrey() {
        TownPalette palette = TownPalette.of(KALROS);
        assertEquals(TownPalette.UNCLAIMED, palette.colorOf((String) null));
        assertEquals(TownPalette.UNCLAIMED, palette.colorOf("   "));
    }

    /** A town the palette never saw still gets a colour rather than reading as unclaimed. */
    @Test
    void anUnlistedTownStillGetsAColour() {
        TownPalette palette = TownPalette.of(KALROS);
        assertNotEquals(TownPalette.UNCLAIMED, palette.colorOf("Somebody Else"));
    }

    /**
     * No two slots may come out near enough to be read as one town.
     *
     * <p>Spreading hues by the golden angle did exactly that — slots 89 apart landed within a
     * degree — and produced two towns eight units of blue apart. This is the check that caught it.
     */
    @Test
    void noTwoSlotsLookAlike() {
        List<Integer> colors = new ArrayList<>();
        for (int slot = 0; slot < TownPalette.SLOTS; slot++) {
            colors.add(TownPalette.colorOfSlot(slot));
        }
        for (int i = 0; i < colors.size(); i++) {
            for (int j = i + 1; j < colors.size(); j++) {
                assertTrue(separation(colors.get(i), colors.get(j)) >= 25,
                        "slots " + i + " and " + j + " are too close: "
                                + Integer.toHexString(colors.get(i)) + " vs "
                                + Integer.toHexString(colors.get(j)));
            }
        }
    }

    /**
     * Every colour has to be legible on Loka's ground, which runs white sand to black rock.
     *
     * <p>Nothing may come out near-black: that vanishes into volcanic stone, and it is the one
     * failure the dark rim under the border cannot rescue.
     */
    @Test
    void noColourComesOutTooDarkToSee() {
        for (int slot = 0; slot < TownPalette.SLOTS; slot++) {
            int color = TownPalette.colorOfSlot(slot);
            int brightest = Math.max((color >> 16) & 0xFF,
                    Math.max((color >> 8) & 0xFF, color & 0xFF));
            assertTrue(brightest >= 150, "slot " + slot + " -> " + Integer.toHexString(color));
        }
    }

    private static double separation(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
