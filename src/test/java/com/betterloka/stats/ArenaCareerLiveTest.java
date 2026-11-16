package com.betterloka.stats;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.model.ArenaEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The duel summary's ranked lookup against Loka's real ladders. Opt-in: -Pbetterloka.live=true */
@EnabledIfSystemProperty(named = "betterloka.live", matches = "true")
class ArenaCareerLiveTest {
    @Test
    void theLadderLeaderHasABestEverAndALastSeason() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            ArenaApi api = new ArenaApi(transport);
            List<ArenaEntry> top = api.fetchCurrent(ArenaApi.Ladder.POTION);
            assertFalse(top.isEmpty());
            ArenaEntry leader = top.get(0);

            long started = System.nanoTime();
            List<ArenaService.Career> careers = new ArenaService(api).careers(leader.name(), leader.uuid())
                    .get(120, TimeUnit.SECONDS);
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            ArenaService.Career potion = careers.stream()
                    .filter(career -> career.ladder() == ArenaApi.Ladder.POTION).findFirst().orElseThrow();
            System.out.println("LIVE " + leader.name() + " now " + potion.current().rank()
                    + " · best " + potion.highest().entry().rank() + " (S" + potion.highest().season() + ")"
                    + " · last " + (potion.lastSeason() == null ? "-" : potion.lastSeason().entry().rank()
                    + " (S" + potion.lastSeason().season() + ")") + " · season " + potion.season()
                    + " · career " + potion.career() + " in " + took + " ms");
            assertTrue(potion.lastNumber() < potion.season(), "the last season is a finished one");
            assertNotNull(potion.current());
            assertNotNull(potion.highest());
            assertTrue(potion.highest().entry().rank().compareTo(potion.current().rank()) >= 0,
                    "the highest ever is at least where they are now");
        }
    }
}
