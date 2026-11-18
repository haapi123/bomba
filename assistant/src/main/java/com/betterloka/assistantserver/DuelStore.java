package com.betterloka.assistantserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * The duel statistics in the database: one row per side of each duel a player's mod reported.
 *
 * <p>A report is a duel as one player's mod saw it — their own swings, hits, crits, sweeps, combo,
 * pots and ping, and their opponent's — so it becomes two rows: the reporter's side
 * ({@code self_report} true) and the opponent's ({@code self_report} false). A player's numbers are
 * then everything anybody's mod saw of them. If both players run the mod, the same duel is in twice,
 * once from each side; the counts say "seen in N duels by M players" for that reason.
 *
 * <p>Plain SQL that MySQL, MariaDB and H2 (in MySQL mode, for the tests) all take.
 */
final class DuelStore {
    /** How many of a player's latest rows their numbers are worked out from: their recent form. */
    static final int RECENT = 200;

    /** Opens a connection to the database; closing it hands it back. */
    interface Connections {
        Connection open() throws SQLException;
    }

    /** One side of a reported duel. */
    record Side(int swings, int hits, int crits, int sweeps, int combo, Integer pots,
                Integer pingAvg, Integer pingMax, Integer pingSpikes, int pingReadings) {
    }

    /**
     * One duel as one player's mod saw it.
     *
     * @param result the reporter's: {@code W}, {@code L} or {@code U}
     */
    record Report(String reporter, String reporterName, String opponent, String opponentName, long duelAt,
                  int millis, char result, String ladder, Side you, Side them, String modVersion, long receivedAt) {
    }

    /** What the rows say about one player. Shares are 0–100, or -1 with nothing to go on. */
    record Stats(int observations, int reporters, int pingAvg, int pingDuels, int accuracy, int critShare,
                 int sweepShare, double combo, double pots, int potDuels, long lastAt) {
    }

    private static final String TABLE = """
            CREATE TABLE IF NOT EXISTS duel_sides (
              id BIGINT NOT NULL AUTO_INCREMENT,
              reporter CHAR(36) NOT NULL,
              duel_at BIGINT NOT NULL,
              player CHAR(36) NOT NULL,
              player_name VARCHAR(16) NOT NULL,
              opponent CHAR(36) NOT NULL,
              opponent_name VARCHAR(16) NOT NULL,
              self_report BOOLEAN NOT NULL,
              result CHAR(1) NOT NULL,
              ladder VARCHAR(16) NULL,
              duration_ms INT NOT NULL,
              swings INT NOT NULL,
              hits INT NOT NULL,
              crits INT NOT NULL,
              sweeps INT NOT NULL,
              best_combo INT NOT NULL,
              pots INT NULL,
              ping_avg INT NULL,
              ping_max INT NULL,
              ping_spikes INT NULL,
              ping_readings INT NOT NULL,
              mod_version VARCHAR(16) NOT NULL,
              received_at BIGINT NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uq_duel_side (reporter, duel_at, player),
              KEY ix_player (player, duel_at),
              KEY ix_reporter (reporter, received_at)
            )""";

    private static final String INSERT = "INSERT INTO duel_sides (reporter, duel_at, player, player_name, opponent,"
            + " opponent_name, self_report, result, ladder, duration_ms, swings, hits, crits, sweeps, best_combo, pots,"
            + " ping_avg, ping_max, ping_spikes, ping_readings, mod_version, received_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String STATS = "SELECT COUNT(*), COUNT(DISTINCT reporter),"
            + " AVG(ping_avg), COUNT(ping_avg),"
            + " SUM(CASE WHEN swings >= hits AND swings > 0 THEN hits ELSE 0 END),"
            + " SUM(CASE WHEN swings >= hits AND swings > 0 THEN swings ELSE 0 END),"
            + " SUM(hits), SUM(crits), SUM(sweeps), AVG(best_combo), AVG(pots), COUNT(pots), MAX(duel_at)"
            + " FROM (SELECT * FROM duel_sides WHERE player = ? ORDER BY duel_at DESC LIMIT " + RECENT + ") recent";

    private final Connections connections;

    DuelStore(Connections connections) {
        this.connections = connections;
    }

    /** Makes the table if it is not there yet. */
    void createTables() throws SQLException {
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute(TABLE);
        }
    }

    /** Whether the database answers. */
    boolean reachable() {
        try (Connection connection = connections.open()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Keeps a report as its two sides.
     *
     * @return {@code false} if it was already kept (the mod sent it again)
     */
    boolean save(Report report) throws SQLException {
        try (Connection connection = connections.open()) {
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                char theirs = report.result() == 'W' ? 'L' : report.result() == 'L' ? 'W' : 'U';
                bind(insert, report, report.reporter(), report.reporterName(), report.opponent(), report.opponentName(),
                        true, report.result(), report.you());
                insert.executeUpdate();
                bind(insert, report, report.opponent(), report.opponentName(), report.reporter(), report.reporterName(),
                        false, theirs, report.them());
                insert.executeUpdate();
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                if (duplicate(e)) {
                    return false;
                }
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
        }
    }

    /** How many duels the player reported since {@code since}, for the limits. */
    int reportedSince(String reporter, long since) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM duel_sides WHERE reporter = ? AND self_report = TRUE AND received_at > ?")) {
            query.setString(1, reporter);
            query.setLong(2, since);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /** Everything seen of one player in their latest {@value #RECENT} rows. */
    Stats stats(String player) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement query = connection.prepareStatement(STATS)) {
            query.setString(1, player);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next() || rows.getInt(1) == 0) {
                    return new Stats(0, 0, -1, 0, -1, -1, -1, 0, 0, 0, 0);
                }
                int observations = rows.getInt(1);
                int reporters = rows.getInt(2);
                double ping = rows.getDouble(3);
                int pingDuels = rows.getInt(4);
                long accHits = rows.getLong(5);
                long accSwings = rows.getLong(6);
                long hits = rows.getLong(7);
                long crits = rows.getLong(8);
                long sweeps = rows.getLong(9);
                double combo = rows.getDouble(10);
                double pots = rows.getDouble(11);
                int potDuels = rows.getInt(12);
                long lastAt = rows.getLong(13);
                return new Stats(observations, reporters, pingDuels == 0 ? -1 : (int) Math.round(ping), pingDuels,
                        share(accHits, accSwings), share(crits, hits), share(sweeps, hits),
                        Math.round(combo * 10) / 10.0, potDuels == 0 ? 0 : Math.round(pots * 10) / 10.0, potDuels, lastAt);
            }
        }
    }

    private static int share(long part, long whole) {
        return whole <= 0 ? -1 : (int) Math.round(part * 100.0 / whole);
    }

    private static void bind(PreparedStatement insert, Report report, String player, String playerName, String opponent,
                             String opponentName, boolean self, char result, Side side) throws SQLException {
        insert.setString(1, report.reporter());
        insert.setLong(2, report.duelAt());
        insert.setString(3, player);
        insert.setString(4, playerName);
        insert.setString(5, opponent);
        insert.setString(6, opponentName);
        insert.setBoolean(7, self);
        insert.setString(8, String.valueOf(result));
        if (report.ladder() == null) {
            insert.setNull(9, Types.VARCHAR);
        } else {
            insert.setString(9, report.ladder());
        }
        insert.setInt(10, report.millis());
        insert.setInt(11, side.swings());
        insert.setInt(12, side.hits());
        insert.setInt(13, side.crits());
        insert.setInt(14, side.sweeps());
        insert.setInt(15, side.combo());
        nullable(insert, 16, side.pots());
        nullable(insert, 17, side.pingAvg());
        nullable(insert, 18, side.pingMax());
        nullable(insert, 19, side.pingSpikes());
        insert.setInt(20, side.pingReadings());
        insert.setString(21, report.modVersion());
        insert.setLong(22, report.receivedAt());
    }

    private static void nullable(PreparedStatement insert, int index, Integer value) throws SQLException {
        if (value == null) {
            insert.setNull(index, Types.INTEGER);
        } else {
            insert.setInt(index, value);
        }
    }

    /** A unique key already taken: SQL state class 23, or MySQL's own number for it. */
    private static boolean duplicate(SQLException e) {
        for (SQLException at = e; at != null; at = at.getNextException()) {
            if ((at.getSQLState() != null && at.getSQLState().startsWith("23")) || at.getErrorCode() == 1062) {
                return true;
            }
        }
        return e.getCause() instanceof SQLException cause && cause != e && duplicate(cause);
    }
}
