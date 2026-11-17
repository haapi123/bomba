package com.betterloka.bot;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The stat channels' numbers from the real Loka. Opt-in: BETTERLOKA_LIVE=true. */
@EnabledIfEnvironmentVariable(named = "BETTERLOKA_LIVE", matches = "true")
class LiveStatsLiveTest {
    @Test
    void readsLokaNow() {
        try (HttpTransport transport = new HttpTransport()) {
            LiveStats live = new LiveStats("play.lokamc.com", true, ZoneId.of("Europe/Warsaw"), false,
                    new LokaApi(transport), new ArenaApi(transport));
            long started = System.nanoTime();
            LiveStats.Snapshot first = live.refresh(System.currentTimeMillis());
            long firstMs = (System.nanoTime() - started) / 1_000_000;
            started = System.nanoTime();
            LiveStats.Snapshot second = live.refresh(System.currentTimeMillis());
            long secondMs = (System.nanoTime() - started) / 1_000_000;
            for (StatChannels.Tile tile : StatChannels.Tile.values()) {
                System.out.println("LIVE " + StatChannels.name(tile, second));
            }
            System.out.println("LIVE first refresh " + firstMs + " ms, second " + secondMs + " ms");
            assertNotNull(first.players());
            assertNotNull(first.kills());
            assertNotNull(first.potion());
        }
    }
}
