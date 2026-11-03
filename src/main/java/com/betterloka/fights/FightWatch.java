package com.betterloka.fights;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.ScheduledFight;
import com.betterloka.api.model.TerritoryDetails;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The fights on Loka's books, kept current while somebody is looking.
 *
 * <p>The map asks for them every frame through {@link #poll}; a request goes out every
 * {@link #POLL_MILLIS} at most, which is one small request — the declared fights, usually a handful
 * — so a fight declared while the map is open is on it within a quarter of a minute.
 *
 * <p>Nothing is fetched while nobody is looking: the map is the only thing that polls.
 */
public final class FightWatch {
    /** How often the fights are asked for while the map is open. */
    public static final long POLL_MILLIS = 15_000;

    /** How long a fight counts as just declared, and is marked new. */
    public static final long NEW_MILLIS = 3 * 60 * 1000L;

    /** A territory's mutator and buff change rarely; they are read again after this long. */
    private static final long TERRITORY_TTL_MILLIS = 5 * 60 * 1000L;

    /** How a fight is fetched; the game API in the mod, a fixture in a test. */
    public interface Source {
        List<ScheduledFight> fights() throws ApiException;

        TerritoryDetails territory(String territoryId) throws ApiException;
    }

    private record Cached(TerritoryDetails details, long at) {
    }

    private final Source source;
    private final Executor executor;

    private volatile List<ScheduledFight> fights = List.of();
    private volatile long updatedAt;
    private volatile boolean failed;
    private volatile boolean inFlight;
    private volatile long lastRequestAt;
    private volatile boolean baselineTaken;

    /** When each fight was first seen, by id; 0 for those already there when watching began. */
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    private final Map<String, Cached> territories = new ConcurrentHashMap<>();
    private final Set<String> territoriesInFlight = ConcurrentHashMap.newKeySet();

    public FightWatch(Source source, Executor executor) {
        this.source = source;
        this.executor = executor;
    }

    /** Asks for the fights again if the last answer is old enough. Safe to call every frame. */
    public void poll(long now) {
        if (inFlight || now - lastRequestAt < POLL_MILLIS) {
            return;
        }
        inFlight = true;
        lastRequestAt = now;
        try {
            executor.execute(this::fetch);
        } catch (RuntimeException e) {
            inFlight = false;
        }
    }

    /** Asks again at the next poll, whatever the time. */
    public void refreshSoon() {
        lastRequestAt = 0;
    }

    private void fetch() {
        try {
            List<ScheduledFight> fetched = new ArrayList<>(source.fights());
            long now = System.currentTimeMillis();
            for (ScheduledFight fight : fetched) {
                if (fight.id() != null) {
                    // The first answer is what was already there: nothing in it is news.
                    firstSeen.putIfAbsent(fight.id(), baselineTaken ? now : 0L);
                }
            }
            baselineTaken = true;
            fetched.sort(ORDER);
            fights = List.copyOf(fetched);
            updatedAt = now;
            failed = false;
            for (ScheduledFight fight : fetched) {
                requestTerritory(fight.territoryId(), now);
            }
        } catch (ApiException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not fetch the declared fights", e);
            failed = true;
        } finally {
            inFlight = false;
        }
    }

    /** Under way first, then soonest to start, then biggest. */
    private static final Comparator<ScheduledFight> ORDER = Comparator
            .comparing((ScheduledFight fight) -> !fight.started())
            .thenComparingLong(fight -> {
                long at = FightTiming.startsAt(fight);
                return at == 0 ? Long.MAX_VALUE : at;
            })
            .thenComparingInt(fight -> -fight.warping());

    private void requestTerritory(String territoryId, long now) {
        if (territoryId == null) {
            return;
        }
        Cached cached = territories.get(territoryId);
        if (cached != null && now - cached.at() < TERRITORY_TTL_MILLIS) {
            return;
        }
        if (!territoriesInFlight.add(territoryId)) {
            return;
        }
        executor.execute(() -> {
            try {
                territories.put(territoryId, new Cached(source.territory(territoryId), System.currentTimeMillis()));
            } catch (ApiException | RuntimeException e) {
                BetterLoka.LOGGER.debug("Could not fetch territory {}", territoryId, e);
            } finally {
                territoriesInFlight.remove(territoryId);
            }
        });
    }

    /** The fights as last fetched, under way first and then soonest. */
    public List<ScheduledFight> fights() {
        return fights;
    }

    /** When they were last fetched, or 0 before the first answer. */
    public long updatedAt() {
        return updatedAt;
    }

    public boolean loading() {
        return inFlight && updatedAt == 0;
    }

    /** Whether the last attempt failed; what was fetched before it is still shown. */
    public boolean failed() {
        return failed;
    }

    /** The territory a fight is over, once fetched, or {@code null}. */
    public TerritoryDetails territory(String territoryId) {
        Cached cached = territoryId == null ? null : territories.get(territoryId);
        return cached == null ? null : cached.details();
    }

    /** When a fight was first seen while watching, or 0 if it was there from the start. */
    public long firstSeen(ScheduledFight fight) {
        Long at = fight.id() == null ? null : firstSeen.get(fight.id());
        return at == null ? 0 : at;
    }

    /** Whether a fight was declared while the map was being watched, and recently. */
    public boolean isNew(ScheduledFight fight, long now) {
        long seen = firstSeen(fight);
        return seen > 0 && now - seen < NEW_MILLIS;
    }

    /** The fights on one world: {@code west}, {@code bigboi}. */
    public List<ScheduledFight> on(String world) {
        List<ScheduledFight> out = new ArrayList<>();
        for (ScheduledFight fight : fights) {
            if (world != null && world.equalsIgnoreCase(fight.world())) {
                out.add(fight);
            }
        }
        return out;
    }

    /** The fight over one territory of one world, or {@code null}. */
    public ScheduledFight at(String world, String number) {
        for (ScheduledFight fight : fights) {
            if (world != null && world.equalsIgnoreCase(fight.world()) && number != null
                    && number.equals(fight.territoryNumber())) {
                return fight;
            }
        }
        return null;
    }

    /** The fight with this id, as last fetched, or {@code null}. */
    public ScheduledFight byId(String id) {
        for (ScheduledFight fight : fights) {
            if (id != null && id.equals(fight.id())) {
                return fight;
            }
        }
        return null;
    }
}
