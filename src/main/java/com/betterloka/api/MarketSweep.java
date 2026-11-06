package com.betterloka.api;

import com.betterloka.api.model.MarketListing;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One fetch of the whole market, filling in a page at a time — or the last one, read back from disk.
 *
 * <p>Written from the request threads and read from the render thread: every write takes the lock,
 * and what the screen reads — the listing snapshot, the counters — is published through volatile
 * fields, so a frame never waits on a page arriving.
 */
public final class MarketSweep {
    private final Map<String, MarketListing> byId = new LinkedHashMap<>();
    private final Map<String, JsonObject> rawById = new LinkedHashMap<>();
    private int unnamed;
    private final Consumer<MarketSweep> onFinish;

    private volatile List<MarketListing> listings = List.of();
    private volatile int version;
    private volatile int pagesTotal;
    private volatile int pagesDone;
    private volatile int pagesFailed;
    private volatile boolean failed;
    private volatile long finishedAt;
    private volatile boolean fromDisk;
    /** Somebody pressed Refresh and is watching it come in: its pages go to the front of the queue. */
    private volatile boolean urgent;

    MarketSweep(Consumer<MarketSweep> onFinish) {
        this.onFinish = onFinish;
    }

    /** A sweep that finished in an earlier session, read back so the market opens full. */
    static MarketSweep restored(List<JsonObject> raw, long savedAt) {
        MarketSweep sweep = new MarketSweep(ignored -> { });
        List<MarketListing> page = new ArrayList<>(raw.size());
        for (JsonObject object : raw) {
            page.add(MarketListing.fromJson(object));
        }
        sweep.start(1);
        sweep.add(page, raw);
        sweep.finishedAt = savedAt;
        sweep.fromDisk = true;
        return sweep;
    }

    /** The first page is in, and says how many there are. */
    synchronized void start(int pages) {
        pagesTotal = pages;
    }

    /** One page's offers. An offer seen twice — the market moved between pages — is kept once. */
    void add(List<MarketListing> page, List<JsonObject> raw) {
        boolean finished;
        synchronized (this) {
            for (int i = 0; i < page.size(); i++) {
                MarketListing listing = page.get(i);
                String key = listing.id() != null ? listing.id() : "#" + unnamed++;
                if (byId.putIfAbsent(key, listing) == null && i < raw.size()) {
                    rawById.put(key, raw.get(i));
                }
            }
            listings = List.copyOf(byId.values());
            pagesDone++;
            version++;
            finished = finishIfDone();
        }
        if (finished) {
            onFinish.accept(this);
        }
    }

    /** A page that could not be fetched: the rest of the market is still worth showing. */
    void pageFailed() {
        boolean finished;
        synchronized (this) {
            pagesFailed++;
            pagesDone++;
            version++;
            finished = finishIfDone();
        }
        if (finished) {
            onFinish.accept(this);
        }
    }

    /** The first page could not be fetched, so nothing is known. */
    void fail() {
        synchronized (this) {
            failed = true;
            finishedAt = System.currentTimeMillis();
            version++;
        }
        onFinish.accept(this);
    }

    private boolean finishIfDone() {
        if (finishedAt == 0 && pagesTotal > 0 && pagesDone >= pagesTotal) {
            finishedAt = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    /** The offers as the API sent them, for saving. */
    synchronized List<JsonObject> raw() {
        return List.copyOf(rawById.values());
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

    /** Whether it finished with every page in. */
    public boolean whole() {
        return finishedAt != 0 && !failed && pagesFailed == 0;
    }

    /** Whether somebody is waiting on this sweep, rather than it filling in behind the screen. */
    public boolean urgent() {
        return urgent;
    }

    /** Somebody is now waiting on it: whatever pages are still to come are fetched as such. */
    void makeUrgent() {
        urgent = true;
    }

    /** Whether this is the last session's market, read back from disk. */
    public boolean fromDisk() {
        return fromDisk;
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
