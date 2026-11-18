package com.betterloka.assistantserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Where the duel statistics are kept: a MySQL or MariaDB database, as a game panel creates one —
 * on Pterodactyl (and so icehost) the server's <b>Databases</b> tab. Its details go in
 * {@code database.json} next to {@code assistant.json}, never in the mod: the mod talks only to
 * this server, and only this server talks to the database.
 */
final class DuelDatabase {
    static final String FILE = "database.json";

    /** {@code database.json}, filled in from what the panel shows for the database. */
    static final class Settings {
        /** The panel's "Endpoint": the database host, with or without {@code :port}. */
        public String host = "";
        public int port = 3306;
        /** The database's full name as the panel shows it, prefix and all (e.g. {@code s1234_betterloka}). */
        public String name = "";
        public String user = "";
        public String password = "";
        /** Instead of the above: a whole JDBC address, e.g. {@code jdbc:mariadb://host:3306/name}. */
        public String jdbcUrl = "";

        boolean configured() {
            return !jdbcUrl.isBlank() || (!host.isBlank() && !name.isBlank() && !user.isBlank());
        }

        /** The JDBC address; the user and password travel separately, never in it. */
        String url() {
            if (!jdbcUrl.isBlank()) {
                return jdbcUrl.strip();
            }
            String where = host.strip();
            int at = where.lastIndexOf(':');
            String hostOnly = where;
            int portNumber = port;
            if (at > 0 && where.substring(at + 1).matches("\\d{1,5}")) {
                hostOnly = where.substring(0, at);
                portNumber = Integer.parseInt(where.substring(at + 1));
            }
            return "jdbc:mariadb://" + hostOnly + ":" + portNumber + "/" + name.strip()
                    + "?connectTimeout=5000&socketTimeout=15000";
        }
    }

    private DuelDatabase() {
    }

    /**
     * Reads {@code database.json}; with none, writes an empty one to fill in and returns it, so the
     * statistics stay off until it is filled.
     */
    static Settings load(Path dir) {
        Path file = dir.resolve(FILE);
        boolean existed = Files.exists(file);
        Settings settings = Json.read(file, Settings.class, Settings::new);
        if (settings.host == null) {
            settings.host = "";
        }
        if (settings.name == null) {
            settings.name = "";
        }
        if (settings.user == null) {
            settings.user = "";
        }
        if (settings.password == null) {
            settings.password = "";
        }
        if (settings.jdbcUrl == null) {
            settings.jdbcUrl = "";
        }
        if (!existed) {
            Json.write(file, settings);
            Log.info("Wrote an empty " + file.toAbsolutePath() + ": fill it in to keep duel statistics.");
        }
        return settings;
    }

    /** Connections to the database in {@code settings}. */
    static DuelStore.Connections connections(Settings settings) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The MariaDB driver is missing from the server jar", e);
        }
        String url = settings.url();
        Properties login = new Properties();
        login.setProperty("user", settings.user.strip());
        login.setProperty("password", settings.password);
        return new DuelStore.Connections() {
            @Override
            public Connection open() throws SQLException {
                return DriverManager.getConnection(url, login);
            }

            @Override
            public String toString() {
                // Where, never who or with what password.
                return url.replaceAll("\\?.*$", "");
            }
        };
    }
}
