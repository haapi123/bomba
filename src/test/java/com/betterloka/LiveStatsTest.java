package com.betterloka;

import com.betterloka.api.ApiException;
import com.betterloka.api.EldritchApi;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.LokaApi;
import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.FightDetail;
import com.betterloka.data.TownCache;
import com.betterloka.stats.FightSummary;
import com.betterloka.stats.PlayerProfile;
import com.betterloka.stats.PlayerStatsService;
import com.betterloka.translate.TranslationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks against the real eldritchbot.com, api.lokamc.com and the translation endpoint.
 * Opt in with {@code ./gradlew test -Pbetterloka.live=true}.
 *
 * <p>The unit tests parse fixtures written by hand to match the real markup; only this suite catches
 * the sites themselves changing.
 */
@EnabledIfSystemProperty(named = "betterloka.live", matches = "true")
class LiveStatsTest {
    /** A long-standing Loka fighter with a deep record, so the assertions have something to bite on. */
    private static final String PLAYER = "Rezorie";

    @Test
    void readsACareerFromEldritchBot() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            EldritchApi api = new EldritchApi(transport);
            EldritchStats stats = api.fetchStats(PLAYER);

            assertEquals(PLAYER, stats.name());
            assertTrue(stats.kills() > 0, "a veteran should have kills");
            assertTrue(stats.deaths() > 0);
            assertTrue(stats.assists() > 0, "assists come from the Combat table");
            assertTrue(stats.wins() > 0 && stats.losses() > 0, "Conquest table");
            assertTrue(stats.golems() > 0 && stats.lamps() > 0);
            assertTrue(stats.potions() > 0 && stats.pearls() > 0, "Consumables table");
            assertNotNull(stats.town());
            assertNotNull(stats.lastFight());
            assertNotNull(stats.nemesisName());
            assertTrue(stats.nemesisDeaths() > 0);
            assertTrue(stats.recentFights().size() >= 5, "the page lists several recent fights");

            System.out.printf("%s (%s): %dK/%dD/%dA  K/D %.2f  W-L %d-%d (%.0f%%)  golems %d lamps %d%n",
                    stats.name(), stats.town(), stats.kills(), stats.deaths(), stats.assists(),
                    stats.killDeathRatio(), stats.wins(), stats.losses(), stats.winRate() * 100,
                    stats.golems(), stats.lamps());
            System.out.printf("  potions %d, pearls %d, food %d, ingots %d, nemesis %s (%d)%n",
                    stats.potions(), stats.pearls(), stats.food(), stats.ancientIngots(),
                    stats.nemesisName(), stats.nemesisDeaths());

            // The fight pages are where the per-fight breakdown comes from.
            EldritchStats.RecentFight newest = stats.recentFights().get(0);
            FightDetail detail = api.fetchFight(newest.id(), PLAYER);
            assertNotNull(detail, PLAYER + " should appear in their own most recent fight");
            assertTrue(detail.ownSideCount() > 0);
            assertTrue(detail.enemySideCount() >= 0);
            System.out.printf("  newest fight %s: %s %d v %d, %dK/%dD/%dA for %s%n",
                    newest.id(), detail.location(), detail.ownSideCount(), detail.enemySideCount(),
                    detail.kills(), detail.deaths(), detail.assists(), detail.playerTown());
        }
    }

    @Test
    void quickStatsPowerTheNameplateOverlay() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            EldritchApi api = new EldritchApi(transport);
            EldritchStats quick = api.fetchQuickStats(PLAYER);

            assertNotNull(quick, "the JSON endpoint should know this player");
            assertEquals(PLAYER, quick.name());
            assertTrue(quick.kills() > 0 && quick.deaths() > 0);
            assertTrue(quick.killDeathRatio() > 0);
            System.out.printf("nameplate K/D for %s: %.2f%n", quick.name(), quick.killDeathRatio());
        }
    }

    @Test
    void buildsAWholeProfileInAHandfulOfRequests() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            LokaApi loka = new LokaApi(transport);
            EldritchApi eldritch = new EldritchApi(transport);
            PlayerStatsService stats = new PlayerStatsService(loka, eldritch, new TownCache(loka));

            AtomicReference<PlayerProfile> headline = new AtomicReference<>();
            AtomicLong headlineAt = new AtomicLong();
            long start = System.currentTimeMillis();

            PlayerProfile profile = stats.lookup(PLAYER, first -> {
                headline.set(first);
                headlineAt.set(System.currentTimeMillis() - start);
            }).get();
            long total = System.currentTimeMillis() - start;

            assertNotNull(headline.get(), "the headline stage must arrive before the fight detail");
            assertEquals(PLAYER, headline.get().name());
            assertTrue(headline.get().stats().kills() > 0,
                    "career totals must already be present in the headline stage");

            assertEquals(PLAYER, profile.name());
            assertEquals(PlayerProfile.FightsState.READY, profile.fightsState());
            assertEquals(PlayerStatsService.RECENT_FIGHT_COUNT, profile.recentFights().size());
            assertNotNull(profile.displayTown());
            assertNotNull(profile.fightingFor());

            for (FightSummary fight : profile.recentFights()) {
                assertTrue(fight.hasDetail(), "every listed fight should resolve its breakdown");
                assertTrue(fight.ownSideCount() > 0);
            }

            // The whole point of moving off Loka's paged battle history: this used to be ~410 requests.
            assertTrue(transport.requestCount() <= 15,
                    "a profile should cost a handful of requests, took " + transport.requestCount());
            assertTrue(total < 20_000, "a profile should land in seconds, took " + total + "ms");

            System.out.printf("profile in %d ms (headline at %d ms) over %d requests, %d rate-limit hits%n",
                    total, headlineAt.get(), transport.requestCount(), transport.throttleCount());
            for (FightSummary fight : profile.recentFights()) {
                System.out.printf("  %-14s %2d v %-2d  %2dK/%dD/%dA  %s  for %-18s vs %s%n",
                        fight.location(), fight.ownSideCount(), fight.enemySideCount(),
                        fight.kills(), fight.deaths(), fight.assists(),
                        fight.victory() ? "win " : "loss", fight.ownTown(), fight.enemyTown());
            }
        }
    }

    @Test
    void reportsUnknownPlayersAsNotFound() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            EldritchApi api = new EldritchApi(transport);
            ApiException missing = assertThrows(ApiException.class,
                    () -> api.fetchStats("ThisPlayerDoesNotExist12345"));
            assertTrue(missing.notFound());
        }
    }

    @Test
    void findsPlayersRegardlessOfNameCasing() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            EldritchApi api = new EldritchApi(transport);
            // EldritchBot's page lookup is case insensitive, unlike its JSON endpoint and Loka's API.
            assertEquals(PLAYER, api.fetchStats(PLAYER.toLowerCase(Locale.ROOT)).name());
        }
    }

    @Test
    void readsTheMarket() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            com.betterloka.api.MarketApi market = new com.betterloka.api.MarketApi(transport);

            java.util.List<String> types = market.fetchTypes();
            assertTrue(types.size() > 100, "Loka's market covers hundreds of item types, got " + types.size());
            assertEquals("DIAMOND_SWORD", market.matchTypes("diamond sword", 1).get(0),
                    "a spaced query should still match the underscored material name");

            java.util.List<com.betterloka.api.model.MarketListing> swords = market.fetchListings("DIAMOND_SWORD");
            assertTrue(swords.size() > 5, "there are always swords on sale, got " + swords.size());
            for (int i = 1; i < swords.size(); i++) {
                assertTrue(swords.get(i - 1).pricePerUnit() <= swords.get(i).pricePerUnit(),
                        "listings should come back cheapest per unit first");
            }

            var special = swords.stream().filter(com.betterloka.api.model.MarketListing::isSpecial).toList();
            assertTrue(!special.isEmpty(), "named swords are what the Special tab lists");
            assertTrue(special.size() < swords.size(),
                    "every Loka item carries lore, so lore alone must not mark an item special");
            var named = special.get(0);
            assertNotNull(named.customName());
            assertTrue(named.price() > 0);
            assertTrue(named.customName().length() < 60,
                    "the name must stop at its own component, not run into the lore: " + named.customName());

            String seller = market.sellerName(swords.get(0).ownerId());
            assertNotNull(seller, "listings carry only an identity id, which must resolve to a name");

            System.out.printf("market: %d item types, %d diamond swords listed, %d of them named/lore%n",
                    types.size(), swords.size(), special.size());
            System.out.printf("  cheapest: %s from %s at %.0f (%.0f each, %d left)%n",
                    swords.get(0).displayName(), seller, swords.get(0).price(),
                    swords.get(0).pricePerUnit(), swords.get(0).quantity());
            for (var listing : special.stream().limit(5).toList()) {
                System.out.printf("  named:    %-34s [%s] %.0f%n", listing.customName(),
                        String.join(", ", listing.enchantments()), listing.price());
            }
            if (!named.lore().isEmpty()) {
                System.out.println("  lore:     " + String.join(" | ", named.lore().subList(0,
                        Math.min(3, named.lore().size()))));
            }
        }
    }

    @Test
    void translatesBothWays() throws Exception {
        try (HttpTransport transport = new HttpTransport()) {
            TranslationService service = new TranslationService(transport);

            String toEnglish = service.translate("idziemy na fighta o 20, potrzebujemy jeszcze 3", "pl", "en").get();
            assertTrue(toEnglish.toLowerCase(Locale.ROOT).contains("fight"),
                    "expected an English sentence, got: " + toEnglish);

            String toPolish = service.translate("anyone up for a fight at 8? we need 3 more", "auto", "pl").get();
            assertTrue(toPolish.toLowerCase(Locale.ROOT).contains("walk"),
                    "expected a Polish sentence, got: " + toPolish);

            System.out.println("pl->en: " + toEnglish);
            System.out.println("en->pl: " + toPolish);
        }
    }
}
