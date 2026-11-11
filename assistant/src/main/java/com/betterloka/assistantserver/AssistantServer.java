package com.betterloka.assistantserver;

import com.anthropic.client.AnthropicClient;

import com.betterloka.bot.BetterLokaBot;
import com.betterloka.bot.BotConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * The Loka Assistant server: one jar, run in a folder that holds its settings, key, knowledge base
 * and state.
 *
 * <pre>
 *   java -jar betterloka-assistant.jar            start answering (same as "serve")
 *   java -jar betterloka-assistant.jar build-kb   read Loka's website, forum and wiki, then write the knowledge base
 *   java -jar betterloka-assistant.jar crawl      only read Loka's pages into sources/
 *   java -jar betterloka-assistant.jar distill    only write the knowledge base from sources/ (uses Claude)
 *   java -jar betterloka-assistant.jar ask "..."  ask one question from the console
 *   java -jar betterloka-assistant.jar kb-info    how big the knowledge base is, and what a question costs
 * </pre>
 * Options: {@code --dir <folder>} (default: here), {@code --yes} (don't ask before spending),
 * {@code --demo} (serve canned answers without a key, for trying the screen).
 */
public final class AssistantServer {
    private AssistantServer() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(".");
        boolean yes = false;
        boolean demo = false;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = Path.of(args[++i]);
                case "--yes", "-y" -> yes = true;
                case "--demo" -> demo = true;
                default -> rest.add(args[i]);
            }
        }
        String command = rest.isEmpty() ? "serve" : rest.get(0).toLowerCase(Locale.ROOT);
        Files.createDirectories(dir);
        ServerConfig config = ServerConfig.load(dir.resolve("assistant.json"));
        seed(dir.resolve("kb"));

        switch (command) {
            case "serve" -> serve(dir, config, demo);
            case "crawl" -> crawl(dir, config);
            case "distill" -> distill(dir, config, yes);
            case "build-kb" -> {
                crawl(dir, config);
                distill(dir, config, yes);
            }
            case "ask" -> ask(dir, config, String.join(" ", rest.subList(1, rest.size())));
            case "kb-info" -> kbInfo(dir, config);
            default -> {
                System.out.println("""
                        Loka Assistant server
                          java -jar betterloka-assistant.jar            start answering
                          java -jar betterloka-assistant.jar build-kb   read Loka's pages and write the knowledge base
                          java -jar betterloka-assistant.jar crawl      only read Loka's pages into sources/
                          java -jar betterloka-assistant.jar distill    only write the knowledge base from sources/
                          java -jar betterloka-assistant.jar ask "..."  ask one question from the console
                          java -jar betterloka-assistant.jar kb-info    knowledge base size and cost per question
                        Options: --dir <folder>, --yes, --demo""");
            }
        }
    }

    private static void serve(Path dir, ServerConfig config, boolean demo) throws Exception {
        Clock clock = Clock.systemDefaultZone();
        Path data = dir.resolve("data");
        String key = apiKey(dir);
        Answerer answerer;
        if (demo) {
            Log.warn("DEMO MODE: answers are canned lines from the knowledge base, not Claude.");
            answerer = new DemoAnswerer();
        } else if (key != null) {
            answerer = new ClaudeAnswerer(ClaudeAnswerer.client(config, key, Duration.ofSeconds(90)), config);
        } else {
            Log.warn("No Claude API key: put it in " + dir.resolve("api-key.txt").toAbsolutePath()
                    + " or in ANTHROPIC_API_KEY. The server runs, but cannot answer.");
            answerer = null;
        }
        MojangCheck mojang = MojangCheck.mojang();
        if (config.skipMojangCheck) {
            Log.warn("skipMojangCheck is on: anyone can pose as any player. Use it only for testing.");
            mojang = MojangCheck.trusting();
        }
        KnowledgeBase knowledge = new KnowledgeBase(dir.resolve("kb"));
        Ledger ledger = new Ledger(data.resolve("ledger.json"), config, clock);
        Community community = new Community(data.resolve("community.json"),
                dir.resolve("kb").resolve(KnowledgeBase.COMMUNITY), clock);
        AssistantService service = new AssistantService(config, knowledge, answerer, ledger,
                new Sessions(Sessions.loadSecret(data.resolve("secret.key")), clock), mojang, community,
                new Discord(config.discordWebhookUrl), data.resolve("questions.jsonl"), clock);

        // The fallen-town bot runs in this same process, over one Discord connection, so a panel
        // that runs one program per server can host both.
        BotConfig botConfig = BetterLokaBot.loadEmbeddedConfig(dir);
        DiscordQuestions questions = null;
        if (!config.discordQuestions.channelId.isBlank()) {
            if (botConfig.botToken.isBlank()) {
                Log.warn("discordQuestions.channelId is set, but posting questions needs a bot: put its botToken in "
                        + dir.resolve(BetterLokaBot.CONFIG_NAME).toAbsolutePath());
            } else {
                questions = new DiscordQuestions(config, community, ledger, knowledge,
                        new DiscordRest(null, botConfig.botToken), data.resolve("discord-questions.json"));
            }
        }
        BetterLokaBot.Embedded bot = BetterLokaBot.startEmbedded(botConfig, questions == null ? null : questions::handle);
        if (questions != null) {
            questions.start();
            Log.info("Unanswered questions go to Discord channel " + config.discordQuestions.channelId
                    + (config.discordQuestions.answerRoleIds.isEmpty() && config.discordQuestions.approveRoleIds.isEmpty()
                    ? " (no roles set yet: only server administrators can answer)" : ""));
        }
        HttpApi api = new HttpApi(service, config.trustForwardedFor);
        int port = config.effectivePort();
        api.start(config.bind, port);
        KnowledgeBase.Snapshot kb = knowledge.current();
        Log.info(String.format(Locale.ROOT, "Loka Assistant is listening on port %d (model %s, effort %s)", port,
                config.model, config.effort));
        Log.info(String.format(Locale.ROOT, "Budget: $%.2f of $%.2f spent this month; today may spend up to $%.2f",
                ledger.monthUsd(), config.monthlyBudgetUsd, ledger.allowedToday()));
        if (kb.files() == 0) {
            Log.warn("The knowledge base is empty: run build-kb, or put .md files in " + knowledge.dir().toAbsolutePath());
        }
        Log.info("Type \"status\" for spending, \"stop\" to stop.");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
        Thread console = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    switch (line.strip().toLowerCase(Locale.ROOT)) {
                        case "stop", "end", "exit", "quit" -> {
                            stopped.countDown();
                            return;
                        }
                        case "status" -> Log.info(String.format(Locale.ROOT,
                                "Spent $%.4f today (may spend $%.2f), $%.2f this month of $%.2f; %d questions so far; knowledge base %d tokens",
                                ledger.dayUsd(), ledger.allowedToday(), ledger.monthUsd(), config.monthlyBudgetUsd,
                                ledger.totalQuestions(), knowledge.current().estimatedTokens()));
                        case "" -> {
                        }
                        default -> Log.info("Commands: status, stop");
                    }
                }
            } catch (IOException ignored) {
                // No console (a service): keep running until the process is stopped.
            }
        }, "assistant-console");
        console.setDaemon(true);
        console.start();
        stopped.await();
        if (questions != null) {
            questions.stop();
        }
        bot.stop();
        api.stop();
        Log.info("Stopped.");
        System.exit(0);
    }

    private static void crawl(Path dir, ServerConfig config) throws IOException, InterruptedException {
        Log.info("Reading Loka's pages into " + dir.resolve("sources").toAbsolutePath()
                + " (a few hundred pages, about one a second)");
        new Crawler(config.crawl, dir.resolve("sources")).run();
    }

    private static void distill(Path dir, ServerConfig config, boolean yes) throws IOException {
        List<String> chunks = Distiller.chunks(Distiller.ordered(Crawler.readAll(dir.resolve("sources"))),
                Math.max(8_000, config.distill.chunkTokens));
        if (chunks.isEmpty()) {
            Log.warn("Nothing in sources/ yet: run crawl first.");
            return;
        }
        double estimate = Distiller.estimate(chunks, config.distill.model, config);
        Log.info(String.format(Locale.ROOT, "%d chunks of sources; with %s this costs roughly $%.0f-%.0f (parts already done are free)",
                chunks.size(), config.distill.model, Math.floor(estimate * 0.6), Math.ceil(estimate * 1.3)));
        String key = apiKey(dir);
        if (key == null) {
            Log.warn("Writing the knowledge base needs a Claude API key: put it in "
                    + dir.resolve("api-key.txt").toAbsolutePath() + " or in ANTHROPIC_API_KEY.");
            return;
        }
        if (!yes && !confirm()) {
            Log.info("Stopped; nothing was spent. Run again with --yes to skip this question.");
            return;
        }
        AnthropicClient client = ClaudeAnswerer.client(config, key, Duration.ofMinutes(15));
        Distiller distiller = new Distiller(config, client, dir.resolve("sources"), dir.resolve("kb").resolve("generated"),
                dir.resolve("data").resolve("distill"));
        distiller.run(chunks);
        new Ledger(dir.resolve("data").resolve("ledger.json"), config, Clock.systemDefaultZone()).spend(distiller.spent());
    }

    private static void ask(Path dir, ServerConfig config, String question) throws Exception {
        String key = apiKey(dir);
        if (key == null || question.isBlank()) {
            Log.warn(key == null ? "No Claude API key." : "Usage: ask \"your question\"");
            return;
        }
        KnowledgeBase knowledge = new KnowledgeBase(dir.resolve("kb"));
        ClaudeAnswerer answerer = new ClaudeAnswerer(ClaudeAnswerer.client(config, key, Duration.ofSeconds(90)), config);
        Answerer.Reply reply = answerer.answer(new Answerer.Question(knowledge.current().text(), List.of(), question,
                "pl_pl", "console"));
        System.out.println();
        System.out.println(reply.text());
        System.out.println();
        System.out.printf(Locale.ROOT, "[%s, %s, $%.4f: %d in, %d cached read, %d cache write, %d out]%n",
                reply.status().name().toLowerCase(Locale.ROOT), reply.model(), reply.costUsd(), reply.usage().input(),
                reply.usage().cacheRead(), reply.usage().cacheWrite5m() + reply.usage().cacheWrite1h(), reply.usage().output());
        new Ledger(dir.resolve("data").resolve("ledger.json"), config, Clock.systemDefaultZone()).spend(reply.costUsd());
    }

    private static void kbInfo(Path dir, ServerConfig config) {
        KnowledgeBase.Snapshot kb = new KnowledgeBase(dir.resolve("kb")).current();
        long prompt = kb.estimatedTokens() + 700;
        Pricing.Usage warm = new Pricing.Usage(150, 500, prompt, 0, 0);
        Pricing.Usage cold = new Pricing.Usage(150, 500, 0, prompt, 0);
        System.out.printf(Locale.ROOT, "Knowledge base: %d files, about %d tokens%n", kb.files(), kb.estimatedTokens());
        System.out.printf(Locale.ROOT, "With %s, a question costs about $%.4f when the knowledge base is cached "
                        + "(another question within %s) and $%.4f when it is not.%n", config.model,
                Pricing.cost(config.model, warm, config.prices), config.cacheTtl,
                Pricing.cost(config.model, cold, config.prices));
        System.out.printf(Locale.ROOT, "$%.0f a month is about %d to %d questions.%n", config.monthlyBudgetUsd,
                (long) (config.monthlyBudgetUsd / Pricing.cost(config.model, cold, config.prices)),
                (long) (config.monthlyBudgetUsd / Pricing.cost(config.model, warm, config.prices)));
    }

    /** {@code ANTHROPIC_API_KEY}, else the first line of {@code api-key.txt}; never printed. */
    static String apiKey(Path dir) throws IOException {
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.strip();
        }
        Path file = dir.resolve("api-key.txt");
        if (Files.isRegularFile(file)) {
            String key = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!key.isEmpty()) {
                return key.lines().findFirst().orElse("").strip();
            }
        }
        return null;
    }

    /** First start: a short page about BetterLoka and its waypoints, so the assistant knows something. */
    private static void seed(Path kb) throws IOException {
        Path file = kb.resolve("betterloka.md");
        if (Files.exists(kb)) {
            return;
        }
        Files.createDirectories(kb);
        try (InputStream in = AssistantServer.class.getResourceAsStream("/seed/betterloka.md")) {
            if (in != null) {
                Files.write(file, in.readAllBytes());
            }
        }
    }

    private static boolean confirm() {
        if (System.console() == null) {
            // No one to ask (a panel or a service): spending needs --yes.
            return false;
        }
        System.out.print("Go ahead? Type yes: ");
        System.out.flush();
        String answer = System.console().readLine();
        return answer != null && answer.strip().equalsIgnoreCase("yes");
    }
}
