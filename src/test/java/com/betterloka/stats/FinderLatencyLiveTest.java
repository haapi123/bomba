package com.betterloka.stats;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.betterloka.api.MarketApi;
import com.betterloka.data.TownCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Player Finder, searched over and over against the live services while everything the game
 * runs in the background runs too: the battle archive, the market warm-up, the ranked ladders and a
 * stream of nameplate K/D lookups.
 *
 * <p>Measurements rather than assertions, and against the real hosts, so opt in:
 * {@code ./gradlew test --tests '*FinderLatencyLiveTest' -Pbetterloka.live=true}.
 */
@EnabledIfSystemProperty(named = "betterloka.live", matches = "true")
class FinderLatencyLiveTest {
    private static final List<String> NAMES = List.of(
            "haapi", "slovakianbeast43", "jagardumm", "haapi", "Tyrael_", "maruguchi",
            "haapi", "xPabloFights_YT", "slovakianbeast43", "haapi", "Rezorie", "haapi",
            "jagardumm", "haapi", "maruguchi", "haapi");

    @Test
    void searchesStayFastUnderBackgroundLoad(@TempDir Path dir) throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            LokaApi loka = new LokaApi(transport);
            EldritchApi eldritch = new EldritchApi(transport);
            MarketApi market = new MarketApi(transport);
            TownCache towns = new TownCache(loka, dir.resolve("towns.json"));
            PlayerStatsService stats = new PlayerStatsService(loka, eldritch, towns, dir.resolve("fights.json"));
            ArenaService arena = new ArenaService(new ArenaApi(transport), dir.resolve("arena.json"));
            BattleIndex battles = new BattleIndex(loka, dir.resolve("battles.json"));
            NameplateKdService nameplates = new NameplateKdService(eldritch);
            EloService elo = new EloService(transport, dir.resolve("elo.json"), transport.backgroundExecutor());
            elo.start();
            stats.useElo(elo);
            for (int i = 0; i < 200 && !elo.ready(); i++) {
                Thread.sleep(100);
            }
            System.out.println("FINDER elo ready=" + elo.ready() + " players=" + elo.size());

            // What the game has going behind the Player Finder.
            arena.prewarm();
            market.sweep(true);
            battles.startIndexing(transport.backgroundExecutor());

            // A Refresh pressed with all of that going on, timed to the last page.
            long refreshStart = System.nanoTime();
            var refresh = market.sweep(true);
            while (refresh.running() && System.nanoTime() - refreshStart < 120_000_000_000L) {
                Thread.sleep(50);
            }
            System.out.println("FINDER market refresh: " + refresh.listings().size() + " offers, "
                    + refresh.pagesDone() + "/" + refresh.pagesTotal() + " pages in "
                    + (System.nanoTime() - refreshStart) / 1_000_000 + " ms");

            List<String> report = new ArrayList<>();
            int round = 0;
            for (String name : NAMES) {
                round++;
                // Players walking past: their nameplates ask for K/D in the background.
                for (String passer : List.of("haapi", "jagardumm", "slovakianbeast43", "maruguchi",
                        "a" + round, "b" + round, "c" + round)) {
                    nameplates.killDeathOf(passer);
                }
                if (round % 4 == 0) {
                    market.sweep(true);
                }

                long start = System.nanoTime();
                long[] headlineAt = {0};
                CompletableFuture<?> current = arena.current(name, null);
                battles.startIndexing(transport.backgroundExecutor());
                CompletableFuture<PlayerProfile> full = stats.lookup(name, headline -> {
                    headlineAt[0] = System.nanoTime();
                    arena.best(headline.name(), headline.uuid());
                });
                String outcome;
                try {
                    full.get(60, TimeUnit.SECONDS);
                    outcome = "ok";
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null && e.getCause().getCause() != null
                            ? e.getCause().getCause() : e.getCause() != null ? e.getCause() : e;
                    outcome = "failed: " + cause.getMessage();
                }
                long fullAt = System.nanoTime();
                try {
                    current.get(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Counted below as however long it took.
                }
                long currentAt = System.nanoTime();
                PlayerProfile shown = full.isDone() && !full.isCompletedExceptionally() ? full.getNow(null) : null;
                if (shown != null) {
                    EloService.Entry rated = elo.byUuid(shown.uuid());
                    outcome += " as " + shown.name() + " rank " + shown.rank()
                            + (rated == null ? " unrated" : " elo " + rated.shown());
                }
                String line = String.format("%2d %-18s headline %5d ms, fights %5d ms, ladder %5d ms, %s"
                                + " (requests %d, 429s %d, battles %d%%)",
                        round, name,
                        headlineAt[0] == 0 ? -1 : (headlineAt[0] - start) / 1_000_000,
                        (fullAt - start) / 1_000_000, (currentAt - start) / 1_000_000, outcome,
                        transport.requestCount(), transport.throttleCount(), battles.progress().percent());
                System.out.println("FINDER " + line);
                report.add(line);
                Thread.sleep(1500);
            }
            System.out.println("FINDER 429s by host: " + transport.throttledByHost());
            transport.slowRequests().forEach(line -> System.out.println("FINDER slow " + line));
            System.out.println("FINDER summary:");
            report.forEach(line -> System.out.println("FINDER " + line));
        }
    }
}
