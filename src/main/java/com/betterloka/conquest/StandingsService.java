package com.betterloka.conquest;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The conquest standings, kept current while somebody is looking at them.
 *
 * <p>Alliances hold nearly all the Strength there is, and Loka lists them all in one request of a
 * few kilobytes, so that is asked for afresh — at most once a minute, and only while the calculator
 * or the map's fights are on screen. The towns come from the roster the mod already keeps, which can
 * be hours old: fine for a town's name, continent and level, not for the Strength of a town in no
 * alliance. So the few such towns that matter — the ones being asked about, and any that reach the
 * top of a continent — are read again one at a time.
 */
public final class StandingsService {
    /** How often the alliances are read again while somebody is looking. */
    public static final long TTL_MILLIS = 60_000;
    /** How long one town's own reading stays good. */
    private static final long TOWN_TTL_MILLIS = 2 * 60_000;
    /** How far down a continent a town in no alliance is worth reading again. */
    private static final int TOP = 6;
    /** One refresh never reads more single towns than this. */
    private static final int MAX_TOWN_READS = 8;

    /** Where the readings come from: the game API in the mod, fixtures in a test. */
    public interface Source {
        List<LokaAlliance> alliances() throws ApiException;

        /** Every living town, as fresh as the roster is. */
        List<LokaTown> towns();

        /** One town, read now. */
        LokaTown town(String townId) throws ApiException;
    }

    private record Fresh(LokaTown town, long at) {
    }

    private final Source source;
    private final Executor executor;
    private final Map<String, Fresh> fresh = new ConcurrentHashMap<>();
    private final Set<String> watched = ConcurrentHashMap.newKeySet();

    private volatile Standings standings = Standings.EMPTY;
    private volatile long updatedAt;
    private volatile long lastRequestAt;
    private volatile boolean inFlight;
    private volatile boolean failed;

    public StandingsService(Source source, Executor executor) {
        this.source = source;
        this.executor = executor;
    }

    /** Reads the standings again if the last reading is old enough. Safe to call every frame. */
    public void poll(long now) {
        if (inFlight || now - lastRequestAt < TTL_MILLIS) {
            return;
        }
        inFlight = true;
        lastRequestAt = now;
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException e) {
            inFlight = false;
        }
    }

    /** Reads again at the next poll, whatever the time. */
    public void refreshSoon() {
        lastRequestAt = 0;
    }

    /** Keeps this town's own Strength fresh from now on, in case it is in no alliance. */
    public void watch(String townId) {
        if (townId != null && watched.add(townId)) {
            refreshSoon();
        }
    }

    public Standings standings() {
        return standings;
    }

    /** When the standings were last read, or 0 before the first reading. */
    public long updatedAt() {
        return updatedAt;
    }

    public boolean loading() {
        return inFlight && updatedAt == 0;
    }

    /** Whether the last attempt failed; the reading before it is still served. */
    public boolean failed() {
        return failed;
    }

    /** One refresh, on the calling thread. */
    void refresh() {
        try {
            List<LokaAlliance> alliances = source.alliances();
            List<LokaTown> roster = source.towns();
            long now = System.currentTimeMillis();
            Standings built = Standings.of(alliances, overlay(roster, now));

            int reads = 0;
            for (String townId : worthReading(built)) {
                Fresh known = fresh.get(townId);
                if (known != null && now - known.at() < TOWN_TTL_MILLIS) {
                    continue;
                }
                if (reads++ >= MAX_TOWN_READS) {
                    break;
                }
                try {
                    LokaTown town = source.town(townId);
                    if (town != null && town.id() != null) {
                        fresh.put(town.id(), new Fresh(town, now));
                    }
                } catch (ApiException e) {
                    BetterLoka.LOGGER.debug("Could not read town {}", townId, e);
                }
            }
            if (reads > 0) {
                built = Standings.of(alliances, overlay(roster, now));
            }
            standings = built;
            updatedAt = now;
            failed = false;
        } catch (ApiException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the conquest standings", e);
            failed = true;
        } finally {
            inFlight = false;
        }
    }

    /** The roster with every town read on its own since put in place of its older self. */
    private List<LokaTown> overlay(List<LokaTown> roster, long now) {
        List<LokaTown> towns = new ArrayList<>(roster.size());
        Set<String> seen = new LinkedHashSet<>();
        for (LokaTown town : roster) {
            Fresh newer = town.id() == null ? null : fresh.get(town.id());
            towns.add(newer != null ? newer.town() : town);
            seen.add(town.id());
        }
        for (Fresh each : fresh.values()) {
            if (!seen.contains(each.town().id())) {
                towns.add(each.town());
            }
        }
        return towns;
    }

    /** Towns in no alliance whose own Strength is on show: the watched ones, and each continent's leaders. */
    private Set<String> worthReading(Standings built) {
        Set<String> ids = new LinkedHashSet<>();
        for (String townId : watched) {
            Standings.Side side = built.sideOfTown(townId);
            if (side == null || !side.alliance()) {
                ids.add(townId);
            }
        }
        for (String world : List.of("north", "west", "south")) {
            List<Standings.Row> ranking = built.ranking(world);
            for (Standings.Row row : ranking.subList(0, Math.min(TOP, ranking.size()))) {
                if (!row.side().alliance()) {
                    ids.add(row.side().id());
                }
            }
        }
        return ids;
    }
}
