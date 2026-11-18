package com.betterloka.duel;

import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One finished duel as it goes to the BetterLoka server's duel statistics: what this mod counted of
 * both sides — swings, hits, crits, sweeps, best combo, pots and ping — the result, the ladder, the
 * opponent's name and account. The player is who the server knows from their login; nothing here
 * says who they are. Nothing else from the game goes with it.
 */
public final class DuelReport {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private DuelReport() {
    }

    /**
     * The report for {@code entry}, or {@code null} if it is not one to send: no opponent account
     * known, a name that is not a Minecraft one, or nobody hit anybody.
     */
    public static JsonObject of(DuelLog.Entry entry, String modVersion) {
        if (entry == null || entry.opponentUuid() == null || entry.opponentName() == null
                || !NAME.matcher(entry.opponentName()).matches()) {
            return null;
        }
        if (entry.you().hits() + entry.them().hits() == 0) {
            return null;
        }
        JsonObject body = new JsonObject();
        body.addProperty("mod", modVersion == null || modVersion.isBlank() ? "unknown" : modVersion);
        // When the duel was kept: one number per duel, so a report sent twice is kept once.
        body.addProperty("duelAt", entry.at());
        body.addProperty("millis", Math.min(entry.millis(), 30 * 60_000L));
        body.addProperty("result", entry.outcome().name());
        if (entry.ladder() != null) {
            body.addProperty("ladder", entry.ladder());
        }
        JsonObject opponent = new JsonObject();
        opponent.addProperty("uuid", entry.opponentUuid().toString());
        opponent.addProperty("name", entry.opponentName());
        body.add("opponent", opponent);
        body.add("you", side(entry.you(), entry.yourPing()));
        body.add("them", side(entry.them(), entry.theirPing()));
        return body;
    }

    /** Whether {@code entry} was played on the account that is logged in now. */
    public static boolean playedBy(DuelLog.Entry entry, UUID account) {
        return entry != null && account != null && account.equals(entry.selfUuid());
    }

    private static JsonObject side(DuelLog.Side side, DuelLog.Ping ping) {
        JsonObject out = new JsonObject();
        int hits = side.hits();
        out.addProperty("swings", side.swings());
        out.addProperty("hits", hits);
        out.addProperty("crits", Math.min(side.crits(), hits));
        out.addProperty("sweeps", Math.min(side.sweeps(), hits));
        out.addProperty("combo", Math.min(side.combo(), hits));
        if (side.pots() != null) {
            out.addProperty("pots", side.pots());
        }
        if (ping != null && ping.known()) {
            JsonObject p = new JsonObject();
            p.addProperty("avg", ping.average());
            p.addProperty("max", Math.max(ping.max(), ping.average()));
            p.addProperty("spikes", ping.spikes());
            p.addProperty("readings", ping.readings());
            out.add("ping", p);
        }
        return out;
    }
}
