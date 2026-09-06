package com.betterloka.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A colour per town, distinct across a continent and the same every session.
 *
 * <p>Loka's map paints a held territory in its alliance's colour, so two rival towns in one alliance
 * come out identical and the border between them is invisible. Giving each town its own colour is
 * what makes the map answer "who holds this" rather than "which side is this".
 *
 * <p>The colour comes from the name, never from the order territories happened to load — the marker
 * file lists them differently between fetches, and anything counter-based would repaint the map on
 * every launch. But a hash alone does not give distinct colours: across twelve real Kalros towns two
 * of them already landed on the same hue. So the hash picks a preferred slot and a collision walks
 * to the next free one, which keeps the map's promise that two colours mean two towns.
 *
 * <p>A town founded later can take a slot another town preferred and push it along, so a colour is
 * stable while the roster is. That is the price of guaranteeing they differ, and it is the right way
 * round: a wrong colour is a curiosity, two towns sharing one is a map that lies.
 */
public final class TownPalette {
    /**
     * A fixed ring of hues rather than a continuous spread.
     *
     * <p>Spreading hues by the golden angle is the usual trick and it fails here: the angle's own
     * convergents mean two slots 89 apart land within a degree of each other, and the first draft
     * produced {@code F043BF} and {@code F043C7} for two different towns. Twenty-four fixed hues
     * are fifteen degrees apart and cannot do that. Seven continents' worth of towns fit easily —
     * Kalros, the busiest, currently has seven holding any ground at all.
     */
    private static final int HUES = 24;

    /** Consecutive slots step a third of the way round the wheel. Coprime with {@link #HUES}. */
    private static final int HUE_STRIDE = 7;

    /**
     * Four takes on each hue, so a continent can outgrow the ring and still not repeat itself.
     *
     * <p>Kept off both ends of the range on purpose: the border is drawn over Loka's own ground,
     * which runs from near-white desert to dark volcanic stone within one territory. A washed-out
     * colour disappears against the sand and a black one against the rock, so even the deepest of
     * these keeps enough light in it to read, and the dark rim the map draws underneath covers the
     * rest.
     */
    private static final float[][] TIERS = {
            {0.78f, 0.96f},
            {1.00f, 0.76f},
            {0.48f, 0.99f},
            {0.92f, 0.62f},
    };

    /** Comfortably more than any continent's town count, so probing stays short. */
    static final int SLOTS = HUES * TIERS.length;

    /** Grey, for ground nobody holds and for a name that never reached us. */
    public static final int UNCLAIMED = 0x8A8F98;

    private final Map<String, Integer> colors;

    private TownPalette(Map<String, Integer> colors) {
        this.colors = colors;
    }

    public static TownPalette of(Collection<String> townNames) {
        // Ordered by the hash rather than alphabetically, so who wins a contested slot does not
        // depend on names elsewhere in the list.
        List<String> ordered = new ArrayList<>();
        for (String name : townNames) {
            if (name != null && !name.isBlank()) {
                ordered.add(name.toLowerCase(Locale.ROOT));
            }
        }
        ordered = new ArrayList<>(new HashSet<>(ordered));
        ordered.sort((a, b) -> {
            int byHash = Long.compare(hash(a) % SLOTS, hash(b) % SLOTS);
            return byHash != 0 ? byHash : a.compareTo(b);
        });

        Set<Integer> taken = new HashSet<>();
        Map<String, Integer> colors = new HashMap<>();
        for (String name : ordered) {
            int slot = (int) (hash(name) % SLOTS);
            while (!taken.add(slot)) {
                slot = (slot + 1) % SLOTS;
            }
            colors.put(name, colorOfSlot(slot));
        }
        return new TownPalette(Map.copyOf(colors));
    }

    /** @return {@code 0xRRGGBB} for a town, or grey when it has no name or was never listed. */
    public int colorOf(String townName) {
        if (townName == null || townName.isBlank()) {
            return UNCLAIMED;
        }
        Integer assigned = colors.get(townName.toLowerCase(Locale.ROOT));
        // A town the palette never saw still gets its preferred colour rather than grey: better a
        // possible clash than a territory drawn as if nobody held it.
        return assigned != null ? assigned
                : colorOfSlot((int) (hash(townName.toLowerCase(Locale.ROOT)) % SLOTS));
    }

    /**
     * Slot to colour: hue steps a third of the wheel each time, the tier changes only after a full
     * lap. Two towns that collided and were placed side by side therefore differ in hue, which is
     * the difference the eye reads fastest.
     */
    static int colorOfSlot(int slot) {
        int index = Math.floorMod(slot, SLOTS);
        float hue = (index * HUE_STRIDE % HUES) / (float) HUES;
        float[] tier = TIERS[(index / HUES) % TIERS.length];
        return hsvToRgb(hue, tier[0], tier[1]);
    }

    /**
     * FNV-1a, rather than {@code String.hashCode}.
     *
     * <p>Both are stable, but this one is written down here: the colours are meant to be the same
     * next year and on somebody else's machine, and that is a promise worth not delegating.
     */
    private static long hash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == Long.MIN_VALUE ? 0 : Math.abs(hash);
    }

    static int hsvToRgb(float hue, float saturation, float brightness) {
        int sector = (int) Math.floor(hue * 6) % 6;
        float offset = hue * 6 - (float) Math.floor(hue * 6);
        float p = brightness * (1 - saturation);
        float q = brightness * (1 - offset * saturation);
        float t = brightness * (1 - (1 - offset) * saturation);

        float red;
        float green;
        float blue;
        switch (sector) {
            case 0 -> { red = brightness; green = t; blue = p; }
            case 1 -> { red = q; green = brightness; blue = p; }
            case 2 -> { red = p; green = brightness; blue = t; }
            case 3 -> { red = p; green = q; blue = brightness; }
            case 4 -> { red = t; green = p; blue = brightness; }
            default -> { red = brightness; green = p; blue = q; }
        }
        return (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }
}
