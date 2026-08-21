package com.betterloka.api.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deals tab points people at listings to buy, so a false positive costs somebody money. The rule
 * is deliberately conservative: a discount is measured against the going rate for that item, and an
 * item with barely any listings has no going rate to be under.
 */
class MarketDealTest {

    @Test
    void findsAListingUnderTheGoingRate() {
        List<MarketListing> listings = new ArrayList<>();
        listings.add(listing("GUNPOWDER", 100, 64));
        listings.add(listing("GUNPOWDER", 640, 64));
        listings.add(listing("GUNPOWDER", 660, 64));
        listings.add(listing("GUNPOWDER", 700, 64));

        List<MarketDeal> deals = MarketDeal.find(listings);
        assertEquals(1, deals.size(), "only the one well under the middle of the market");
        MarketDeal deal = deals.get(0);
        assertEquals(100.0 / 64, deal.listing().pricePerUnit(), 0.0001);
        assertEquals(4, deal.listingsOfType());
        assertTrue(deal.discount() > 0.8, "a tenth of the going rate is a deep discount");
    }

    @Test
    void leavesAnOrdinarySpreadAlone() {
        List<MarketListing> listings = new ArrayList<>();
        listings.add(listing("DIAMOND", 90, 1));
        listings.add(listing("DIAMOND", 95, 1));
        listings.add(listing("DIAMOND", 100, 1));
        listings.add(listing("DIAMOND", 110, 1));

        assertTrue(MarketDeal.find(listings).isEmpty(),
                "a normal spread is not a bargain; flagging it would train people to ignore the tab");
    }

    @Test
    void needsEnoughListingsToKnowTheGoingRate() {
        List<MarketListing> listings = new ArrayList<>();
        listings.add(listing("NETHERITE_INGOT", 10, 1));
        listings.add(listing("NETHERITE_INGOT", 10_000, 1));

        assertTrue(MarketDeal.find(listings).isEmpty(),
                "two listings do not establish a price, however far apart they are");
    }

    @Test
    void comparesPerUnitRatherThanPerListing() {
        List<MarketListing> listings = new ArrayList<>();
        // A stack of 64 for 640 is 10 each — the same price as the singles, not a bargain.
        listings.add(listing("ARROW", 640, 64));
        listings.add(listing("ARROW", 10, 1));
        listings.add(listing("ARROW", 11, 1));
        listings.add(listing("ARROW", 12, 1));

        assertTrue(MarketDeal.find(listings).isEmpty(),
                "a big total price on a big stack is not a discount");
    }

    @Test
    void ranksTheDeepestDiscountFirst() {
        List<MarketListing> listings = new ArrayList<>();
        listings.add(listing("GUNPOWDER", 100, 64));
        listings.add(listing("GUNPOWDER", 300, 64));
        listings.add(listing("GUNPOWDER", 640, 64));
        listings.add(listing("GUNPOWDER", 660, 64));
        listings.add(listing("GUNPOWDER", 700, 64));

        List<MarketDeal> deals = MarketDeal.find(listings);
        assertEquals(2, deals.size());
        assertTrue(deals.get(0).discount() > deals.get(1).discount());
    }

    private static MarketListing listing(String type, double price, int quantity) {
        JsonObject json = JsonParser.parseString("""
                {"id":"x","ownerId":"o","price":%s,"quantity":%s,"type":"%s","itemStack":""}
                """.formatted(price, quantity, type)).getAsJsonObject();
        return MarketListing.fromJson(json);
    }
}
