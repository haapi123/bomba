package com.betterloka.data;

import com.betterloka.BetterLoka;
import com.betterloka.api.LokaApi;
import com.betterloka.api.LokaApiException;
import com.betterloka.api.model.BattleZone;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps a local copy of Loka's battle history in step with the server.
 *
 * <p>The API offers no way to ask "which battles did player X fight in", and caps pages at 20
 * records, so answering that question at all means holding the history locally. The first sync
 * walks every page (a few hundred requests); after that the count of battles the server reports is
 * compared against the count we already stored, and only the pages covering the difference are
 * fetched — normally one or two.
 */
public final class BattleSyncService {
    public enum Phase {
        IDLE,
        /** Reading the on-disk cache. */
        LOADING,
        /** Talking to the API. */
        SYNCING,
        READY,
        FAILED
    }

    public record Progress(Phase phase, int done, int total, String message) {
        public boolean busy() {
            return phase == Phase.LOADING || phase == Phase.SYNCING;
        }

        public float fraction() {
            return total <= 0 ? 0f : Math.min(1f, (float) done / total);
        }
    }

    /** Beyond this many pages behind, a rolling catch-up is not worth it — resync everything. */
    private static final int MAX_INCREMENTAL_PAGES = 40;

    private final LokaApi api;
    private final BattleIndexStore store;

    /**
     * The sync's own coordinator thread. It spends most of its life blocked waiting on the page
     * fetches, so it must not sit on one of the API pool's workers — otherwise a search started
     * mid-sync would queue behind the very sync it is waiting for.
     */
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BetterLoka-Sync");
        thread.setDaemon(true);
        return thread;
    });

    private volatile BattleIndex index;
    private volatile List<BattleZone> activeBattles = List.of();
    private volatile Progress progress = new Progress(Phase.IDLE, 0, 0, "");
    private CompletableFuture<BattleIndex> inFlight;

    public BattleSyncService(LokaApi api, Path cacheFile) {
        this.api = api;
        this.store = new BattleIndexStore(cacheFile);
    }

    public Progress progress() {
        return progress;
    }

    /** Battles in progress right now. Not part of the index — they are refetched on every sync. */
    public List<BattleZone> activeBattles() {
        return activeBattles;
    }

    /**
     * Starts a sync, or joins the one already running. Never runs two syncs at once, so it is safe
     * to call on every screen open.
     */
    public synchronized CompletableFuture<BattleIndex> ensureSynced() {
        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }
        inFlight = CompletableFuture.supplyAsync(this::run, coordinator);
        return inFlight;
    }

    private BattleIndex run() {
        try {
            BattleIndex current = index;
            if (current == null) {
                progress = new Progress(Phase.LOADING, 0, 0, "Reading cached battles");
                current = store.load();
                index = current;
            }

            progress = new Progress(Phase.SYNCING, 0, 1, "Checking for new battles");
            LokaApi.BattlePage first = api.fetchBattlePage(0);
            merge(current, first.battles());

            int totalPages = first.totalPages();
            int totalElements = first.totalElements();
            int lastPage = pagesToFetch(current, totalPages, totalElements);
            boolean fullSync = lastPage >= totalPages - 1 && totalPages > MAX_INCREMENTAL_PAGES;

            int failures = fetchPages(current, 1, lastPage, fullSync);

            if (failures == 0) {
                current.setSyncedTotalElements(totalElements);
            } else if (fullSync) {
                // A partial full sync leaves holes anywhere in the history, and the incremental
                // path can only fill holes at the front. Force another full sync next time.
                current.setSyncedTotalElements(0);
            }
            store.save(current);

            refreshActiveBattles();

            progress = new Progress(Phase.READY, 1, 1,
                    current.size() + " battles indexed" + (failures > 0 ? " (" + failures + " pages failed)" : ""));
            return current;
        } catch (LokaApiException e) {
            BetterLoka.LOGGER.warn("Battle sync failed", e);
            BattleIndex current = index;
            if (current != null && current.size() > 0) {
                // We still have usable history from a previous run; let the GUI work with it.
                refreshActiveBattles();
                progress = new Progress(Phase.READY, 1, 1, "Offline — showing " + current.size() + " cached battles");
                return current;
            }
            progress = new Progress(Phase.FAILED, 0, 0, describe(e));
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    /** @return the last page index that needs fetching, or 0 when page 0 alone was enough. */
    private int pagesToFetch(BattleIndex current, int totalPages, int totalElements) {
        if (totalPages <= 1) {
            return 0;
        }
        int synced = current.syncedTotalElements();
        if (synced <= 0 || current.size() == 0) {
            return totalPages - 1;
        }
        int appeared = totalElements - synced;
        if (appeared <= 0) {
            return 0;
        }
        // One extra page of slack so a battle sitting on a page boundary is never missed.
        int needed = (appeared / LokaApi.PAGE_SIZE) + 2;
        if (needed > MAX_INCREMENTAL_PAGES) {
            return totalPages - 1;
        }
        return Math.min(totalPages - 1, needed);
    }

    /** @return how many pages could not be fetched. */
    private int fetchPages(BattleIndex current, int firstPage, int lastPage, boolean fullSync) {
        if (lastPage < firstPage) {
            return 0;
        }
        int total = lastPage - firstPage + 1;
        String label = fullSync ? "Downloading full battle history" : "Downloading new battles";
        progress = new Progress(Phase.SYNCING, 0, total, label);

        AtomicInteger done = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>(total);

        for (int page = firstPage; page <= lastPage; page++) {
            int target = page;
            tasks.add(CompletableFuture.runAsync(() -> {
                try {
                    merge(current, api.fetchBattlePage(target).battles());
                } catch (LokaApiException e) {
                    failures.incrementAndGet();
                    BetterLoka.LOGGER.debug("Battle page {} failed", target, e);
                } finally {
                    progress = new Progress(Phase.SYNCING, done.incrementAndGet(), total, label);
                }
            }, api.bulkExecutor()));
        }

        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        return failures.get();
    }

    private void refreshActiveBattles() {
        try {
            activeBattles = api.fetchActiveBattles();
        } catch (LokaApiException e) {
            BetterLoka.LOGGER.debug("Could not fetch active battles", e);
            activeBattles = List.of();
        }
    }

    private static void merge(BattleIndex index, List<BattleZone> battles) {
        for (BattleZone battle : battles) {
            // Battles still running get their kill counts updated as they go, so indexing them here
            // would freeze a half-finished snapshot into the cache. They are tracked separately.
            if (!battle.active() && battle.timeEnded() > 0) {
                index.add(battle);
            }
        }
    }

    private static String describe(LokaApiException e) {
        return e.notFound() ? "Loka API returned no data" : "Could not reach the Loka API";
    }
}
