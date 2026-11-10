package com.betterloka.assistantserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code assistant.json}: everything the owner may want to change, with defaults that work.
 * The API key is not in here: it comes from {@code ANTHROPIC_API_KEY} or {@code api-key.txt}.
 */
public final class ServerConfig {
    /** Where the mod connects. A game panel's own port wins: see {@link #effectivePort()}. */
    public int port = 8787;
    public String bind = "0.0.0.0";

    /** The model that answers players. One line to change: a cheaper one costs less per question. */
    public String model = "claude-opus-5-5";
    /** How hard the model thinks: low, medium, high. Short Q&A rarely gains from more. */
    public String effort = "low";
    public long maxOutputTokens = 2048;
    /** How long the knowledge base stays cached between questions: 5m or 1h. */
    public String cacheTtl = "5m";

    /** Spending, in US dollars. The server stops answering before it goes over. */
    public double monthlyBudgetUsd = 50.0;
    /** A hard cap per day; 0 spreads what is left of the month over the days left, see {@link Ledger}. */
    public double dailyBudgetUsd = 0;
    /** How far above an even share one busy day may go when there is no hard daily cap. */
    public double dailyBurst = 2.0;

    public int questionsPerDay = 30;
    public int proposalsPerDay = 10;
    public int maxQuestionChars = 300;
    public int maxProposalChars = 1200;
    /** How many earlier questions and answers travel with a follow-up. */
    public int historyTurns = 3;
    /** Claude calls running at once; the rest wait their turn. */
    public int concurrentAnswers = 4;

    /** Who may approve players' answers: Minecraft UUIDs (or names, which can change hands). */
    public List<String> admins = new ArrayList<>();
    /** Optional: a Discord webhook told about unanswered questions and new proposals. Keep it secret. */
    public String discordWebhookUrl = "";

    /**
     * Testing only: trust whatever name the mod sends instead of asking Mojang. Anyone could then
     * pose as anyone, so the server says so loudly at start.
     */
    public boolean skipMojangCheck = false;
    /** Behind Caddy/nginx on the same machine, the player's address comes from X-Forwarded-For. */
    public boolean trustForwardedFor = false;

    /** Optional HTTPS proxy for the Claude API, {@code host:port}. Empty means direct. */
    public String proxy = "";
    /** Optional: another address for the Claude API, used by the tests. */
    public String apiBaseUrl = "";

    /** Prices per million tokens, for models not already known to {@link Pricing}. */
    public Map<String, Pricing.Price> prices = new LinkedHashMap<>();

    public Crawl crawl = new Crawl();
    public Distill distill = new Distill();

    /** What {@code crawl} reads. Every source can be turned off. */
    public static final class Crawl {
        public String userAgent = "BetterLoka-Assistant-KB/1.0 (knowledge base builder for the BetterLoka mod)";
        /** Pause between two requests to the same site. */
        public long delayMillis = 1200;
        public boolean site = true;
        public boolean forum = true;
        /** Forum sections to read, by number: 5 is News, Announcements & Server Information. */
        public List<Integer> forumIds = new ArrayList<>(List.of(5));
        public int maxForumThreads = 400;
        public boolean wiki = true;
        public int maxWikiPages = 600;
    }

    /** How {@code distill} turns the sources into the knowledge base. */
    public static final class Distill {
        public String model = "claude-opus-5-5";
        public String effort = "medium";
        /** Sources per call, in estimated tokens. */
        public int chunkTokens = 60000;
    }

    public static ServerConfig load(Path file) {
        ServerConfig config = Json.read(file, ServerConfig.class, ServerConfig::new);
        if (config.crawl == null) {
            config.crawl = new Crawl();
        }
        if (config.distill == null) {
            config.distill = new Distill();
        }
        if (config.admins == null) {
            config.admins = new ArrayList<>();
        }
        if (config.prices == null) {
            config.prices = new LinkedHashMap<>();
        }
        if (!Files.exists(file)) {
            Json.write(file, config);
            Log.info("Wrote default settings to " + file.toAbsolutePath());
        }
        return config;
    }

    /**
     * The port to listen on. Game panels (Pterodactyl and the like) hand each server one port in
     * {@code SERVER_PORT}; other hosts often use {@code PORT}. Either wins over the file.
     */
    public int effectivePort() {
        for (String name : List.of("SERVER_PORT", "PORT")) {
            String value = System.getenv(name);
            if (value != null && value.matches("\\d{1,5}")) {
                return Integer.parseInt(value);
            }
        }
        return port;
    }

    /** Whether a verified player may approve answers. */
    public boolean isAdmin(String uuid, String name) {
        String plain = uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
        for (String admin : admins) {
            if (admin == null) {
                continue;
            }
            String entry = admin.strip().toLowerCase(Locale.ROOT);
            if (entry.replace("-", "").equals(plain) || (name != null && entry.equals(name.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }
}
