package com.betterloka.assistantserver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The duel statistics' two requests: a player's mod reporting a duel it counted, and the numbers
 * kept about a player. Both need the player's login, so every report is from a real, verified
 * Minecraft account, and a few limits keep one account from filling the table.
 */
final class DuelService {
    static final int PER_HOUR = 40;
    static final int PER_DAY = 300;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,16}");

    private final DuelStore store;
    private final Clock clock;

    /** @param store {@code null} when no database is set up: every request then says so */
    DuelService(DuelStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    boolean enabled() {
        return store != null;
    }

    /** {@code POST /v1/duels/report}: one duel, as the player's mod counted it. */
    AssistantService.Response report(Sessions.Session player, JsonObject body) {
        if (store == null) {
            return off();
        }
        DuelStore.Report report;
        try {
            report = parse(player, body, clock.millis());
        } catch (IllegalArgumentException bad) {
            return AssistantService.error(400, "bad_report", bad.getMessage());
        }
        try {
            long now = clock.millis();
            if (store.reportedSince(report.reporter(), now - 3_600_000L) >= PER_HOUR
                    || store.reportedSince(report.reporter(), now - 86_400_000L) >= PER_DAY) {
                return AssistantService.error(429, "slow_down", "Too many duels reported; try again later.");
            }
            boolean kept = store.save(report);
            JsonObject out = AssistantService.ok();
            out.addProperty("kept", kept);
            return new AssistantService.Response(200, out);
        } catch (SQLException e) {
            Log.warn("Could not keep a duel report: " + e.getMessage());
            return AssistantService.error(503, "database", "The duel database is not answering.");
        }
    }

    /** {@code POST /v1/duels/player} with {@code {"uuid": ...}}: what has been seen of one player. */
    AssistantService.Response player(JsonObject body) {
        if (store == null) {
            return off();
        }
        String uuid;
        try {
            uuid = uuid(text(body, "uuid"));
        } catch (IllegalArgumentException bad) {
            return AssistantService.error(400, "bad_uuid", "No such player id.");
        }
        try {
            DuelStore.Stats stats = store.stats(uuid);
            JsonObject out = AssistantService.ok();
            out.addProperty("uuid", uuid);
            out.addProperty("observations", stats.observations());
            out.addProperty("reporters", stats.reporters());
            out.addProperty("ping", stats.pingAvg());
            out.addProperty("pingDuels", stats.pingDuels());
            out.addProperty("accuracy", stats.accuracy());
            out.addProperty("critShare", stats.critShare());
            out.addProperty("sweepShare", stats.sweepShare());
            out.addProperty("combo", stats.combo());
            out.addProperty("pots", stats.pots());
            out.addProperty("potDuels", stats.potDuels());
            out.addProperty("lastAt", stats.lastAt());
            return new AssistantService.Response(200, out);
        } catch (SQLException e) {
            Log.warn("Could not read duel statistics: " + e.getMessage());
            return AssistantService.error(503, "database", "The duel database is not answering.");
        }
    }

    private static AssistantService.Response off() {
        return AssistantService.error(503, "duels_off", "This server keeps no duel statistics.");
    }

    /** A report as the mod sends it, checked: anything out of what a duel can be is refused. */
    static DuelStore.Report parse(Sessions.Session player, JsonObject body, long now) {
        String reporter = uuid(player.uuid());
        JsonObject opponent = object(body, "opponent");
        String opponentUuid = uuid(text(opponent, "uuid"));
        if (opponentUuid.equals(reporter)) {
            throw new IllegalArgumentException("A duel against yourself.");
        }
        String opponentName = text(opponent, "name");
        if (opponentName == null || !NAME.matcher(opponentName).matches()) {
            throw new IllegalArgumentException("The opponent's name is not a Minecraft name.");
        }
        long duelAt = number(body, "duelAt", 0, Long.MAX_VALUE);
        if (duelAt < now - 3L * 86_400_000L || duelAt > now + 10 * 60_000L) {
            throw new IllegalArgumentException("The duel's time is off.");
        }
        int millis = (int) number(body, "millis", 0, 30 * 60_000L);
        String result = text(body, "result");
        char outcome = switch (result == null ? "" : result.toUpperCase(Locale.ROOT)) {
            case "WON" -> 'W';
            case "LOST" -> 'L';
            case "UNKNOWN" -> 'U';
            default -> throw new IllegalArgumentException("The result is WON, LOST or UNKNOWN.");
        };
        String ladder = text(body, "ladder");
        if (ladder != null && !ladder.equals("pot") && !ladder.equals("barebones")) {
            throw new IllegalArgumentException("No such ladder.");
        }
        String version = text(body, "mod");
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("The mod's version is missing.");
        }
        String reporterName = player.name() != null && NAME.matcher(player.name()).matches() ? player.name() : "?";
        return new DuelStore.Report(reporter, reporterName, opponentUuid, opponentName, duelAt, millis, outcome, ladder,
                side(object(body, "you")), side(object(body, "them")), version, now);
    }

    private static DuelStore.Side side(JsonObject side) {
        int swings = (int) number(side, "swings", 0, 5000);
        int hits = (int) number(side, "hits", 0, 2000);
        int crits = (int) number(side, "crits", 0, hits);
        int sweeps = (int) number(side, "sweeps", 0, hits);
        int combo = (int) number(side, "combo", 0, hits);
        Integer pots = optional(side, "pots", 0, 64);
        JsonObject ping = side.has("ping") && side.get("ping").isJsonObject() ? side.getAsJsonObject("ping") : null;
        Integer avg = ping == null ? null : optional(ping, "avg", 0, 10_000);
        Integer max = ping == null ? null : optional(ping, "max", 0, 10_000);
        Integer spikes = ping == null ? null : optional(ping, "spikes", 0, 1000);
        int readings = ping == null ? 0 : (int) number(ping, "readings", 0, 10_000);
        if (avg == null) {
            max = null;
            spikes = null;
            readings = 0;
        }
        return new DuelStore.Side(swings, hits, crits, sweeps, combo, pots, avg, max, spikes, readings);
    }

    private static String uuid(String raw) {
        try {
            if (raw == null) {
                throw new IllegalArgumentException("A player id is missing.");
            }
            String plain = raw.strip().replace("-", "");
            if (!plain.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Not a player id.");
            }
            return new UUID(Long.parseUnsignedLong(plain.substring(0, 16), 16),
                    Long.parseUnsignedLong(plain.substring(16), 16)).toString();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a player id.");
        }
    }

    private static JsonObject object(JsonObject body, String key) {
        JsonElement value = body == null ? null : body.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("\"" + key + "\" is missing.");
        }
        return value.getAsJsonObject();
    }

    private static String text(JsonObject body, String key) {
        JsonElement value = body == null ? null : body.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static long number(JsonObject body, String key, long min, long max) {
        JsonElement value = body.get(key);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("\"" + key + "\" is missing.");
            }
            long number = value.getAsLong();
            if (number < min || number > max || value.getAsDouble() != number) {
                throw new IllegalArgumentException("\"" + key + "\" is out of range.");
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + key + "\" is not a number.");
        }
    }

    private static Integer optional(JsonObject body, String key, long min, long max) {
        JsonElement value = body.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return (int) number(body, key, min, max);
    }
}
