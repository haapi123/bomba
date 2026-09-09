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
 * <p>A town falling is found by watching the living roster: seventy-five towns over four pages,
 * diffed on every sweep, and a town that was on it last time and is not on it now has gone. The
 * count behind it is one kilobyte and is checked every minute, so a fall triggers a poll rather than
 * waiting out the interval. That is the dated answer, and the only one there can be — Loka publishes
 * no deletion date and no deletion order, so a fallen town's date is when it was seen to go.
 *
 * <p>The deleted listing is diffed too, as a backstop for a town that leaves and is replaced between
 * two polls, which leaves the living count unmoved.
 *
 * <p>The dangling claims a dead town leaves are a second, undated source. Loka does not clear a
 * deleted town's territories straight away, so its id sits on them until it does; that finds towns
 * nobody was watching for, which on a fresh install is the whole of the history. It cannot be the
 * only source, though, and used to be: a town whose land Loka had already reclaimed left no trace at
 * all and could never appear.
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
    /** How many towns the listing held last time, so a change in it is the signal to re-read. */
    private volatile int deletedTownCount;
    /** The towns on the map at the last poll. A town missing from the next one has fallen. */
    private volatile Map<String, LokaTown> livingTowns = Map.of();

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
        deletedTownCount = deletedTowns.size();
        livingTowns = store.livingTowns();
        state = config.townLogEnabled() ? State.IDLE : State.OFF;

        // Five minutes' grace before the first sweep. Launching the game already saturates a
        // connection, and a sweep on top of that is what makes the first screen anybody opens look
        // broken; nothing here is urgent enough to justify it.
        scheduler.scheduleWithFixedDelay(this::tick, 5 * 60, 60, TimeUnit.SECONDS);
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
        if (System.currentTimeMillis() - lastPollAt >= due || townCountMoved()) {
            poll();
        }
    }

    /**
     * Whether the number of towns has changed since the last poll.
     *
     * <p>One kilobyte, checked every minute, against waiting out a thirty-minute interval to find
     * out that a town fell twenty-nine minutes ago. It is the whole reason a fall can be dated
     * closely at all — Loka publishes no deletion time, so the date is only ever as good as how soon
     * the disappearance was noticed.
     */
    private boolean townCountMoved() {
        if (livingTowns.isEmpty()) {
            return false;
        }
        try {
            int count = loka.countTowns();
            return count >= 0 && count != livingTowns.size();
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not count towns", e);
            return false;
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
                BetterLoka.LOGGER.warn("[betterloka] territory sweep failed", e);
            } catch (RuntimeException e) {
                lastError = e.toString();
                state = State.FAILED;
                BetterLoka.LOGGER.warn("[betterloka] territory sweep failed", e);
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

        List<TownLogEvent> fell = new ArrayList<>(vanishedFromTheMap());
        fell.addAll(refreshDeletedTowns());
        refreshAlliances();

        List<TownLogEvent> events = new ArrayList<>();
        // A town appearing in Loka's deleted listing is the town falling, and is dated when it was
        // seen to happen. The dangling-claim sweep below is the separate question of whose land is
        // still lying about, and its events carry no date worth reading.
        events.addAll(fell);
        events.addAll(fallenTowns(now));
        events.addAll(handovers(now));

        snapshot = now;
        store.record(now, events, deletedTowns, deletedTownsLoadedAt);
        BetterLoka.LOGGER.info("[betterloka] town sweep: {} territories, {} deleted towns on record,"
                        + " {} events kept, newest fallen {}",
                now.size(), deletedTowns.size(), events.size(), newestFallenText());
    }

    /** The newest fallen town and its date, for the diagnostic line. */
    private String newestFallenText() {
        List<TownLogEvent> fallen = newestFirst(fallen());
        if (fallen.isEmpty()) {
            return "none";
        }
        TownLogEvent newest = fallen.get(0);
        return newest.townName() + " @ " + java.time.Instant.ofEpochMilli(newest.at());
    }

    /**
     * Territories still recorded to a town Loka no longer has.
     *
     * <p>Reported whether or not the mod was running when it happened — the dangling id is a standing
     * fact about the territory, not an event that has to be caught.
     */
    private List<TownLogEvent> fallenTowns(Map<String, Territory> now) {
        List<TownLogEvent> events = new ArrayList<>();
        for (FallenTowns.Fallen fallen : FallenTowns.detect(now.values(), deletedTowns)) {
            for (Territory territory : fallen.territories()) {
                events.add(TownLogEvent.of(TownLogEvent.Kind.TOWN_FELL, territory,
                        fallen.town().id(), fallen.town().name(), null));
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
    /**
     * The towns that were on the map last poll and are not on it now.
     *
     * <p>This is the question the module is actually asking — is this town still there — and the
     * living roster is where it is answered. It was being asked of the deleted listing instead,
     * which is forty-two pages and so only re-read when its count moved or an hour had passed; a
     * town could fall and go unnoticed for the better part of an hour. The living roster is seventy-
     * five towns over four pages, cheap enough to diff on every sweep, so a fall is seen within one
     * poll and dated to it.
     *
     * <p>The first sweep seeds the roster and reports nothing, for the same reason the deleted
     * listing does: everything already gone was already gone.
     */
    private List<TownLogEvent> vanishedFromTheMap() {
        Map<String, LokaTown> now;
        try {
            now = loka.fetchLivingTowns();
        } catch (ApiException e) {
            lastError = e.getMessage();
            BetterLoka.LOGGER.warn("[betterloka] could not read the living town roster", e);
            return List.of();
        }
        if (now.isEmpty()) {
            BetterLoka.LOGGER.warn("[betterloka] the living town roster came back empty; ignoring it");
            return List.of();
        }

        Map<String, LokaTown> before = livingTowns;
        livingTowns = Map.copyOf(now);
        store.recordLivingTowns(livingTowns);
        if (before.isEmpty()) {
            BetterLoka.LOGGER.info("[betterloka] first living-town roster: {} towns taken as the "
                    + "baseline, none reported as having just fallen", now.size());
            return List.of();
        }

        List<TownLogEvent> gone = new ArrayList<>();
        for (Map.Entry<String, LokaTown> entry : before.entrySet()) {
            if (!now.containsKey(entry.getKey())) {
                LokaTown town = entry.getValue();
                gone.add(TownLogEvent.townDeleted(town.id(), town.name(), town.world()));
            }
        }
        if (!gone.isEmpty()) {
            BetterLoka.LOGGER.info("[betterloka] {} town(s) left the map since the last poll: {}",
                    gone.size(), gone.stream().map(TownLogEvent::townName).toList());
        }
        return gone;
    }

    private List<TownLogEvent> refreshDeletedTowns() {
        if (!deletedListChanged()) {
            return List.of();
        }
        try {
            Map<String, LokaTown> deleted = new HashMap<>();
            LokaApi.TownPage first = loka.fetchDeletedTownPage(0);
            collect(first, deleted);
            for (int page = 1; page < first.totalPages(); page++) {
                collect(loka.fetchDeletedTownPage(page), deleted);
            }
            if (deleted.isEmpty()) {
                BetterLoka.LOGGER.warn(
                        "[betterloka] the deleted-town listing came back empty; keeping the {} we had",
                        deletedTowns.size());
                return List.of();
            }

            List<TownLogEvent> fell = newlyDeleted(deleted);
            deletedTowns = Map.copyOf(deleted);
            deletedTownsLoadedAt = System.currentTimeMillis();
            deletedTownCount = deleted.size();
            BetterLoka.LOGGER.info(
                    "[betterloka] deleted towns refreshed: {} on record, {} fell since the last poll",
                    deleted.size(), fell.size());
            return fell;
        } catch (ApiException e) {
            // Loudly: a listing that silently stops refreshing is exactly how this went unnoticed.
            lastError = e.getMessage();
            BetterLoka.LOGGER.warn("[betterloka] could not refresh the deleted town list", e);
            return List.of();
        }
    }

    /**
     * Whether the listing is worth re-reading.
     *
     * <p>A town falling changes nothing in the territory data, so the count is the only cheap signal
     * that one has: one request against the forty a full listing costs. The hourly age is a backstop
     * for the case where a town falls and another is created between two polls, leaving the count
     * unchanged.
     */
    private boolean deletedListChanged() {
        if (deletedTowns.isEmpty()) {
            return true;
        }
        try {
            int count = loka.countDeletedTowns();
            if (count >= 0 && count != deletedTownCount) {
                return true;
            }
        } catch (ApiException e) {
            BetterLoka.LOGGER.debug("Could not count deleted towns", e);
        }
        return System.currentTimeMillis() - deletedTownsLoadedAt > DELETED_INDEX_TTL_MILLIS;
    }

    /**
     * The towns in {@code deleted} that were not there last time.
     *
     * <p>This is the fix for the thing that made the list wrong. A fallen town used to be found by
     * the claims it left behind, so one whose land Loka had already reclaimed — Drovath, on the day
     * this was reported — could never appear at all, while one whose claims still dangle stayed on
     * the list for ever wearing the date the mod first noticed it rather than the date it fell.
     *
     * <p>The first run seeds the baseline and reports nothing: 826 towns have been deleted over the
     * server's life, and none of them fell today.
     */
    private List<TownLogEvent> newlyDeleted(Map<String, LokaTown> deleted) {
        if (deletedTowns.isEmpty()) {
            BetterLoka.LOGGER.info("[betterloka] first deleted-town listing: {} towns taken as the "
                    + "baseline, none reported as having just fallen", deleted.size());
            return List.of();
        }
        List<TownLogEvent> fell = new ArrayList<>();
        for (Map.Entry<String, LokaTown> entry : deleted.entrySet()) {
            if (!deletedTowns.containsKey(entry.getKey())) {
                LokaTown town = entry.getValue();
                fell.add(TownLogEvent.townDeleted(town.id(), town.name(), town.world()));
            }
        }
        return fell;
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

    /**
     * The towns that have fallen, one row each.
     *
     * <p>Two sources, and the order between them matters. A town seen to appear in Loka's deleted
     * listing is dated when that happened, and those come first. Behind them are the towns found by
     * the claims they left dangling, which have no honest date — they were already gone when the mod
     * first looked — but are worth showing, because on a fresh install they are the only history
     * there is.
     *
     * <p>One entry per town: a town that left six territories behind used to be six rows.
     */
    public List<TownLogEvent> fallen() {
        Map<String, TownLogEvent> byTown = new LinkedHashMap<>();
        for (TownLogEvent event : store.events()) {
            if (!event.kind().isTownGone()) {
                continue;
            }
            TownLogEvent held = byTown.get(event.townId());
            // A dated sighting beats a standing claim, and the earliest sighting beats a later one.
            if (held == null
                    || (!held.hasTerritory() == !event.hasTerritory() && event.at() < held.at())
                    || (held.hasTerritory() && !event.hasTerritory())) {
                byTown.put(event.townId(), event);
            }
        }
        return List.copyOf(byTown.values());
    }

    /** Whether a fallen town was watched falling, or merely found already gone. */
    public boolean wasSeenFalling(TownLogEvent event) {
        return !event.hasTerritory();
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
    /**
     * Newest first, by when it happened.
     *
     * <p>This used to reverse the list instead of sorting it, which put it in whatever order the
     * events had been appended — and the first sweep appends every standing fallen town at once, in
     * the alphabetical order {@link FallenTowns#detect} returns them in. So "newest" was really
     * "last alphabetically", which is how a town that fell two days ago sat above one that fell
     * today. Sorted on the timestamp, and stable, so events from one sweep keep their own order.
     */
    public static List<TownLogEvent> newestFirst(List<TownLogEvent> events) {
        List<TownLogEvent> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong(TownLogEvent::at).reversed());
        return ordered;
    }
}
