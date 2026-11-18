package com.betterloka.bot;

import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Against Loka itself: a restart with the state file does not read the deleted towns again. */
@EnabledIfEnvironmentVariable(named = "BETTERLOKA_LIVE", matches = "true")
class FallenTownWatcherLiveTest {
    @Test
    void aRestartReadsOnlyTheCount(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        long hour = 60 * 60_000L;
        try (HttpTransport transport = new HttpTransport()) {
            LokaApi api = new LokaApi(transport);
            BotState state = new BotState(file);
            state.load();
            FallenTownWatcher.Result first = new FallenTownWatcher(api, state).check(false, hour);
            assertTrue(first.sweptFully(), "the first run reads everything");
            System.out.println("first run: full sweep, " + first.deletedCount() + " deleted towns");

            BotState restarted = new BotState(file);
            restarted.load();
            FallenTownWatcher.Result again = new FallenTownWatcher(api, restarted).check(false, hour);
            System.out.println("after a restart: full sweep " + again.sweptFully() + ", " + again.deletedCount() + " deleted towns");
            assertFalse(again.sweptFully(), "a restart with nothing new reads only the count");
        }
    }
}
