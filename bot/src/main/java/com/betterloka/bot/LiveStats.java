package com.betterloka.bot;

import com.betterloka.api.ApiException;
import com.betterloka.api.ArenaApi;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.BattleZone;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.ScheduledFight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loka right now, for the stat channels: who is on, how many fights are on, who has the most
 * conquest kills and damage this month, and who tops ranked potion.
 *
 * <ul>
 *   <li><b>Players</b>: the server's own answer to the Minecraft server list ping, or a public status
 *       service when it cannot be reached.</li>
 *   <li><b>Fights</b>: Loka's declared battles, those started and those still to come.</li>
 *   <li><b>Kills and damage</b>: this month's finished battles, from Loka's battle archive; read
 *       newest first, so after the first sweep a refresh costs a request or two.</li>
 *   <li><b>Ranked</b>: the top of Loka's current potion ladder.</li>
 * </ul>
 *
 * <p>Each is asked for on its own: one that fails keeps its last answer, so a tile shows what was
 * last known rather than an error.
 */
final class LiveStats {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");
    /** A month of battles is a few hundred, twenty a page; this is only a guard. */
    private static final int MAX_PAGES = 80;

    record Players(int online, int max, boolean offline) {
    }

    record Fights(int now, int declared) {
    }

    /** A player at the top of something, by name, and their figure. */
    record Named(String name, double value) {
    }

    record Ranked(String name, String rank) {
    }

    /**
     * Everything the tiles show. A part is {@code null} until it has been read once; {@code kills}
     * and {@code damage} are {@code Named("", 0)} when nobody has any this month yet.
     */
    record Snapshot(Players players, Fights fights, Named kills, Named damage, Ranked potion, YearMonth month) {
    }

    private final String serverAddress;
    private final boolean statusFallback;
    private final LokaApi loka;
    private final ArenaApi arena;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final MonthTally tally;
    private final Map<UUID, String> names = new HashMap<>();

    private boolean caughtUp;
    private YearMonth sweptMonth;
    /** Where the month's tally is kept between restarts, or {@code null}. */
    private final java.nio.file.Path tallyFile;
    /** The last reason the potion ladder could not be read, said once rather than every refresh. */
    private String potionFailure;
    private Players players;
    private Fights fights;
    private Ranked potion;

    LiveStats(String serverAddress, boolean statusFallback, ZoneId zone, boolean countRivina, LokaApi loka, ArenaApi arena) {
        this(serverAddress, statusFallback, zone, countRivina, loka, arena, null);
    }

    /** @param tallyFile where the month's tally is kept between restarts; {@code null} to keep it only in memory */
    LiveStats(String serverAddress, boolean statusFallback, ZoneId zone, boolean countRivina, LokaApi loka, ArenaApi arena,
              java.nio.file.Path tallyFile) {
        this.serverAddress = serverAddress;
        this.statusFallback = statusFallback;
        this.loka = loka;
        this.arena = arena;
        this.tally = new MonthTally(zone, countRivina);
        this.tallyFile = tallyFile;
        long now = System.currentTimeMillis();
        if (tallyFile != null) {
            // Carry on from the last run: read only what is newer than what it had counted.
            caughtUp = tally.load(tallyFile, now);
            sweptMonth = tally.month(now);
            if (tally.battles() > 0) {
                LOG.info("Stat channels: {} battle(s) of {} already counted", tally.battles(), sweptMonth);
            }
        }
    }

    /** Asks every source again; whatever fails keeps what it had. */
    synchronized Snapshot refresh(long now) {
        readPlayers();
        readFights();
        readMonth(now);
        readPotion();
        return snapshot(now);
    }

    synchronized Snapshot snapshot(long now) {
        YearMonth month = tally.month(now);
        return new Snapshot(players, fights, named(tally.topKills()), named(tally.topDamage()), potion, month);
    }

    private void readPlayers() {
        try {
            ServerPing.Status status = ServerPing.ping(serverAddress, 5000);
            players = new Players(status.online(), status.max(), false);
            return;
        } catch (Exception e) {
            LOG.debug("Server list ping to {} failed: {}", serverAddress, e.toString());
        }
        if (!statusFallback) {
            return;
        }
        try {
            ServerPing.Status status = ServerPing.viaStatusService(http, serverAddress);
            players = status == null ? new Players(0, 0, true) : new Players(status.online(), status.max(), false);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Could not tell how many are on {}: {}", serverAddress, e.getMessage());
        }
    }

    private void readFights() {
        try {
            int started = 0;
            int declared = 0;
            for (ScheduledFight fight : loka.fetchScheduledFights()) {
                if (fight.started()) {
                    started++;
                } else {
                    declared++;
                }
            }
            fights = new Fights(started, declared);
        } catch (ApiException | RuntimeException e) {
            LOG.warn("Could not read Loka's fights: {}", e.getMessage());
        }
    }

    /**
     * Reads the battles that ended since the last read, newest first. The first read of a month goes
     * back to its first day; after that it stops at the first battle already counted.
     */
    private void readMonth(long now) {
        YearMonth month = tally.month(now);
        if (!month.equals(sweptMonth)) {
            // A new month (or the first read): back to its first day.
            sweptMonth = month;
            caughtUp = false;
        }
        long start = tally.monthStart(now);
        int counted = 0;
        try {
            pages:
            for (int page = 0; page < MAX_PAGES; page++) {
                LokaApi.BattlePage battles = loka.fetchBattlePage(page);
                if (battles.battles().isEmpty()) {
                    caughtUp = true;
                    break;
                }
                for (BattleZone battle : battles.battles()) {
                    if (battle.active() || battle.timeEnded() <= 0) {
                        continue;
                    }
                    if (battle.timeEnded() < start) {
                        caughtUp = true;
                        break pages;
                    }
                    if (tally.has(battle)) {
                        if (caughtUp) {
                            break pages;
                        }
                        continue;
                    }
                    if (tally.add(battle, now)) {
                        counted++;
                    }
                }
                if (page + 1 >= battles.totalPages()) {
                    caughtUp = true;
                    break;
                }
            }
        } catch (ApiException | RuntimeException e) {
            // What was counted stays counted; the next read carries on from the newest again, and
            // goes all the way back while it has not yet reached the month's first battle.
            LOG.warn("Could not read Loka's battles: {}", e.getMessage());
        }
        if (counted > 0) {
            LOG.info("Stat channels: {} new battle(s) counted for {} ({} this month)", counted, tally.month(now),
                    tally.battles());
        }
        if (counted > 0 || caughtUp) {
            tally.save(tallyFile, caughtUp);
        }
    }

    private void readPotion() {
        try {
            List<ArenaEntry> ladder = arena.fetchCurrent(ArenaApi.Ladder.POTION);
            if (!ladder.isEmpty()) {
                ArenaEntry top = ladder.get(0);
                potion = new Ranked(top.name(), top.rank() == null ? "" : top.rank().toString());
            }
            if (potionFailure != null) {
                LOG.info("The potion ladder can be read again");
                potionFailure = null;
            }
        } catch (ApiException | RuntimeException e) {
            // Said once: Loka's ladder site refusing the bot (HTTP 403) does not change every ten minutes.
            String reason = String.valueOf(e.getMessage());
            if (!reason.equals(potionFailure)) {
                LOG.warn("Could not read the potion ladder: {} (said once; the tile keeps its last value)", reason);
                potionFailure = reason;
            } else {
                LOG.debug("Could not read the potion ladder: {}", reason);
            }
        }
    }

    private Named named(MonthTally.Leader leader) {
        if (leader == null) {
            return new Named("", 0);
        }
        return new Named(nameOf(leader.uuid()), leader.value());
    }

    /** Battle records name nobody; the leader's name is asked for once and kept. */
    private String nameOf(UUID uuid) {
        String known = names.get(uuid);
        if (known != null) {
            return known;
        }
        try {
            LokaPlayer player = loka.findPlayerByUuid(uuid);
            if (player != null && player.name() != null) {
                names.put(uuid, player.name());
                return player.name();
            }
        } catch (ApiException | RuntimeException e) {
            LOG.debug("No name for {}: {}", uuid, e.getMessage());
        }
        return uuid.toString().substring(0, 8);
    }
}
