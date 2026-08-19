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
 * every player to register for one.
 */
public final class TranslationService {
    private static final String ENDPOINT = "https://translate.googleapis.com/translate_a/single";

    /** Longest text accepted; the endpoint is a GET, so the whole message rides in the query string. */
    public static final int MAX_LENGTH = 900;

    private static final int CACHE_SIZE = 300;

    /** Languages offered in the Translator's pickers, keyed by ISO code. */
    public static final Map<String, String> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("en", "English");
        LANGUAGES.put("pl", "Polski");
        LANGUAGES.put("de", "Deutsch");
        LANGUAGES.put("es", "Espanol");
        LANGUAGES.put("fr", "Francais");
        LANGUAGES.put("nl", "Nederlands");
        LANGUAGES.put("pt", "Portugues");
        LANGUAGES.put("it", "Italiano");
        LANGUAGES.put("sv", "Svenska");
        LANGUAGES.put("cs", "Cestina");
        LANGUAGES.put("uk", "Ukrainska");
        LANGUAGES.put("ru", "Russkiy");
        LANGUAGES.put("tr", "Turkce");
        LANGUAGES.put("fi", "Suomi");
        LANGUAGES.put("da", "Dansk");
        LANGUAGES.put("no", "Norsk");
    }

    public static String languageName(String code) {
        return LANGUAGES.getOrDefault(code, code == null ? "?" : code);
    }

    private final HttpTransport transport;

    /** Chat repeats itself a lot — the same call to arms goes out several times a night. */
    private final Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
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
     * @return the translated text; the future fails with {@link ApiException} if the service is
     * unreachable.
     */
    public CompletableFuture<String> translate(String text, String source, String target) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }

        String key = source + '>' + target + '>' + trimmed;
        synchronized (cache) {
            String hit = cache.get(key);
            if (hit != null) {
                return CompletableFuture.completedFuture(hit);
            }
        }

        String payload = trimmed;
        return CompletableFuture.supplyAsync(() -> {
            try {
                String result = request(payload, source, target);
                synchronized (cache) {
                    cache.put(key, result);
                }
                return result;
            } catch (ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, transport.executor());
    }

    private String request(String text, String source, String target) throws ApiException {
        String url = ENDPOINT + "?client=gtx&sl=" + encode(source) + "&tl=" + encode(target)
                + "&dt=t&q=" + encode(text);
        return parse(transport.get(url));
    }

    /**
     * The response is a nested array; the first element holds one entry per sentence, whose first
     * member is the translated text.
     */
    static String parse(String body) throws ApiException {
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
            return out.toString().trim();
        } catch (JsonSyntaxException | IllegalStateException | IndexOutOfBoundsException e) {
            throw new ApiException("Could not read the translation response", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
