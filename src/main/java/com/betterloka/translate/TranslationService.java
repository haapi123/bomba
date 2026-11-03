package com.betterloka.translate;

import com.betterloka.api.ApiException;
import com.betterloka.api.HttpTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Machine translation for the Translator module.
 *
 * <p>Uses Google's public {@code translate_a} endpoint, which needs no API key — important for a mod
 * anyone can download, since a keyed service would mean either shipping a shared secret or asking
 * every player to register for one. It answers a chat line in a fraction of a second, which is what
 * a conversation needs; a language model would read slang a little better but takes seconds a line
 * and costs whoever holds the key.
 *
 * <p>The answer says which language it detected as well as the translation, which is how a message
 * is labelled with the language it was written in, and how a message already in the reader's
 * language is recognised and left alone.
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
    public record Translation(String text, String source) {
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

    private final HttpTransport transport;

    /** Chat repeats itself a lot — the same call to arms goes out several times a night. */
    private final Map<String, Translation> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Translation> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public TranslationService(HttpTransport transport) {
        this.transport = transport;
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                Translation result = request(payload, source, target);
                synchronized (cache) {
                    cache.put(key, result);
                }
                return result;
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, transport.executor());
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
