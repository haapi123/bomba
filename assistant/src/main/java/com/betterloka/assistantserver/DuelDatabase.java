package com.betterloka.assistantserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
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
        /**
         * Instead of the above: the panel's "JDBC connection string", as it shows it —
         * {@code jdbc:mysql://user:password@host:port/name} — or one without the user and password.
         */
        public String jdbcUrl = "";

        boolean configured() {
            Settings at = resolved();
            return !at.host.isBlank() && !at.name.isBlank() && !at.user.isBlank();
        }

        /**
         * The settings with a connection string, in {@code jdbcUrl} or pasted into {@code host},
         * taken apart: what the separate fields say wins over what the string says.
         */
        Settings resolved() {
            Settings out = new Settings();
            out.host = host.strip();
            out.port = port;
            out.name = name.strip();
            out.user = user.strip();
            out.password = password;
            String string = !jdbcUrl.isBlank() ? jdbcUrl : looksLikeString(host) ? host : null;
            Parsed parsed = string == null ? null : parse(string);
            if (parsed != null) {
                out.host = parsed.host() + (parsed.port() > 0 ? ":" + parsed.port() : "");
                if (out.name.isBlank()) {
                    out.name = parsed.name();
                }
                if (out.user.isBlank() && parsed.user() != null) {
                    out.user = parsed.user();
                }
                if (out.password.isEmpty() && parsed.password() != null) {
                    out.password = parsed.password();
                }
            }
            return out;
        }

        /** The JDBC address: host, port and database only. The user and password travel separately. */
        String url() {
            Settings at = resolved();
            String where = at.host;
            int colon = where.lastIndexOf(':');
            String hostOnly = where;
            int portNumber = at.port;
            if (colon > 0 && where.substring(colon + 1).matches("\\d{1,5}")) {
                hostOnly = where.substring(0, colon);
                portNumber = Integer.parseInt(where.substring(colon + 1));
            }
            return "jdbc:mariadb://" + hostOnly + ":" + portNumber + "/" + at.name
                    + "?connectTimeout=5000&socketTimeout=15000";
        }
    }

    /** A connection string taken apart. */
    record Parsed(String user, String password, String host, int port, String name) {
    }

    private static final String[] SCHEMES = {"jdbc:mysql://", "jdbc:mariadb://", "mysql://", "mariadb://"};

    private static boolean looksLikeString(String text) {
        String lower = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        for (String scheme : SCHEMES) {
            if (lower.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code jdbc:mysql://user:password@host:port/name?options}, as panels show it. The password may
     * hold any character but {@code @} ends it only where the host begins: the last {@code @} is the
     * one before the host. Options are dropped. {@code null} if it is not such a string.
     */
    static Parsed parse(String text) {
        String rest = text.strip();
        String lower = rest.toLowerCase(Locale.ROOT);
        boolean known = false;
        for (String scheme : SCHEMES) {
            if (lower.startsWith(scheme)) {
                rest = rest.substring(scheme.length());
                known = true;
                break;
            }
        }
        if (!known) {
            return null;
        }
        String user = null;
        String password = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String info = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int colon = info.indexOf(':');
            user = colon < 0 ? info : info.substring(0, colon);
            password = colon < 0 ? null : info.substring(colon + 1);
        }
        int question = rest.indexOf('?');
        if (question >= 0) {
            rest = rest.substring(0, question);
        }
        String name = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            name = rest.substring(slash + 1);
            rest = rest.substring(0, slash);
        }
        String host = rest;
        int port = -1;
        int colon = rest.lastIndexOf(':');
        if (colon > 0 && rest.substring(colon + 1).matches("\\d{1,5}")) {
            host = rest.substring(0, colon);
            port = Integer.parseInt(rest.substring(colon + 1));
        }
        return new Parsed(user, password, host, port, name);
    }

    /**
     * A message with the password and any {@code user:password@} in it blanked out. The driver puts
     * the whole address it was given into some of its errors, and those go to the console.
     */
    static String redact(String message, String password) {
        if (message == null) {
            return "";
        }
        String out = message;
        if (password != null && !password.isEmpty()) {
            out = out.replace(password, "***");
        }
        out = out.replaceAll("//[^/\\s]*@", "//***@");
        return out.replaceAll("(?i)(password=)[^&\\s]*", "$1***");
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

    /** Connections to the database in {@code settings}. Their errors never carry the password. */
    static DuelStore.Connections connections(Settings settings) {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The MariaDB driver is missing from the server jar", e);
        }
        Settings at = settings.resolved();
        String url = settings.url();
        String password = at.password;
        Properties login = new Properties();
        login.setProperty("user", at.user);
        login.setProperty("password", password);
        return new DuelStore.Connections() {
            @Override
            public Connection open() throws SQLException {
                try {
                    return DriverManager.getConnection(url, login);
                } catch (SQLException e) {
                    // Not chained: the original's message may hold what this one leaves out.
                    throw new SQLException(redact(e.getMessage(), password), e.getSQLState(), e.getErrorCode());
                }
            }

            @Override
            public String toString() {
                // Where, never who or with what password.
                return redact(url.replaceAll("\\?.*$", ""), password);
            }
        };
    }
}
