package com.betterloka;

import com.betterloka.api.LokaApi;
import com.betterloka.data.BattleIndex;
import com.betterloka.data.BattleSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;

/**
 * Writes a fully synced battle cache to a chosen path, so a dev client can start with the history
 * already in place instead of spending a minute and a half downloading it.
 *
 * <p>{@code ./gradlew test -Pbetterloka.seed=run/config/betterloka/battles.bin --tests "*CacheSeedTest"}
 */
@EnabledIfSystemProperty(named = "betterloka.seed", matches = ".+")
class CacheSeedTest {
    @Test
    void seedCache() throws Exception {
        Path target = Path.of(System.getProperty("betterloka.seed"));
        try (LokaApi api = new LokaApi()) {
            BattleIndex index = new BattleSyncService(api, target).ensureSynced().get();
            System.out.printf("seeded %s with %d battles%n", target.toAbsolutePath(), index.size());
        }
    }
}
