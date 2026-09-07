package com.betterloka.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The orders the Market's offers can be put in.
 *
 * <p>Price is compared per unit rather than per listing, because a stack of sixty-four and a single
 * item are not otherwise comparable — the cheapest listing is often just the smallest one.
 *
 * <p>Dates come from the listing's own id: Loka publishes no "listed at" field, but its ids are
 * MongoDB ObjectIds and those open with the document's creation time.
 */
public enum MarketSort {
    /** Cheapest per unit first — the usual reason to open the Market. */
    CHEAPEST("betterloka.market.sort.cheapest",
            Comparator.comparingDouble(MarketListing::pricePerUnit)),

    DEAREST("betterloka.market.sort.dearest",
            Comparator.comparingDouble(MarketListing::pricePerUnit).reversed()),

    NEWEST("betterloka.market.sort.newest", Comparator.comparing(
            MarketSort::listedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()),

    OLDEST("betterloka.market.sort.oldest", Comparator.comparing(
            MarketSort::listedAt, Comparator.nullsLast(Comparator.naturalOrder())));

    private final String key;
    private final Comparator<MarketListing> comparator;

    MarketSort(String key, Comparator<MarketListing> comparator) {
        this.key = key;
        this.comparator = comparator;
    }

    /** The translation key for this order's name, as the button shows it. */
    public String key() {
        return key;
    }

    /**
     * The translation key for this order spelled out, as the line above the results shows it.
     *
     * <p>Separate from the button's label because the header used to say "cheapest per unit first"
     * whatever the order actually was, which is worse than saying nothing.
     */
    public String orderKey() {
        return key.replace(".sort.", ".order.");
    }

    /** The next order in the cycle, for a button that steps through them. */
    public MarketSort next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * @return a new list in this order; the one passed in is left alone, since it is the API's own
     *         cached copy and is shared with whatever else is reading it
     */
    public List<MarketListing> sort(List<MarketListing> listings) {
        List<MarketListing> sorted = new ArrayList<>(listings);
        // Price is the tie-break on the date orders: two offers put up in the same second are
        // otherwise in whatever order the API returned them, which changes between refreshes.
        sorted.sort(comparator.thenComparingDouble(MarketListing::pricePerUnit));
        return List.copyOf(sorted);
    }

    /** An id that is not an ObjectId has no date; those sort to the end either way. */
    private static Instant listedAt(MarketListing listing) {
        return listing.listedAt();
    }
}
