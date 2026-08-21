package com.betterloka.api.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A listing priced well under what its item usually goes for.
 *
 * <p>The comparison is against the <em>typical</em> price of that item rather than the cheapest one,
 * because the cheapest listing cannot be cheaper than itself: what makes an offer worth crossing the
 * map for is that it undercuts the going rate, and the going rate is the middle of the market.
 */
public record MarketDeal(MarketListing listing, double typicalPricePerUnit, int listingsOfType) {

    /** How far under the going rate a listing has to be to count. */
    private static final double DISCOUNT = 0.20;

    /**
     * Fewer than this many listings and there is no going rate to speak of — two offers make each
     * other look like a bargain or a ripoff depending on the order.
     */
    private static final int MINIMUM_LISTINGS = 3;

    /** @return every underpriced listing, deepest discount first. */
    public static List<MarketDeal> find(List<MarketListing> listings) {
        Map<String, List<MarketListing>> byType = new HashMap<>();
        for (MarketListing listing : listings) {
            if (listing.type() != null && listing.pricePerUnit() > 0) {
                byType.computeIfAbsent(listing.type(), key -> new ArrayList<>()).add(listing);
            }
        }

        List<MarketDeal> deals = new ArrayList<>();
        for (List<MarketListing> ofType : byType.values()) {
            if (ofType.size() < MINIMUM_LISTINGS) {
                continue;
            }
            ofType.sort(Comparator.comparingDouble(MarketListing::pricePerUnit));
            double typical = median(ofType);
            if (typical <= 0) {
                continue;
            }
            for (MarketListing listing : ofType) {
                if (listing.pricePerUnit() > typical * (1 - DISCOUNT)) {
                    // Sorted by price, so the first one at the going rate ends the run of bargains.
                    break;
                }
                deals.add(new MarketDeal(listing, typical, ofType.size()));
            }
        }
        deals.sort(Comparator.comparingDouble(MarketDeal::discount).reversed());
        return List.copyOf(deals);
    }

    /** {@code sorted} must already be ordered by price per unit. */
    private static double median(List<MarketListing> sorted) {
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle).pricePerUnit();
        }
        return (sorted.get(middle - 1).pricePerUnit() + sorted.get(middle).pricePerUnit()) / 2;
    }

    /** How far under the going rate this is, 0 to 1. */
    public double discount() {
        return typicalPricePerUnit <= 0 ? 0 : 1 - listing.pricePerUnit() / typicalPricePerUnit;
    }

    /**
     * Rounded down, so an item at a thousandth of the going rate reads {@code -99%} rather than the
     * {@code -100%} that rounding produces — nothing is ever actually free.
     */
    public String discountText() {
        return String.format(Locale.ROOT, "-%d%%", (int) Math.floor(discount() * 100));
    }
}
