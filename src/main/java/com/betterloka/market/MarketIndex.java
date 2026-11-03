package com.betterloka.market;

import com.betterloka.api.model.MarketListing;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The market sorted onto its stalls: what each sells, how much of it, and what to put on its
 * counter.
 *
 * <p>Plain Java, worked out once per change in what has been fetched rather than on every frame.
 */
public final class MarketIndex {
    /** How many items a stall shows on its counter. */
    public static final int SHOWCASE = 3;

    /**
     * One stall.
     *
     * @param listings every offer it has, in the order they were fetched
     * @param types    how many different item types those are
     * @param showcase item ids to put on the counter: the ones most offered, most first
     */
    public record Stall(MarketCategory category, List<MarketListing> listings, int types, List<String> showcase) {
        public int offers() {
            return listings.size();
        }
    }

    private static final MarketIndex EMPTY = of(List.of());

    private final Map<MarketCategory, Stall> stalls;
    private final int total;

    private MarketIndex(Map<MarketCategory, Stall> stalls, int total) {
        this.stalls = stalls;
        this.total = total;
    }

    public static MarketIndex empty() {
        return EMPTY;
    }

    public static MarketIndex of(List<MarketListing> listings) {
        Map<MarketCategory, List<MarketListing>> grouped = new EnumMap<>(MarketCategory.class);
        for (MarketListing listing : listings) {
            grouped.computeIfAbsent(listing.category(), key -> new ArrayList<>()).add(listing);
        }
        Map<MarketCategory, Stall> stalls = new EnumMap<>(MarketCategory.class);
        for (MarketCategory category : MarketCategory.values()) {
            List<MarketListing> offers = grouped.getOrDefault(category, List.of());
            Set<String> types = new HashSet<>();
            Map<String, Integer> byItem = new HashMap<>();
            for (MarketListing offer : offers) {
                types.add(offer.type());
                String item = offer.itemId();
                if (item != null) {
                    byItem.merge(item, 1, Integer::sum);
                }
            }
            List<String> showcase = byItem.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(SHOWCASE)
                    .map(Map.Entry::getKey)
                    .toList();
            stalls.put(category, new Stall(category, List.copyOf(offers), types.size(), showcase));
        }
        return new MarketIndex(stalls, listings.size());
    }

    public Stall stall(MarketCategory category) {
        return stalls.get(category);
    }

    /** How many offers there are on the whole square. */
    public int total() {
        return total;
    }
}
