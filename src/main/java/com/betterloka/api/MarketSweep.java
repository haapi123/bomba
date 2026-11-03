package com.betterloka.api;

import com.betterloka.api.model.MarketListing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One fetch of the whole market, filling in a page at a time.
 *
 * <p>Written from the request threads and read from the render thread: every write takes the lock,
 * and what the screen reads — the listing snapshot, the counters — is published through volatile
 * fields, so a frame never waits on a page arriving.
 */
public final class MarketSweep {
    private final Map<String, MarketListing> byId = new LinkedHashMap<>();
    private int unnamed;

    private volatile List<MarketListing> listings = List.of();
    private volatile int version;
    private volatile int pagesTotal;
    private volatile int pagesDone;
    private volatile int pagesFailed;
    private volatile boolean failed;
    private volatile long finishedAt;

    MarketSweep() {
    }

    /** The first page is in, and says how many there are. */
    synchronized void start(int pages) {
        pagesTotal = pages;
    }

    /** One page's offers. An offer seen twice — the market moved between pages — is kept once. */
    synchronized void add(List<MarketListing> page) {
        for (MarketListing listing : page) {
            String key = listing.id() != null ? listing.id() : "#" + unnamed++;
            byId.putIfAbsent(key, listing);
        }
        listings = List.copyOf(byId.values());
        pagesDone++;
        version++;
        finishIfDone();
    }

    /** A page that could not be fetched: the rest of the market is still worth showing. */
    synchronized void pageFailed() {
        pagesFailed++;
        pagesDone++;
        version++;
        finishIfDone();
    }

    /** The first page could not be fetched, so nothing is known. */
    synchronized void fail() {
        failed = true;
        finishedAt = System.currentTimeMillis();
        version++;
    }

    private void finishIfDone() {
        if (pagesTotal > 0 && pagesDone >= pagesTotal) {
            finishedAt = System.currentTimeMillis();
        }
    }

    /** Every offer fetched so far. */
    public List<MarketListing> listings() {
        return listings;
    }

    /** Goes up each time anything changes, so a screen knows when to work its view out again. */
    public int version() {
        return version;
    }

    public boolean running() {
        return finishedAt == 0;
    }

    /** Whether nothing at all could be fetched. */
    public boolean failed() {
        return failed;
    }

    /** How many pages could not be fetched, in a sweep that otherwise finished. */
    public int pagesFailed() {
        return pagesFailed;
    }

    /** When the sweep finished, or 0 while it is running. */
    public long finishedAt() {
        return finishedAt;
    }

    /** How far along it is, 0 to 1; 0 until the first page has said how many there are. */
    public float progress() {
        int total = pagesTotal;
        return total == 0 ? 0 : Math.min(1f, pagesDone / (float) total);
    }

    public int pagesDone() {
        return pagesDone;
    }

    public int pagesTotal() {
        return pagesTotal;
    }
}
