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
    /** What stands as fallen on Loka right now, as the bot would announce it. */
    @Test
    void whatStandsFallenNow(@TempDir Path dir) throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            LokaApi api = new LokaApi(transport);
            System.out.println("deleted towns now: " + api.countDeletedTowns());
            java.util.List<com.betterloka.api.model.Territory> territories = new java.util.ArrayList<>();
            for (String world : LokaApi.CONQUEST_WORLDS) {
                java.util.List<com.betterloka.api.model.Territory> here = api.fetchTerritories(world);
                long owned = here.stream().filter(com.betterloka.api.model.Territory::isOwned).count();
                System.out.println("world " + world + ": " + here.size() + " territories, " + owned + " owned");
                territories.addAll(here);
            }
            BotState state = new BotState(dir.resolve("state.json"));
            for (com.betterloka.towns.FallenTowns.Fallen fallen : new FallenTownWatcher(api, state).current()) {
                System.out.println("FALLEN " + fallen.town().name() + " " + fallen.town().id() + " holds " + fallen.numbers());
            }
        }
    }

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
            state.save();
            System.out.println("first run: full sweep, " + first.deletedCount() + " deleted towns");

            BotState restarted = new BotState(file);
            restarted.load();
            FallenTownWatcher watcher = new FallenTownWatcher(api, restarted);
            assertTrue(watcher.knownDeletedTowns() == first.deletedCount(), "the listing comes from the state file");
            FallenTownWatcher.Result again = watcher.check(false, hour);
            System.out.println("after a restart: territories looked over " + again.sweptFully() + ", "
                    + again.deletedCount() + " deleted towns, listing not read again");
            assertTrue(again.sweptFully(), "every start looks the territories over once");
            assertFalse(watcher.check(false, hour).sweptFully(), "and then asks only for the count");
        }
    }
}
