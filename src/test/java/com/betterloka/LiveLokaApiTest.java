package com.betterloka;

import com.betterloka.api.LokaApi;
import com.betterloka.api.LokaApiException;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.data.BattleIndex;
import com.betterloka.data.BattleSyncService;
import com.betterloka.data.TownCache;
import com.betterloka.stats.FightSummary;
import com.betterloka.stats.PlayerProfile;
import com.betterloka.stats.PlayerStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks against the real api.lokamc.com. Opt in with
 * {@code ./gradlew test -Pbetterloka.live=true} — the first sync walks the whole battle history and
 * takes about a minute, so this is not part of a normal build.
 */
@EnabledIfSystemProperty(named = "betterloka.live", matches = "true")
class LiveLokaApiTest {
    /** Loka's owner; the oldest player document on the server and a stable fixture. */
    private static final String OWNER = "Cryptite";

    @Test
    void findsPlayersRegardlessOfNameCasing() throws Exception {
        try (LokaApi api = new LokaApi()) {
            LokaPlayer exact = api.findPlayerByName(OWNER);
            assertEquals(OWNER, exact.name());
            assertNotNull(exact.uuid());

            // The Loka endpoint is case sensitive and 404s here; the Mojang fallback is what saves it.
            LokaPlayer lowercase = api.findPlayerByName(OWNER.toLowerCase(Locale.ROOT));
            assertEquals(exact.uuid(), lowercase.uuid());

            LokaApiException missing = assertThrows(LokaApiException.class,
                    () -> api.findPlayerByName("ThisPlayerDoesNotExist12345"));
            assertTrue(missing.notFound());
        }
    }

    @Test
    void syncsHistoryThenBuildsAProfile(@TempDir Path directory) throws Exception {
        Path cache = directory.resolve("battles.bin");
        try (LokaApi api = new LokaApi()) {
            BattleSyncService sync = new BattleSyncService(api, cache);
            BattleIndex index = sync.ensureSynced().get();

            assertTrue(index.size() > 1000, "Loka has thousands of battles on record, got " + index.size());
            assertTrue(index.playerCount() > 100, "battles should resolve to many distinct players");
            assertEquals(BattleSyncService.Phase.READY, sync.progress().phase());
            assertTrue(Files.size(cache) > 0, "the sync must leave a reusable cache behind");
            System.out.printf("indexed %d battles / %d players, cache %.1f MB, %d requests, %d rate-limit hits%n",
                    index.size(), index.playerCount(), Files.size(cache) / 1e6,
                    api.requestCount(), api.throttleCount());
            // Loka's limiter is bursty rather than a clean requests-per-second budget: halving the
            // pace barely moves this number, and the retry path absorbs what does get through (the
            // completeness assertions above are the real guarantee). The bar here is loose enough
            // not to flake, but tight enough to catch the pacing being removed — unthrottled, this
            // sweep loses well over half its requests.
            assertTrue(api.throttleCount() < api.requestCount() * 0.10,
                    "the sync should stay inside the API's rate limit, got " + api.throttleCount()
                            + " rate-limit hits over " + api.requestCount() + " requests");

            TownCache towns = new TownCache(api);
            PlayerStatsService stats = new PlayerStatsService(api, sync, towns);

            // Pick the busiest player in the history so the assertions below have something to bite on.
            String busiest = busiestPlayerName(api, index);
            java.util.concurrent.atomic.AtomicReference<PlayerProfile> partial = new java.util.concurrent.atomic.AtomicReference<>();
            PlayerProfile profile = stats.lookup(busiest, partial::set).get();

            PlayerProfile identityStage = partial.get();
            assertNotNull(identityStage, "the identity stage must arrive before the combat stage");
            assertEquals(busiest, identityStage.player().name());
            assertEquals(PlayerProfile.StatsState.PENDING, identityStage.statsState());

            assertEquals(PlayerProfile.StatsState.READY, profile.statsState());
            assertEquals(busiest, profile.player().name());
            assertNotNull(profile.firstSeen());
            assertTrue(profile.kills() > 0, "the busiest fighter on Loka should have kills");
            assertTrue(profile.battlesFought() > 0);
            assertTrue(profile.killDeathRatio() > 0);
            assertEquals(PlayerStatsService.RECENT_FIGHT_COUNT, profile.recentFights().size());

            for (FightSummary fight : profile.recentFights()) {
                assertNotNull(fight.territory());
                assertTrue(fight.ownSideCount() > 0, "the player fought, so their own side cannot be empty");
                assertTrue(fight.kills() >= 0 && fight.deaths() >= 0);
            }

            System.out.printf("%s: %dK / %dD (%.2f K/D) across %d fights, town=%s, fighting for %s, first seen %s%n",
                    profile.player().name(), profile.kills(), profile.deaths(), profile.killDeathRatio(),
                    profile.battlesFought(),
                    profile.town() != null ? profile.town().name() : "none",
                    profile.fightingFor(), profile.firstSeen());
            for (FightSummary fight : profile.recentFights()) {
                System.out.printf("  %-14s %2d v %-2d  %2dK / %dD  for %s%n", fight.territory(),
                        fight.ownSideCount(), fight.enemySideCount(), fight.kills(), fight.deaths(),
                        fight.foughtForTown());
            }
        }

        // A second run must reuse the cache instead of walking the whole history again.
        try (LokaApi api = new LokaApi()) {
            long start = System.currentTimeMillis();
            BattleSyncService resync = new BattleSyncService(api, cache);
            BattleIndex reloaded = resync.ensureSynced().get();
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(reloaded.size() > 1000);
            assertTrue(elapsed < 30_000,
                    "an incremental sync should take seconds, not a full resync; took " + elapsed + "ms");
            System.out.printf("incremental resync: %d battles in %d ms%n", reloaded.size(), elapsed);
        }
    }

    /**
     * @return the name of the busiest fighter that still has a player record. Not every UUID in the
     * battle history resolves — old records reference accounts the {@code /players} collection no
     * longer holds — so this walks down the leaderboard until one of them does.
     */
    private static String busiestPlayerName(LokaApi api, BattleIndex index) throws LokaApiException {
        java.util.Map<java.util.UUID, Integer> counts = new java.util.HashMap<>();
        index.battles().forEach(battle -> battle.participants()
                .forEach(participant -> counts.merge(participant.uuid(), 1, Integer::sum)));

        List<java.util.UUID> ranked = counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<java.util.UUID, Integer>comparingByValue().reversed())
                .limit(10)
                .map(java.util.Map.Entry::getKey)
                .toList();

        for (java.util.UUID candidate : ranked) {
            try {
                String name = api.findPlayerByUuid(candidate).name();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (LokaApiException e) {
                if (!e.notFound()) {
                    throw e;
                }
            }
        }
        throw new AssertionError("none of the ten busiest fighters resolved to a player record");
    }
}
