package com.betterloka.duel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The duels kept on this computer: last matches, the record between two players, usual ping. */
class DuelLogTest {
    private static final UUID ME = new UUID(1, 1);
    private static final UUID ALT = new UUID(1, 2);
    private static final UUID RIVAL = new UUID(2, 1);
    private static final UUID OTHER = new UUID(2, 2);

    private static DuelStats.Summary duel(boolean won) {
        DuelStats stats = new DuelStats(10, 0);
        stats.swing(10);
        stats.swing(10);
        stats.hit(10, 20, 1000);
        stats.hit(20, 10, 1500);
        stats.died(won ? 20 : 10, 2000);
        return stats.summary();
    }

    private static PingTrace.Summary ping(int ms) {
        PingTrace trace = new PingTrace();
        trace.sample(0, ms);
        return trace.summary(0, 1000);
    }

    @Test
    void keepsDuelsAcrossSessions(@TempDir Path dir) {
        Path file = dir.resolve("duels.json");
        DuelLog log = new DuelLog(file);
        DuelLog.Entry first = log.add(1, "Me", ME, "Rival", RIVAL, duel(true), ping(40), ping(90));
        DuelLog.Entry second = log.add(2, "Me", ME, "Other", OTHER, duel(false), ping(45), null);
        assertEquals(1, first.id());
        assertEquals(2, second.id());

        DuelLog again = new DuelLog(file);
        assertEquals("Rival", again.byId(1).opponentName());
        assertEquals(DuelStats.Result.LOST, again.byId(2).outcome());
        assertEquals(50, again.byId(1).you().accuracy());
        assertEquals(90, again.byId(1).theirPing().average());
        assertEquals(-1, again.byId(2).theirPing().average(), "no ping read is no ping, not 0");
        assertEquals(3, again.add(3, "Me", ME, "Rival", RIVAL, duel(true), null, null).id(), "numbers go on");
    }

    @Test
    void theRecordBetweenTwoPlayersAndTheLatestMatches() {
        DuelLog log = new DuelLog(null);
        log.add(1, "Me", ME, "Rival", RIVAL, duel(true), null, ping(80));
        log.add(2, "Me", ME, "Other", OTHER, duel(false), null, null);
        log.add(3, "Me", ME, "Rival", RIVAL, duel(false), null, ping(100));
        log.add(4, "Alt", ALT, "Rival", RIVAL, duel(true), null, null);
        log.add(5, "Me", ME, "Rival", RIVAL, duel(true), null, null);

        DuelLog.Record record = log.between(ME, RIVAL, "Rival");
        assertEquals(2, record.wins());
        assertEquals(1, record.losses(), "the alt's win over them is the alt's");
        assertEquals(List.of(5, 3, 1), record.matches().stream().map(DuelLog.Entry::id).toList(), "newest first");

        assertEquals(List.of(5, 3, 2), log.recent(ME, 3).stream().map(DuelLog.Entry::id).toList());
        assertArrayEquals(new int[]{90, 2}, log.averagePing(RIVAL, "Rival"), "over the two duels it was read in");
        assertNull(log.averagePing(OTHER, "Other"));
    }

    @Test
    void anOpponentWithNoUuidIsFoundByName() {
        DuelLog log = new DuelLog(null);
        log.add(1, "Me", ME, "Rival", null, duel(true), null, null);
        assertEquals(1, log.between(ME, null, "rival").wins());
    }

    @Test
    void onlyTheNewestAreKept() {
        DuelLog log = new DuelLog(null);
        for (int i = 0; i < DuelLog.KEEP + 5; i++) {
            log.add(i, "Me", ME, "Rival", RIVAL, duel(true), null, null);
        }
        assertNull(log.byId(1));
        assertNotNull(log.byId(DuelLog.KEEP + 5));
        assertEquals(DuelLog.KEEP, log.between(ME, RIVAL, "Rival").games());
    }

    @Test
    void theOpponentIsReadOffTheLineOnlyWhenItNamesThem() {
        assertNull(DuelMessages.opponentIn(List.of("Match Ready! [Click to join]", "Click to join"), "Me"));
        assertEquals("Knucior", DuelMessages.opponentIn(List.of("Match Ready!", "Ranked Potion vs Knucior"), "Me"));
        assertEquals("Rival_1", DuelMessages.opponentIn(List.of("Opponent: Rival_1 (Diamond III)"), "Me"));
        assertEquals("Rival", DuelMessages.opponentIn(List.of("Me vs Rival"), "Me"));
        assertNull(DuelMessages.opponentIn(List.of("Rival vs Me"), "me"), "never the player themself");
    }
}
