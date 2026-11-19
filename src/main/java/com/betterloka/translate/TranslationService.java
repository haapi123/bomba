package com.betterloka.translate;

import com.betterloka.api.ApiException;
import com.betterloka.api.HttpTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.betterloka.assistant.AssistantClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Machine translation for the Translator module.
 *
 * <p>Lines go to the BetterLoka server, which translates them with Claude — its cheapest model,
 * thinking off, a few lines a request — and keeps what it translated, so a line every player with
 * the Translator open asks for is paid for once. The key stays on the server: the mod only logs in
 * the way Loka Assistant does. Lines asked for within a moment of each other travel together.
 *
 * <p>When the server cannot translate — down, out of budget, the player's allowance spent, no
 * Minecraft login — the line goes to Google's public {@code translate_a} endpoint instead, which
 * needs no key, and the server is left alone for a while. A translation is never missing because
 * the AI is.
 *
 * <p>Either way the answer says which language it detected as well as the translation, which is how
 * a message is labelled with the language it was written in, and how a message already in the
 * reader's language is recognised and left alone.
 */
public final class TranslationService {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";

    /** Longest text accepted; the endpoint is a GET, so the whole message rides in the query string. */
    public static final int MAX_LENGTH = 900;

    private static final int CACHE_SIZE = 300;

    /**
     * What came back: the translation, and the language the original was detected as, or
     * {@code null} when the service did not say.
     */
    public record Translation(String text, String source, String engine) {
        public static final String AI = "ai";
        public static final String GOOGLE = "google";

        public Translation(String text, String source) {
            this(text, source, GOOGLE);
        }

        public boolean byAi() {
            return AI.equals(engine);
        }

        /** Whether the original was already in the language it was translated into. */
        public boolean sameLanguage(String target) {
            return source != null && baseCode(source).equals(baseCode(target));
        }
    }

    /** Languages offered in the Translator's pickers, keyed by ISO code, each named in itself. */
    public static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("pl", "Polski");
        LANGUAGES.put("de", "Deutsch");
        LANGUAGES.put("es", "Español");
        LANGUAGES.put("fr", "Français");
        LANGUAGES.put("it", "Italiano");
        LANGUAGES.put("pt", "Português");
        LANGUAGES.put("nl", "Nederlands");
        LANGUAGES.put("sv", "Svenska");
        LANGUAGES.put("da", "Dansk");
        LANGUAGES.put("no", "Norsk");
        LANGUAGES.put("fi", "Suomi");
        LANGUAGES.put("cs", "Čeština");
        LANGUAGES.put("sk", "Slovenčina");
        LANGUAGES.put("hu", "Magyar");
        LANGUAGES.put("ro", "Română");
        LANGUAGES.put("lt", "Lietuvių");
        LANGUAGES.put("lv", "Latviešu");
        LANGUAGES.put("et", "Eesti");
        LANGUAGES.put("uk", "Українська");
        LANGUAGES.put("ru", "Русский");
        LANGUAGES.put("tr", "Türkçe");
        LANGUAGES.put("el", "Ελληνικά");
        LANGUAGES.put("ja", "日本語");
        LANGUAGES.put("zh-CN", "中文");
        LANGUAGES.put("ko", "한국어");
    }

    /**
     * A language's name in that language. Codes the pickers do not offer — the detector can answer
     * with any of a hundred — are named by Java's own table, and failing that shown as the code.
     */
    public static String languageName(String code) {
        if (code == null || code.isBlank()) {
            return "?";
        }
        String offered = LANGUAGES.get(code);
        if (offered != null) {
            return offered;
        }
        java.util.Locale locale = java.util.Locale.forLanguageTag(code);
        String name = locale.getDisplayLanguage(locale);
        if (name.isBlank() || name.equalsIgnoreCase(code)) {
            return code;
        }
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    /** {@code zh-CN} to {@code zh}: the detector and the pickers do not always agree on regions. */
    static String baseCode(String code) {
        int dash = code.indexOf('-');
        return (dash < 0 ? code : code.substring(0, dash)).toLowerCase(java.util.Locale.ROOT);
    }

    /** Sends lines to the server's AI; the tests give a stand-in. */
    public interface AiTranslator {
        CompletableFuture<List<AssistantClient.Translated>> translate(List<String> texts, List<String> sources, String target);
    }

    /** The translation that needs no server: Google's. The tests give a stand-in. */
    interface Machine {
        Translation translate(String text, String source, String target) throws ApiException;
    }

    /** Lines sent to the server in one request, and how long a line waits for others to join it. */
    static final int BATCH = 20;
    static final long GATHER_MILLIS = 120;

    private record Pending(String key, String text, String source, String target, CompletableFuture<Translation> future) {
    }

    private final HttpTransport transport;
    private final Supplier<AiTranslator> ai;
    private final BooleanSupplier useAi;
    private final Machine machine;
    private final List<Pending> queue = new ArrayList<>();
    private final Map<String, CompletableFuture<Translation>> inFlight = new HashMap<>();
    private final ScheduledExecutorService gather = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BetterLoka translator batches");
        thread.setDaemon(true);
        return thread;
    });
    private boolean flushScheduled;
    /** Until when the server is left alone after it could not translate. */
    private volatile long aiPausedUntil;

    /** Chat repeats itself a lot — the same call to arms goes out several times a night. */
    private final Map<String, Translation> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Translation> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public TranslationService(HttpTransport transport) {
        this(transport, null, () -> false);
    }

    /**
     * @param ai    the server's AI, asked for when a batch goes out
     * @param useAi whether to use it (the player's choice); without, every line goes to Google
     */
    public TranslationService(HttpTransport transport, Supplier<AiTranslator> ai, BooleanSupplier useAi) {
        this(transport, ai, useAi, null);
    }

    TranslationService(HttpTransport transport, Supplier<AiTranslator> ai, BooleanSupplier useAi, Machine machine) {
        this.transport = transport;
        this.ai = ai;
        this.useAi = useAi;
        this.machine = machine != null ? machine : this::request;
    }

    /**
     * Translates off the render thread.
     *
     * @param source source language code, or {@code "auto"} to detect it.
     * @return the translation and the detected language; the future fails with
     * {@link ApiException} if the service is unreachable.
     */
    public CompletableFuture<Translation> translate(String text, String source, String target) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(new Translation("", null));
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }

        String key = source + '>' + target + '>' + trimmed;
        synchronized (cache) {
            Translation hit = cache.get(key);
            if (hit != null) {
                return CompletableFuture.completedFuture(hit);
            }
        }

        String payload = trimmed;
        if (ai == null || !useAi.getAsBoolean() || System.currentTimeMillis() < aiPausedUntil) {
            return google(key, payload, source, target);
        }
        synchronized (queue) {
            // The same line asked for twice before it is back: one request, one answer.
            CompletableFuture<Translation> waiting = inFlight.get(key);
            if (waiting != null) {
                return waiting;
            }
            CompletableFuture<Translation> future = new CompletableFuture<>();
            inFlight.put(key, future);
            queue.add(new Pending(key, payload, source, target, future));
            if (queue.size() >= BATCH) {
                gather.execute(this::flush);
            } else if (!flushScheduled) {
                flushScheduled = true;
                gather.schedule(this::flush, GATHER_MILLIS, TimeUnit.MILLISECONDS);
            }
            return future;
        }
    }

    /** Sends the lines waiting, up to a batch with one target language, to the server. */
    private void flush() {
        List<Pending> batch = new ArrayList<>();
        synchronized (queue) {
            flushScheduled = false;
            if (queue.isEmpty()) {
                return;
            }
            String target = queue.get(0).target();
            for (java.util.Iterator<Pending> it = queue.iterator(); it.hasNext() && batch.size() < BATCH; ) {
                Pending pending = it.next();
                if (pending.target().equals(target)) {
                    batch.add(pending);
                    it.remove();
                }
            }
            if (!queue.isEmpty()) {
                flushScheduled = true;
                gather.execute(this::flush);
            }
        }
        List<String> texts = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        for (Pending pending : batch) {
            texts.add(pending.text());
            sources.add(pending.source());
        }
        CompletableFuture<List<AssistantClient.Translated>> asked;
        try {
            AiTranslator translator = ai.get();
            asked = translator == null ? CompletableFuture.failedFuture(new IllegalStateException("no server"))
                    : translator.translate(texts, sources, batch.get(0).target());
        } catch (RuntimeException e) {
            asked = CompletableFuture.failedFuture(e);
        }
        asked.whenComplete((results, error) -> {
            if (error != null) {
                pause(error);
            }
            for (int i = 0; i < batch.size(); i++) {
                Pending pending = batch.get(i);
                AssistantClient.Translated result = error == null && results != null && i < results.size() ? results.get(i) : null;
                if (result != null) {
                    Translation translation = new Translation(result.text(), result.source(), Translation.AI);
                    synchronized (cache) {
                        cache.put(pending.key(), translation);
                    }
                    finish(pending, translation, null);
                } else {
                    // The server could not: Google, for this line.
                    google(pending.key(), pending.text(), pending.source(), pending.target())
                            .whenComplete((translation, failure) -> finish(pending, translation, failure));
                }
            }
        });
    }

    private void finish(Pending pending, Translation translation, Throwable failure) {
        synchronized (queue) {
            inFlight.remove(pending.key());
        }
        if (failure != null) {
            pending.future().completeExceptionally(failure);
        } else {
            pending.future().complete(translation);
        }
    }

    /**
     * The server could not translate: leave it alone for a while — longer when it will not be able
     * to for a while (out of budget, the allowance spent, no key) than when it merely did not answer.
     */
    private void pause(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        String code = cause instanceof AssistantClient.Failure failure ? failure.code() : "offline";
        long minutes = switch (code) {
            case "player_limit", "budget_day", "budget_month", "no_key" -> 15;
            case "login", "not_verified" -> 5;
            default -> 1;
        };
        aiPausedUntil = System.currentTimeMillis() + minutes * 60_000L;
        com.betterloka.BetterLoka.LOGGER.info("[betterloka] Translator: the server could not translate ({}); "
                + "Google for the next {} min", code, minutes);
    }

    private CompletableFuture<Translation> google(String key, String text, String source, String target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Translation result = machine.translate(text, source, target);
                synchronized (cache) {
                    cache.put(key, result);
                }
                return result;
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, transport == null ? java.util.concurrent.ForkJoinPool.commonPool() : transport.executor());
    }

    private Translation request(String text, String source, String target) throws ApiException {
        String url = ENDPOINT + "?client=gtx&sl=" + encode(source) + "&tl=" + encode(target)
                + "&dt=t&q=" + encode(text);
        return parse(transport.get(url));
    }

    /**
     * The response is a nested array; the first element holds one entry per sentence, whose first
     * member is the translated text, and the third element is the language the original was
     * detected as.
     */
    static Translation parse(String body) throws ApiException {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) {
                throw new ApiException("Unexpected translation response", false);
            }
            JsonArray segments = root.getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder out = new StringBuilder();
            for (JsonElement segment : segments) {
                if (!segment.isJsonArray()) {
                    continue;
                }
                JsonElement piece = segment.getAsJsonArray().get(0);
                if (piece != null && !piece.isJsonNull()) {
                    out.append(piece.getAsString());
                }
            }
            JsonArray all = root.getAsJsonArray();
            String source = null;
            if (all.size() > 2 && all.get(2).isJsonPrimitive()) {
                source = all.get(2).getAsString();
            }
            return new Translation(out.toString().trim(), source);
        } catch (JsonSyntaxException | IllegalStateException | IndexOutOfBoundsException e) {
            throw new ApiException("Could not read the translation response", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
