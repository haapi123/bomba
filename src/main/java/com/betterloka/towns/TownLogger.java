package com.betterloka.towns;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.data.TownCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches who holds every Conquest territory, and reports the towns that have fallen.
 *
 * <p>Two things are being told apart. A territory that moves from one living town to another was
 * taken in a fight — ordinary Conquest, and not what this is for. A territory whose holder Loka no
 * longer has a record of means that town was deleted or collapsed, and that is what gets reported,
 * with the continent, the territory number and the beacon coordinates.
 *
 * <p>Loka leaves the dead town's id on its territories rather than clearing them, so a fallen town
 * is visible in a single sweep without having to have been watching when it happened: the whole
 * standing backlog shows up on the first poll. Naming it takes the deleted-towns listing, because a
 * deleted town 404s on a lookup by id — that listing is fetched rarely and kept, since it only grows
 * when a town dies.
 */
public final class TownLogger {
    /** The deleted-town roster is forty small requests, and only changes when a town dies. */
    private static final long DELETED_INDEX_TTL_MILLIS = 60 * 60 * 1000L;

    /** Where a poll has got to, for the screen to show. */
    public enum State {
        OFF, IDLE, POLLING, FAILED
    }

    private final LokaApi loka;
    private final TownCache towns;
    private final BetterLokaConfig config;
    private final TownLogStore store;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "betterloka-town-logger");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean polling = new AtomicBoolean();

    /** Territory key to its last seen state, so a poll can tell what changed. */
    private volatile Map<String, Territory> snapshot = Map.of();
    /** Deleted town id to name — the only way a fallen town can be named. */
    private volatile Map<String, LokaTown> deletedTowns = Map.of();
    /** Who is allied with whom right now; the history behind it lives in the store. */
    private volatile List<LokaAlliance> alliances = List.of();
    private volatile long deletedTownsLoadedAt;

    private volatile State state = State.OFF;
    private volatile long lastPollAt;
    private volatile String lastError;

    public TownLogger(LokaApi loka, TownCache towns, BetterLokaConfig config, TownLogStore store) {
        this.loka = loka;
        this.towns = towns;
        this.config = config;
        this.store = store;
    }

    /** Loads the saved log and starts the poll timer. Safe to call once, at client start. */
    public void start() {
        store.load();
        snapshot = store.territories();
        deletedTowns = store.deletedTowns();
        deletedTownsLoadedAt = store.deletedTownsLoadedAt();
        state = config.townLogEnabled() ? State.IDLE : State.OFF;

        // A minute's grace before the first sweep so it does not compete with the game starting up.
        scheduler.scheduleWithFixedDelay(this::tick, 60, 60, TimeUnit.SECONDS);
    }

    private void tick() {
        if (!config.townLogEnabled()) {
            state = State.OFF;
            return;
        }
        if (state == State.OFF) {
            state = State.IDLE;
        }
        long due = config.townLogIntervalMinutes() * 60_000L;
        if (System.currentTimeMillis() - lastPollAt >= due) {
            poll();
        }
    }

    /** Runs a sweep now, off the render thread. Does nothing if one is already running. */
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        scheduler.execute(() -> {
            try {
                state = State.POLLING;
                sweep();
                lastError = null;
                state = State.IDLE;
            } catch (ApiException e) {
                lastError = e.getMessage();
                state = State.FAILED;
                BetterLoka.LOGGER.debug("Territory sweep failed", e);
            } catch (RuntimeException e) {
                lastError = e.toString();
                state = State.FAILED;
                BetterLoka.LOGGER.warn("Territory sweep failed", e);
            } finally {
                lastPollAt = System.currentTimeMillis();
                polling.set(false);
            }
        });
    }

    private void sweep() throws ApiException {
        Map<String, Territory> now = new LinkedHashMap<>();
        for (String world : LokaApi.CONQUEST_WORLDS) {
            for (Territory territory : loka.fetchTerritories(world)) {
                now.put(territory.key(), territory);
            }
        }
        if (now.isEmpty()) {
            throw new ApiException("The territory sweep came back empty", false);
        }

        refreshDeletedTowns();
        refreshAlliances();

        List<TownLogEvent> events = new ArrayList<>();
        events.addAll(fallenTowns(now));
        events.addAll(handovers(now));

        snapshot = now;
        store.record(now, events, deletedTowns, deletedTownsLoadedAt);
    }

    /**
     * Territories still recorded to a town Loka no longer has.
     *
     * <p>Reported whether or not the mod was running when it happened — the dangling id is a standing
     * fact about the territory, not an event that has to be caught.
     */
    private List<TownLogEvent> fallenTowns(Map<String, Territory> now) {
        List<TownLogEvent> events = new ArrayList<>();
        for (Territory territory : now.values()) {
            if (!territory.isOwned()) {
                continue;
            }
            LokaTown dead = deletedTowns.get(territory.townId());
            if (dead != null) {
                events.add(TownLogEvent.of(TownLogEvent.Kind.TOWN_FELL, territory,
                        territory.townId(), dead.name(), null));
            }
        }
        return events;
    }

    /** What changed hands since the last sweep. */
    private List<TownLogEvent> handovers(Map<String, Territory> now) {
        Map<String, Territory> before = snapshot;
        List<TownLogEvent> events = new ArrayList<>();
        if (before.isEmpty()) {
            // Nothing to compare against yet; this sweep becomes the baseline.
            return events;
        }
        for (Map.Entry<String, Territory> entry : now.entrySet()) {
            Territory was = before.get(entry.getKey());
            Territory is = entry.getValue();
            if (was == null || java.util.Objects.equals(was.townId(), is.townId())) {
                continue;
            }
            if (was.isOwned() && is.isOwned()) {
                events.add(TownLogEvent.of(TownLogEvent.Kind.CAPTURED, is, was.townId(),
                        nameOf(was.townId()), nameOf(is.townId())));
            } else if (was.isOwned()) {
                events.add(TownLogEvent.of(TownLogEvent.Kind.RELEASED, is, was.townId(),
                        nameOf(was.townId()), null));
            } else {
                events.add(TownLogEvent.of(TownLogEvent.Kind.CLAIMED, is, is.townId(),
                        nameOf(is.townId()), null));
            }
        }
        return events;
    }

    /**
     * Takes a reading of who is allied with whom.
     *
     * <p>One request. Loka publishes the alliances that exist right now and keeps no history, so
     * "who do they usually ally with" can only be built by looking regularly and remembering — which
     * is why this rides along with the territory sweep rather than being asked for on demand.
     */
    private void refreshAlliances() {
        try {
            List<LokaAlliance> found = loka.fetchAlliances();
            if (found.isEmpty()) {
                return;
            }
            alliances = List.copyOf(found);

            Map<String, String> names = new HashMap<>();
            for (LokaAlliance alliance : found) {
                for (String townId : alliance.townIds()) {
                    String name = towns.nameOf(townId);
                    if (name != null) {
                        names.put(townId, name);
                    }
                }
            }
            store.recordAlliances(found, names);
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not refresh the alliance list", e);
        }
    }

    /** Every alliance as of the last sweep. */
    public List<LokaAlliance> alliances() {
        return alliances;
    }

    /** The alliance a town is currently in, or {@code null} if it is in none. */
    public LokaAlliance allianceOf(String townId) {
        for (LokaAlliance alliance : alliances) {
            if (alliance.contains(townId)) {
                return alliance;
            }
        }
        return null;
    }

    /** Who this town has been allied with while the mod has been watching, longest first. */
    public List<TownLogStore.TownPartner> partnersOf(String townId) {
        return store.partnersOf(townId);
    }

    /**
     * Reloads the deleted-town listing when it has gone stale.
     *
     * <p>Time is the only trigger available: a town dying changes nothing in the territory data — the
     * id stays exactly where it was — so there is no cheaper signal to watch. Forty small requests an
     * hour is the price of naming a town within the hour it fell, and the screen has a button for
     * when that is not soon enough.
     */
    private void refreshDeletedTowns() {
        if (System.currentTimeMillis() - deletedTownsLoadedAt <= DELETED_INDEX_TTL_MILLIS
                && !deletedTowns.isEmpty()) {
            return;
        }
        try {
            Map<String, LokaTown> deleted = new HashMap<>();
            LokaApi.TownPage first = loka.fetchDeletedTownPage(0);
            collect(first, deleted);
            for (int page = 1; page < first.totalPages(); page++) {
                collect(loka.fetchDeletedTownPage(page), deleted);
            }
            if (!deleted.isEmpty()) {
                deletedTowns = Map.copyOf(deleted);
                deletedTownsLoadedAt = System.currentTimeMillis();
            }
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not refresh the deleted town list", e);
        }
    }

    private static void collect(LokaApi.TownPage page, Map<String, LokaTown> into) {
        for (LokaTown town : page.towns()) {
            if (town.id() != null) {
                into.put(town.id(), town);
            }
        }
    }

    private String nameOf(String townId) {
        LokaTown dead = deletedTowns.get(townId);
        if (dead != null) {
            return dead.name();
        }
        String name = towns.nameOf(townId);
        return name != null ? name : townId;
    }

    /** Every territory whose holding town no longer exists, newest sweep first. */
    public List<TownLogEvent> fallen() {
        List<TownLogEvent> fallen = new ArrayList<>();
        for (TownLogEvent event : store.events()) {
            if (event.kind().isTownGone()) {
                fallen.add(event);
            }
        }
        return fallen;
    }

    public List<TownLogEvent> events() {
        return store.events();
    }

    /**
     * A town Loka has deleted, by name.
     *
     * <p>The living-town lookup 404s on these, so a name that finds nothing there is usually a town
     * that is gone rather than one that never existed.
     */
    public LokaTown deletedByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (LokaTown town : deletedTowns.values()) {
            if (name.equalsIgnoreCase(town.name())) {
                return town;
            }
        }
        return null;
    }

    /** Territories nobody holds — where a fallen town's land ends up once Loka clears it. */
    public List<Territory> unowned() {
        List<Territory> free = new ArrayList<>();
        for (Territory territory : snapshot.values()) {
            if (!territory.isOwned()) {
                free.add(territory);
            }
        }
        free.sort(Comparator.comparing(Territory::world).thenComparing(t -> numberOf(t.num())));
        return free;
    }

    /** The territories one town currently holds. */
    public List<Territory> heldBy(String townId) {
        List<Territory> held = new ArrayList<>();
        if (townId == null) {
            return held;
        }
        for (Territory territory : snapshot.values()) {
            if (townId.equals(territory.townId())) {
                held.add(territory);
            }
        }
        held.sort(Comparator.comparing(Territory::world).thenComparing(t -> numberOf(t.num())));
        return held;
    }

    private static int numberOf(String num) {
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    public State state() {
        return state;
    }

    public long lastPollAt() {
        return lastPollAt;
    }

    public String lastError() {
        return lastError;
    }

    public int territoryCount() {
        return snapshot.size();
    }

    public int deletedTownCount() {
        return deletedTowns.size();
    }

    /** Drops the saved log, keeping the current sweep as the new baseline. */
    public void clear() {
        store.clearEvents();
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    /** @return the events, newest first. */
    public static List<TownLogEvent> newestFirst(List<TownLogEvent> events) {
        List<TownLogEvent> ordered = new ArrayList<>(events);
        Collections.reverse(ordered);
        return ordered;
    }
}
