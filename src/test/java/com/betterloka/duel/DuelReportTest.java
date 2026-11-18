package com.betterloka.duel;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What goes to the duel statistics, and what does not go at all. */
class DuelReportTest {
    private static final UUID ME = new UUID(1, 1);
    private static final UUID RIVAL = new UUID(2, 1);

    private static DuelLog.Entry entry(String opponentName, UUID opponent, int myHits, int theirHits) {
        DuelLog.Side mine = new DuelLog.Side(12, myHits, 3, 1, 4, 6);
        DuelLog.Side theirs = new DuelLog.Side(9, theirHits, 1, 0, 2, null);
        DuelLog.Ping ping = new DuelLog.Ping(3, 85, 120, 1, 1, 0, 35, 10);
        return new DuelLog.Entry(7, 1_763_000_000_000L, "Me", ME.toString(), opponentName,
                opponent == null ? null : opponent.toString(), "WON", 42_000, mine, theirs, DuelLog.Ping.NONE, ping,
                "pot", "profile");
    }

    @Test
    void bothSidesAndTheResultButNothingAboutWhoSent() {
        JsonObject report = DuelReport.of(entry("Knucior", RIVAL, 8, 5), "0.58.0");
        assertEquals("0.58.0", report.get("mod").getAsString());
        assertEquals(1_763_000_000_000L, report.get("duelAt").getAsLong());
        assertEquals("WON", report.get("result").getAsString());
        assertEquals("pot", report.get("ladder").getAsString());
        assertEquals(RIVAL.toString(), report.getAsJsonObject("opponent").get("uuid").getAsString());
        assertEquals(8, report.getAsJsonObject("you").get("hits").getAsInt());
        assertEquals(6, report.getAsJsonObject("you").get("pots").getAsInt());
        assertFalse(report.getAsJsonObject("you").has("ping"), "no ping read: none sent");
        assertFalse(report.getAsJsonObject("them").has("pots"), "not counted: not sent");
        assertEquals(85, report.getAsJsonObject("them").getAsJsonObject("ping").get("avg").getAsInt());
        assertFalse(report.toString().contains(ME.toString()), "the server knows the sender from the login");
        assertFalse(report.toString().contains("\"Me\""));
    }

    @Test
    void whatIsNotSent() {
        assertNull(DuelReport.of(entry("Knucior", null, 8, 5), "0.58.0"), "no account for the opponent");
        assertNull(DuelReport.of(entry("?", RIVAL, 8, 5), "0.58.0"));
        assertNull(DuelReport.of(entry("Knucior", RIVAL, 0, 0), "0.58.0"), "nobody hit anybody");
        assertTrue(DuelReport.playedBy(entry("Knucior", RIVAL, 1, 1), ME));
        assertFalse(DuelReport.playedBy(entry("Knucior", RIVAL, 1, 1), RIVAL), "an alt's duel");
    }
}
