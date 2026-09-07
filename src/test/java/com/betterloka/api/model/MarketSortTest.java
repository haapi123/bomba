package com.betterloka.api.model;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The orders the Market's offers go in.
 *
 * <p>Dates are the interesting half: Loka publishes no "listed at" field, so they are recovered from
 * the MongoDB ObjectId the listing already carries. The ids below are real ones from
 * {@code /market_sales}.
 */
class MarketSortTest {
    /** Real ids, whose leading eight hex digits are the seconds the document was created. */
    private static final String AUG_24_2106 = "6a8cb2481541d13a1c0a0f56";
    private static final String AUG_24_2255 = "6a8ccbcc1541d13a1c12a623";
    private static final String AUG_24_2325 = "6a8cd2d11541d13a1c14ce13";

    private static MarketListing listing(String id, double price, int quantity) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("ownerId", "owner-" + id);
        json.addProperty("price", price);
        json.addProperty("quantity", quantity);
        json.addProperty("stackSize", 64);
        json.addProperty("type", "DIAMOND_SWORD");
        return MarketListing.fromJson(json);
    }

    @Test
    void aListingKnowsWhenItWentUp() {
        Instant listed = listing(AUG_24_2106, 100, 1).listedAt();

        assertNotNull(listed);
        assertEquals(Instant.parse("2026-08-24T21:06:16Z"), listed);
    }

    @Test
    void anIdThatIsNotAnObjectIdHasNoDate() {
        assertNull(listing("not-an-object-id", 100, 1).listedAt());
    }

    /** Price is compared per unit: the cheapest listing is otherwise just the smallest one. */
    @Test
    void cheapestComparesPerUnitRatherThanPerListing() {
        MarketListing single = listing(AUG_24_2106, 90, 1);        // 90 each
        MarketListing stack = listing(AUG_24_2255, 640, 64);       // 10 each

        List<MarketListing> sorted = MarketSort.CHEAPEST.sort(List.of(single, stack));

        assertEquals(stack.id(), sorted.get(0).id(), "640 for 64 is cheaper than 90 for one");
    }

    @Test
    void dearestIsTheOtherWayRound() {
        MarketListing single = listing(AUG_24_2106, 90, 1);
        MarketListing stack = listing(AUG_24_2255, 640, 64);

        List<MarketListing> sorted = MarketSort.DEAREST.sort(List.of(stack, single));

        assertEquals(single.id(), sorted.get(0).id());
    }

    @Test
    void newestPutsTheMostRecentlyListedFirst() {
        List<MarketListing> sorted = MarketSort.NEWEST.sort(List.of(
                listing(AUG_24_2106, 10, 1), listing(AUG_24_2325, 10, 1), listing(AUG_24_2255, 10, 1)));

        assertEquals(List.of(AUG_24_2325, AUG_24_2255, AUG_24_2106),
                sorted.stream().map(MarketListing::id).toList());
    }

    @Test
    void oldestPutsTheLongestStandingOfferFirst() {
        List<MarketListing> sorted = MarketSort.OLDEST.sort(List.of(
                listing(AUG_24_2325, 10, 1), listing(AUG_24_2106, 10, 1), listing(AUG_24_2255, 10, 1)));

        assertEquals(List.of(AUG_24_2106, AUG_24_2255, AUG_24_2325),
                sorted.stream().map(MarketListing::id).toList());
    }

    /** An offer whose id carries no date must not be mistaken for the newest thing on the market. */
    @Test
    void anUndatedListingSortsToTheEndEitherWay() {
        MarketListing undated = listing("no-date", 10, 1);
        MarketListing dated = listing(AUG_24_2255, 10, 1);

        assertEquals(dated.id(), MarketSort.NEWEST.sort(List.of(undated, dated)).get(0).id());
        assertEquals(dated.id(), MarketSort.OLDEST.sort(List.of(undated, dated)).get(0).id());
    }

    /** Two offers put up in the same second should not swap places between refreshes. */
    @Test
    void offersListedTogetherAreBrokenTiedByPrice() {
        MarketListing dear = listing(AUG_24_2255, 500, 1);
        MarketListing cheap = listing(AUG_24_2255, 100, 1);

        assertEquals(100, MarketSort.NEWEST.sort(List.of(dear, cheap)).get(0).price());
        assertEquals(100, MarketSort.OLDEST.sort(List.of(dear, cheap)).get(0).price());
    }

    /** The order is applied to a copy: the list handed in is the API's own cached one. */
    @Test
    void sortingLeavesTheListItWasGivenAlone() {
        List<MarketListing> original =
                List.of(listing(AUG_24_2325, 10, 1), listing(AUG_24_2106, 10, 1));

        MarketSort.OLDEST.sort(original);

        assertEquals(AUG_24_2325, original.get(0).id());
    }

    @Test
    void everyOrderHasABothLabelAndAHeaderPhrase() {
        for (MarketSort value : MarketSort.values()) {
            assertTrue(value.key().startsWith("betterloka.market.sort."), value.name());
            assertTrue(value.orderKey().startsWith("betterloka.market.order."), value.name());
            assertEquals(value.key().substring(value.key().lastIndexOf('.')),
                    value.orderKey().substring(value.orderKey().lastIndexOf('.')),
                    "the two keys must name the same order");
        }
    }
}
