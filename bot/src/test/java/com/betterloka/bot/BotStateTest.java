package com.betterloka.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bot pings a role, so being wrong here is loud. A fallen town stays fallen until the server
 * clears the claim, which means every sweep sees it again — without this memory the role would be
 * pinged with the same dozen towns every five minutes, and the channel would be muted within a day.
 */
class BotStateTest {

    @Test
    void announcesSomethingOnlyOnce(@TempDir Path dir) {
        BotState state = new BotState(dir.resolve("state.json"));
        assertTrue(state.markSeen(List.of("town|north/74")), "the first sighting is news");
        assertFalse(state.markSeen(List.of("town|north/74")), "the second is not");
    }

    @Test
    void countsATownLosingMoreGroundAsNewsAgain(@TempDir Path dir) {
        BotState state = new BotState(dir.resolve("state.json"));
        state.markSeen(List.of("town|north/74"));
        assertTrue(state.markSeen(List.of("town|north/74", "town|north/75")),
                "a territory nobody has been told about is worth a ping even for a known town");
    }

    @Test
    void remembersAcrossARestart(@TempDir Path dir) {
        Path file = dir.resolve("state.json");
        BotState first = new BotState(file);
        first.markSeen(List.of("town|north/74"));
        first.clearFirstRun();
        first.save();

        BotState second = new BotState(file);
        second.load();
        assertFalse(second.isFirstRun(), "a restart is not a first run");
        assertFalse(second.markSeen(List.of("town|north/74")), "and it must not re-announce");
        assertEquals(1, second.announcedCount());
    }

    @Test
    void startsOutKnowingItHasNoBaseline(@TempDir Path dir) {
        BotState state = new BotState(dir.resolve("absent.json"));
        state.load();
        assertTrue(state.isFirstRun(),
                "without this the backlog of long-dead towns would all be announced as fresh");
    }
}
