package com.betterloka.assistantserver;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPartsTest {
    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now;

        TestClock(String instant) {
            now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void anOlderSettingsFileGetsTheDiscordSectionAndABrokenOneIsLeftAlone(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("assistant.json");
        Files.writeString(old, "{\"admins\": [\"Steve\"], \"model\": \"claude-sonnet-5-5\"}");
        ServerConfig config = ServerConfig.load(old);
        assertEquals("", config.discordQuestions.channelId);
        String rewritten = Files.readString(old);
        assertTrue(rewritten.contains("\"discordQuestions\""), rewritten);
        assertTrue(rewritten.contains("\"Steve\"") && rewritten.contains("claude-sonnet-5-5"), "the owner's settings stay");

        Path broken = dir.resolve("broken.json");
        String typo = "{\"admins\": [\"Steve\"],, }";
        Files.writeString(broken, typo);
        ServerConfig.load(broken);
        assertEquals(typo, Files.readString(broken), "a file with a typo is the owner's to fix, not ours to replace");
    }

    @Test
    void pricesCacheReadsWritesAndOutput() {
        // 10k cached read + 1k fresh input + 500 output on the default model.
        double warm = Pricing.cost("claude-opus-5-5", new Pricing.Usage(1000, 500, 10_000, 0, 0), null);
        assertEquals((1000 * 4.0 + 10_000 * 0.20 + 500 * 20.0) / 1e6, warm, 1e-12);
        // Writing to the cache costs 1.25x input for five minutes, 2x for an hour.
        double cold = Pricing.cost("claude-opus-5-5", new Pricing.Usage(0, 0, 0, 1000, 1000), null);
        assertEquals((1000 * 4.0 * 1.25 + 1000 * 4.0 * 2) / 1e6, cold, 1e-12);
        // A long prompt on the small model switches to its long prices.
        double longPrompt = Pricing.cost("claude-haiku-5-5", new Pricing.Usage(150_000, 1000, 0, 0, 0), null);
        assertEquals((150_000 * 0.50 + 1000 * 2.50) / 1e6, longPrompt, 1e-12);
        // An unknown model is priced high, so the budget stays safe.
        assertTrue(Pricing.price("some-new-model", null).input() >= 10);
        // The owner's own table wins.
        assertEquals(1.0, Pricing.price("claude-opus-5-5", Map.of("claude-opus-5-5", new Pricing.Price(1, 2, 0.1))).input());
    }

    @Test
    void ledgerKeepsPlayersAndTheBudgetInBounds(@TempDir Path dir) {
        TestClock clock = new TestClock("2026-11-01T10:00:00Z");
        ServerConfig config = new ServerConfig();
        config.monthlyBudgetUsd = 30;
        config.dailyBurst = 2;
        config.questionsPerDay = 2;
        Ledger ledger = new Ledger(dir.resolve("ledger.json"), config, clock);

        // First of a 30-day month: an even share is $1, twice that may go today.
        assertEquals(2.0, ledger.allowedToday(), 1e-9);
        assertEquals(Ledger.Verdict.OK, ledger.canAsk("u1", false));
        ledger.recordQuestion("u1", 0.10);
        ledger.recordQuestion("u1", 0.10);
        assertEquals(Ledger.Verdict.PLAYER_LIMIT, ledger.canAsk("u1", false));
        assertEquals(Ledger.Verdict.OK, ledger.canAsk("u1", true), "admins have no daily question limit");
        assertEquals(Ledger.Verdict.OK, ledger.canAsk("u2", false));

        ledger.spend(1.85);
        assertEquals(Ledger.Verdict.BUDGET_DAY, ledger.canAsk("u2", false));

        // Next day: counts reset, and the share comes from what was left of the month.
        clock.now = Instant.parse("2026-11-02T10:00:00Z");
        assertEquals(Ledger.Verdict.OK, ledger.canAsk("u1", false));
        assertEquals((30 - 2.05) / 29 * 2, ledger.allowedToday(), 1e-9);

        // It survives a restart.
        Ledger reloaded = new Ledger(dir.resolve("ledger.json"), config, clock);
        assertEquals(2.05, reloaded.monthUsd(), 1e-9);

        // A hard daily cap wins over the share; the month's budget is a ceiling.
        config.dailyBudgetUsd = 0.5;
        assertEquals(0.5, reloaded.allowedToday(), 1e-9);
        reloaded.spend(28);
        assertEquals(Ledger.Verdict.BUDGET_MONTH, reloaded.canAsk("u3", false));

        // A new month starts from nothing.
        clock.now = Instant.parse("2026-12-01T00:00:01Z");
        assertEquals(0, reloaded.monthUsd(), 1e-9);
        assertEquals(Ledger.Verdict.OK, reloaded.canAsk("u3", false));
    }

    @Test
    void ticketsAreSignedAndExpire(@TempDir Path dir) {
        TestClock clock = new TestClock("2026-11-10T00:00:00Z");
        byte[] secret = Sessions.loadSecret(dir.resolve("secret.key"));
        assertTrue(Files.exists(dir.resolve("secret.key")));
        Sessions sessions = new Sessions(secret, clock);

        String serverId = sessions.challenge("Steve");
        assertFalse(sessions.redeem(serverId, "Alex"), "a challenge is for one name");
        String again = sessions.challenge("Steve");
        assertTrue(sessions.redeem(again, "steve"));
        assertFalse(sessions.redeem(again, "Steve"), "a challenge is spent once");

        String ticket = sessions.ticket("1234", "Steve");
        assertEquals("Steve", sessions.verify(ticket).orElseThrow().name());
        String forged = ticket.substring(0, ticket.indexOf('.')) + ".AAAA";
        assertTrue(sessions.verify(forged).isEmpty());
        assertTrue(new Sessions(new byte[32], clock).verify(ticket).isEmpty(), "another server's key does not fit");

        clock.now = clock.now.plus(Sessions.TICKET_LIFE).plusSeconds(5);
        assertTrue(sessions.verify(ticket).isEmpty(), "tickets run out");
        // The key is kept.
        assertEquals(new String(secret, StandardCharsets.ISO_8859_1),
                new String(Sessions.loadSecret(dir.resolve("secret.key")), StandardCharsets.ISO_8859_1));
    }

    @Test
    void readsRobotsTheWayCrawlersShould() {
        String loka = """
                User-agent: GPTBot
                User-agent: ClaudeBot
                User-agent: *
                Disallow: /config
                Disallow: /api/
                Allow: /api/ui-extensions/
                Disallow:/*?format=json
                Disallow: /account$
                """;
        Robots robots = Robots.parse(loka, "BetterLoka-Assistant-KB/1.0 (knowledge base builder)");
        assertTrue(robots.allows("/game/conquest"));
        assertFalse(robots.allows("/api/thing"));
        assertTrue(robots.allows("/api/ui-extensions/x"), "the longer Allow wins");
        assertFalse(robots.allows("/news?format=json"));
        assertFalse(robots.allows("/account"));
        assertTrue(robots.allows("/account-help"), "$ anchors the end");

        String forum = """
                User-agent: AhrefsBot
                Disallow: /

                User-agent: *
                Disallow: /members/
                Disallow: /search/
                Allow: /
                """;
        Robots forumRules = Robots.parse(forum, "BetterLoka-Assistant-KB/1.0");
        assertTrue(forumRules.allows("/threads/patch-notes.123/"));
        assertFalse(forumRules.allows("/members/someone.5/"));

        Robots ours = Robots.parse("User-agent: betterloka-assistant-kb\nDisallow: /\n\nUser-agent: *\nAllow: /\n",
                "BetterLoka-Assistant-KB/1.0");
        assertFalse(ours.allows("/anything"), "a group naming us beats the * group");
    }

    @Test
    void turnsPagesIntoStructuredText() {
        String html = """
                <html><body><nav>Menu Home Store</nav><main>
                <h2>Conquest</h2><p>Towns   fight over <b>territories</b>.</p><img alt="2020-04-04_22.16.38.png"><img alt="A castle on a hill">
                <ul><li>Battles last 20 minutes</li><li>Win to capture</li></ul>
                <script>var x = 1;</script>
                <table><tr><td>Level</td><td>Cost</td></tr></table>
                </main><footer>Copyright</footer></body></html>""";
        String text = HtmlText.of(Jsoup.parse(html).selectFirst("main"));
        assertTrue(text.contains("## Conquest"), text);
        assertTrue(text.contains("Towns fight over territories."), text);
        assertTrue(text.contains("- Battles last 20 minutes"), text);
        assertTrue(text.contains("| Level | Cost"), text);
        assertFalse(text.contains("var x"), text);
        assertFalse(text.contains("Menu"), text);
        assertFalse(text.contains(".png"), "image file names are noise");
        assertTrue(text.contains("[A castle on a hill]"), text);
    }

    @Test
    void crawlerHelpers() {
        assertEquals("2024-05-02", Crawler.date("2024-05-02T18:21:44+0000"));
        assertEquals("2023-11-12", Crawler.date("2023-11-12T09:00:00Z"));
        assertEquals("game-conquest-2", Crawler.slug("/game/conquest/2"));
        assertEquals("", Crawler.date(""));
    }

    @Test
    void knowledgeBaseReadsTheFolderAndNoticesChanges(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("generated"));
        Files.writeString(dir.resolve("generated/towns.md"), "# Towns\nTowns have levels.");
        Files.writeString(dir.resolve(KnowledgeBase.COMMUNITY), "# Community answers\n## Q\nA");
        Files.writeString(dir.resolve("aaa.md"), "# First by name\nText.");
        Files.writeString(dir.resolve("notes.json"), "{}");
        KnowledgeBase kb = new KnowledgeBase(dir);
        KnowledgeBase.Snapshot first = kb.current();
        assertEquals(3, first.files());
        assertTrue(first.text().indexOf("aaa.md") < first.text().indexOf("generated/towns.md"));
        assertTrue(first.text().indexOf("generated/towns.md") < first.text().indexOf("community.md"),
                "the players' approved answers come last");
        assertFalse(first.text().contains("notes.json"));

        Files.writeString(dir.resolve("generated/towns.md"), "# Towns\nTowns have levels and upkeep.");
        kb.touch();
        KnowledgeBase.Snapshot second = kb.current();
        assertTrue(second.text().contains("upkeep"));
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void communityCollectsQuestionsAndOnlyApprovedAnswersReachTheKnowledgeBase(@TempDir Path dir) throws Exception {
        TestClock clock = new TestClock("2026-11-10T12:00:00Z");
        Path communityFile = dir.resolve("kb").resolve(KnowledgeBase.COMMUNITY);
        Community community = new Community(dir.resolve("community.json"), communityFile, clock);

        Community.OpenQuestion first = community.noteOpen("Gdzie jest Warden?", "unknown", null);
        Community.OpenQuestion again = community.noteOpen("gdzie JEST warden", "unknown", null);
        assertEquals(first.id, again.id, "the same question however it is typed");
        assertEquals(2, again.count);
        community.noteOpen("How do I join a town?", "disputed", "Wrong answer");
        assertEquals("Gdzie jest Warden?", community.open(10).get(0).question, "the most asked first");

        Community.Proposal troll = community.propose(first.id, first.question, "In your basement", "u9", "Troll");
        Community.Proposal good = community.propose(first.id, first.question, "Balak, at 3870 100 2482.", "u2", "Helper");
        assertEquals(2, community.pending().size());
        assertFalse(Files.exists(communityFile), "nothing reaches the knowledge base before an admin approves");

        assertTrue(community.review(troll.id, false, null, null, "Admin").isPresent());
        assertFalse(Files.exists(communityFile));
        assertTrue(community.review(good.id, true, null, "Balak: 3870 100 2482.", "Admin").isPresent());
        String written = Files.readString(communityFile);
        assertTrue(written.contains("## Gdzie jest Warden?"), written);
        assertTrue(written.contains("Balak: 3870 100 2482."), "the admin's edit is what is kept");
        assertTrue(written.contains("written by Helper"));
        assertTrue(community.openQuestion(first.id).isEmpty(), "answered questions close");
        assertTrue(community.review(good.id, true, null, null, "Admin").isEmpty(), "reviewed once");

        // State survives a restart.
        Community reloaded = new Community(dir.resolve("community.json"), communityFile, clock);
        assertEquals(1, reloaded.open(10).size());
        assertTrue(reloaded.dismiss(reloaded.open(10).get(0).id));
        assertTrue(reloaded.open(10).isEmpty());
    }

    @Test
    void distillerPacksSourcesAndSortsNotesByTopic() {
        List<Crawler.Source> sources = List.of(
                new Crawler.Source("Old wiki", "w1", "wiki", "2019-01-01", "x".repeat(2000)),
                new Crawler.Source("Conquest", "s1", "site", "", "y".repeat(2000)),
                new Crawler.Source("New post", "f2", "forum", "2026-05-01", "z".repeat(2000)),
                new Crawler.Source("Old post", "f1", "forum", "2020-05-01", "w".repeat(200_000)));
        List<Crawler.Source> ordered = Distiller.ordered(sources);
        assertEquals(List.of("Conquest", "New post", "Old post", "Old wiki"), ordered.stream().map(Crawler.Source::title).toList());
        List<String> chunks = Distiller.chunks(ordered, 10_000);
        assertTrue(chunks.size() >= 3);
        for (String chunk : chunks) {
            assertTrue(chunk.length() / 4 <= 10_000 + 200, "chunks stay near the limit: " + chunk.length());
        }
        assertTrue(chunks.get(0).contains("kind=\"site\""));

        Map<String, StringBuilder> byTopic = new HashMap<>();
        Distiller.split("## towns\n- Towns level up [site]\n\n## nonsense\n- Stray fact [wiki]\n## TOWNS\n- Upkeep is daily [forum 2025-01-01]\n", byTopic);
        assertEquals("- Towns level up [site]\n- Upkeep is daily [forum 2025-01-01]\n", byTopic.get("towns").toString());
        assertEquals("- Stray fact [wiki]\n", byTopic.get("other").toString(), "unknown headings land in other");
        assertTrue(Distiller.estimate(chunks, "claude-opus-5-5", new ServerConfig()) > 0);
    }

    @Test
    void demoAnswersFromTheMatchingSection() {
        String kb = "# Places\n### Balak\n- Balak Docks: 2300 80 3012\n- Warden boss: 3870 100 2482\n"
                + "## Ile kosztuje town?\nDwa tysiące diamentów.\n_Approved 2026-11-10, written by Helper._\n";
        DemoAnswerer demo = new DemoAnswerer();
        Answerer.Reply warden = demo.answer(new Answerer.Question(kb, List.of(), "Gdzie jest boss Warden?", "pl_pl", "u"));
        assertEquals("(demo) - Warden boss: 3870 100 2482", warden.text());
        Answerer.Reply town = demo.answer(new Answerer.Question(kb, List.of(), "Ile kosztuje town", "pl_pl", "u"));
        assertEquals("(demo) Dwa tysiące diamentów.", town.text());
        assertEquals(Answerer.Status.UNKNOWN, demo.answer(new Answerer.Question(kb, List.of(), "Pogoda?", "pl_pl", "u")).status());
    }

    @Test
    void adminsByUuidOrName() {
        ServerConfig config = new ServerConfig();
        config.admins = List.of("069a79f4-44e9-4726-a5be-fca90e38aaf5", "SomeName");
        assertTrue(config.isAdmin("069a79f444e94726a5befca90e38aaf5", "Other"));
        assertTrue(config.isAdmin("ffffffff-ffff-ffff-ffff-ffffffffffff", "somename"));
        assertFalse(config.isAdmin("ffffffff-ffff-ffff-ffff-ffffffffffff", "Someone"));
    }
}
