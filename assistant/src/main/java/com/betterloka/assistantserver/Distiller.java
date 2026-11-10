package com.betterloka.assistantserver;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns what the crawler read into the knowledge base, in two passes with Claude:
 * <ol>
 * <li>the sources, a chunk at a time, become notes: every fact a player could ask about, sorted
 * under fixed topics and tagged with where and when it was said;</li>
 * <li>each topic's notes from all chunks become one chapter in {@code kb/generated/}, duplicates
 * merged and the newer word kept where sources disagree.</li>
 * </ol>
 * Notes are kept in {@code data/distill/}, keyed by their chunk's contents, so a run that stops
 * half way picks up where it was and a rerun after a new crawl only pays for what changed.
 */
final class Distiller {
    record Topic(String id, String title, int words) {
    }

    static final List<Topic> TOPICS = List.of(
            new Topic("basics", "Getting started on Loka", 1500),
            new Topic("commands", "Commands", 1500),
            new Topic("towns", "Towns", 1800),
            new Topic("conquest", "Conquest, territories and battles", 1800),
            new Topic("pvp", "Battlegrounds, arenas and PvP", 1200),
            new Topic("market", "Market and economy", 1200),
            new Topic("world", "The world: continents, travel and resources", 1500),
            new Topic("zyre", "Zyre", 1200),
            new Topic("bosses", "Bosses, dungeons and mobs", 1500),
            new Topic("quests", "Quests", 1200),
            new Topic("items", "Custom items, enchantments and features", 1800),
            new Topic("events", "Events and festivals", 1200),
            new Topic("rules", "Rules", 1200),
            new Topic("ranks", "Ranks, store and cosmetics", 1000),
            new Topic("history", "Lore and history", 800),
            new Topic("other", "Everything else", 800));

    static final String EXTRACT = """
            You are building the knowledge base of Loka Assistant, a helper that answers players' \
            questions about Loka (play.lokamc.com), a Minecraft server.

            Below are pages from Loka's website, its forum announcements and its wiki. Write down \
            every fact a player could ask about: how things work, rules, commands, numbers, costs, \
            cooldowns, coordinates, places, names of items, bosses and events, schedules, \
            requirements, rewards, and what changed when. Leave out chit-chat, thanks, sign-ups, \
            link lists and anything no player would ask about.

            Sort the facts under these topics, as markdown headings "## <topic id>", using only \
            these ids:
            %s

            Under each heading, one fact per bullet. Keep exact numbers, names and commands. End \
            each bullet with its source in brackets: its kind and date if known, such as \
            [forum 2024-05-02], [site] or [wiki 2023-11-12]. When a page announces a change, say \
            what changed and when. Leave out a topic that has nothing. Write in English.""";

    static final String MERGE = """
            You are writing one chapter of the knowledge base of Loka Assistant, a helper that \
            answers players' questions about Loka (play.lokamc.com), a Minecraft server. The \
            chapter is "%s".

            Below are notes gathered from Loka's website, forum announcements and wiki, each fact \
            tagged with its source and date. Write the chapter from them:
            - Keep every fact a player could ask about, with exact numbers, names, commands and \
            coordinates.
            - Merge duplicates. Where notes disagree, keep the newer or the official (site) one; \
            if it is unclear which is current, keep both with their dates.
            - Mark facts that may be out of date (old forum posts, wiki pages not edited for \
            years) with their date.
            - Organize with "### " subheadings and short bullets. No introduction or closing \
            remarks.
            - Keep it compact: about %d words at most.
            Write in English, starting with the line "# %s".""";

    private final ServerConfig config;
    private final AnthropicClient client;
    private final Path sources;
    private final Path generated;
    private final Path work;
    private double spent;
    private Pricing.Usage used = new Pricing.Usage(0, 0, 0, 0, 0);

    Distiller(ServerConfig config, AnthropicClient client, Path sources, Path generated, Path work) {
        this.config = config;
        this.client = client;
        this.sources = sources;
        this.generated = generated;
        this.work = work;
    }

    /** Sources sorted the way they should be read: the website, then the forum and wiki, newest first. */
    static List<Crawler.Source> ordered(List<Crawler.Source> all) {
        Comparator<Crawler.Source> byKind = Comparator.comparingInt(source -> switch (source.kind()) {
            case "site" -> 0;
            case "forum" -> 1;
            default -> 2;
        });
        return all.stream()
                .sorted(byKind.thenComparing(Crawler.Source::date, Comparator.reverseOrder()).thenComparing(Crawler.Source::title))
                .toList();
    }

    /** Sources packed into chunks of at most {@code limit} estimated tokens; a longer page is cut. */
    static List<String> chunks(List<Crawler.Source> sources, int limit) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int tokens = 0;
        for (Crawler.Source source : sources) {
            String text = source.text();
            int cap = limit * 4 - 400;
            if (text.length() > cap) {
                text = text.substring(0, cap);
            }
            String block = "<source kind=\"" + source.kind() + "\" date=\"" + source.date() + "\" url=\"" + source.url()
                    + "\">\n# " + source.title() + "\n" + text + "\n</source>\n\n";
            int blockTokens = (int) Math.ceil(block.length() / 4.0);
            if (tokens > 0 && tokens + blockTokens > limit) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
                tokens = 0;
            }
            chunk.append(block);
            tokens += blockTokens;
        }
        if (tokens > 0) {
            chunks.add(chunk.toString());
        }
        return chunks;
    }

    /** Roughly what a run will cost, in dollars, before it starts. */
    static double estimate(List<String> chunks, String model, ServerConfig config) {
        long input = 0;
        for (String chunk : chunks) {
            input += chunk.length() / 4 + 600;
        }
        long notes = Math.min(input / 6, chunks.size() * 12_000L);
        long merged = TOPICS.size() * 3_000L;
        // Thinking is billed as output; at medium effort count about half again.
        Pricing.Usage usage = new Pricing.Usage(input + notes + TOPICS.size() * 700L, (long) ((notes + merged) * 1.5), 0, 0, 0);
        return Pricing.cost(model, usage, config.prices);
    }

    void run(List<String> chunks) throws IOException {
        Files.createDirectories(work);
        Files.createDirectories(generated);
        List<Path> keep = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            Path notes = work.resolve(String.format(Locale.ROOT, "notes-%s.md", KnowledgeBase.fingerprint(chunk)));
            keep.add(notes);
            if (Files.exists(notes)) {
                Log.info("Chunk " + (i + 1) + "/" + chunks.size() + ": already done");
                continue;
            }
            Log.info("Chunk " + (i + 1) + "/" + chunks.size() + ": reading " + (chunk.length() / 4) + " tokens of sources");
            String text = call(String.format(EXTRACT, topicList()), chunk, 16_000);
            if (text != null) {
                Files.writeString(notes, text, StandardCharsets.UTF_8);
            }
        }
        try (Stream<Path> old = Files.list(work)) {
            for (Path file : old.filter(p -> p.getFileName().toString().startsWith("notes-")).toList()) {
                if (!keep.contains(file)) {
                    Files.delete(file);
                }
            }
        }

        Map<String, StringBuilder> byTopic = new LinkedHashMap<>();
        for (Path notes : keep) {
            if (Files.exists(notes)) {
                split(Files.readString(notes, StandardCharsets.UTF_8), byTopic);
            }
        }
        for (Topic topic : TOPICS) {
            StringBuilder notes = byTopic.get(topic.id());
            Path chapter = generated.resolve(topic.id() + ".md");
            if (notes == null || notes.toString().isBlank()) {
                Files.deleteIfExists(chapter);
                continue;
            }
            Log.info("Writing chapter \"" + topic.title() + "\" from " + (notes.length() / 4) + " tokens of notes");
            String text = call(String.format(MERGE, topic.title(), topic.words(), topic.title()), notes.toString(), 12_000);
            if (text != null) {
                Files.writeString(chapter, text.strip() + "\n", StandardCharsets.UTF_8);
            }
        }
        Log.info(String.format(Locale.ROOT, "Knowledge base written to %s; this run cost about $%.2f", generated.toAbsolutePath(), spent));
    }

    double spent() {
        return spent;
    }

    /** The notes of one or more chunks, gathered by topic heading. */
    static void split(String notes, Map<String, StringBuilder> byTopic) {
        String topic = null;
        for (String line : notes.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("## ")) {
                String id = stripped.substring(3).strip().toLowerCase(Locale.ROOT);
                topic = TOPICS.stream().anyMatch(t -> t.id().equals(id)) ? id : "other";
                continue;
            }
            if (topic != null && !stripped.isEmpty()) {
                byTopic.computeIfAbsent(topic, key -> new StringBuilder()).append(stripped).append('\n');
            }
        }
    }

    private static String topicList() {
        return TOPICS.stream().map(t -> "- " + t.id() + ": " + t.title()).collect(Collectors.joining("\n"));
    }

    private String call(String system, String user, long maxTokens) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(config.distill.model)
                .maxTokens(maxTokens)
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacksDefault()
                .system(system)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(config.distill.effort)).build())
                .addUserMessage(user)
                .build();
        BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
        try (StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params)) {
            stream.stream().forEach(accumulator::accumulate);
        } catch (AnthropicServiceException | AnthropicIoException e) {
            Log.warn("Claude call failed: " + e.getMessage());
            return null;
        }
        BetaMessage message = accumulator.message();
        Pricing.Usage usage = ClaudeAnswerer.usage(message.usage());
        used = used.plus(usage);
        spent += Pricing.cost(message.model().asString(), usage, config.prices);
        if (message.stopReason().isPresent() && message.stopReason().get().equals(BetaStopReason.REFUSAL)) {
            Log.warn("Claude declined this part; skipping it");
            return null;
        }
        if (message.stopReason().isPresent() && message.stopReason().get().equals(BetaStopReason.MAX_TOKENS)) {
            Log.warn("This part ran out of room; keeping what was written");
        }
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(BetaTextBlock::text)
                .collect(Collectors.joining("\n"));
    }
}
