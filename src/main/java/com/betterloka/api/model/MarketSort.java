package com.betterloka.api.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The orders the Market's offers can be put in, as its Filter menu offers them.
 *
 * <p>Price is compared per item rather than per offer, because a stack of sixty-four and a single
 * item are not otherwise comparable — the cheapest offer is often just the smallest one.
 *
 * <p>Dates come from the listing's own id: Loka publishes no "listed at" field, but its ids are
 * MongoDB ObjectIds and those open with the document's creation time.
 */
public enum MarketSort {
    NEWEST("betterloka.market.sort.newest", Comparator.comparing(
            MarketSort::listedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()),

    OLDEST("betterloka.market.sort.oldest", Comparator.comparing(
            MarketSort::listedAt, Comparator.nullsLast(Comparator.naturalOrder()))),

    DEAREST("betterloka.market.sort.dearest",
            Comparator.comparingDouble(MarketListing::itemPrice).reversed()),

    CHEAPEST("betterloka.market.sort.cheapest",
            Comparator.comparingDouble(MarketListing::itemPrice));

    private final String key;
    private final Comparator<MarketListing> comparator;

    MarketSort(String key, Comparator<MarketListing> comparator) {
        this.key = key;
        this.comparator = comparator;
    }

    /** The translation key for this order's name, as the Filter menu shows it. */
    public String key() {
        return key;
    }

    /** The translation key for this order spelled out, as the line above the results shows it. */
    public String orderKey() {
        return key.replace(".sort.", ".order.");
    }

    /** Whether this order is by when the offer went up, rather than by price. */
    public boolean byDate() {
        return this == NEWEST || this == OLDEST;
    }

    /**
     * @return a new list in this order; the one passed in is left alone, since it is the API's own
     *         cached copy and is shared with whatever else is reading it
     */
    public List<MarketListing> sort(List<MarketListing> listings) {
        List<MarketListing> sorted = new ArrayList<>(listings);
        // Price is the tie-break on the date orders, and the date on the price ones: two offers put
        // up in the same second are otherwise in whatever order the API returned them, which
        // changes between refreshes.
        Comparator<MarketListing> tieBreak = byDate()
                ? Comparator.comparingDouble(MarketListing::itemPrice)
                : NEWEST.comparator;
        sorted.sort(comparator.thenComparing(tieBreak).thenComparing(MarketSort::idOf));
        return List.copyOf(sorted);
    }

    /** An id that is not an ObjectId has no date; those sort to the end either way. */
    private static Instant listedAt(MarketListing listing) {
        return listing.listedAt();
    }

    private static String idOf(MarketListing listing) {
        return listing.id() == null ? "" : listing.id();
    }
}
