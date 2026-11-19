package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@code POST /v1/translate}: a few lines of chat in, their translations out, for the mod's
 * Translator.
 *
 * <p>Chat repeats itself — the same call to arms goes out several times a night, and every player
 * with the Translator open asks for the same lines — so translations are kept here, and only lines
 * nobody has asked for yet reach the model. What reaches it counts against the player's daily
 * allowance and the server's budget, the same budget Loka Assistant spends from. The lines themselves
 * are not written anywhere: they are other players' chat.
 */
final class TranslationDesk {
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Za-z]{2,4})?");
    private static final int KEEP = 5000;

    private final ServerConfig config;
    private final Translator translator;
    private final Ledger ledger;
    private final Semaphore working;
    private final Map<String, Translator.Result> kept = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Translator.Result> eldest) {
            return size() > KEEP;
        }
    };

    /** @param translator {@code null} without an API key: every request then says so */
    TranslationDesk(ServerConfig config, Translator translator, Ledger ledger) {
        this.config = config;
        this.translator = translator;
        this.ledger = ledger;
        this.working = new Semaphore(Math.max(1, config.concurrentTranslations), true);
    }

    boolean enabled() {
        return translator != null;
    }

    /**
     * Body: {@code {"target": "pl", "items": [{"text": "...", "source": "auto"}, ...]}}. Answer:
     * {@code {"items": [{"text": "...", "source": "en"}, ...], "left": 1987}} in the same order.
     */
    AssistantService.Response translate(Sessions.Session session, JsonObject body) {
        if (translator == null) {
            return AssistantService.error(503, "no_key", "The server cannot translate yet.");
        }
        String target = text(body, "target");
        if (target == null || !LANGUAGE.matcher(target).matches()) {
            return AssistantService.error(400, "bad_target", "Say which language to translate into.");
        }
        JsonElement listed = body.get("items");
        if (listed == null || !listed.isJsonArray() || listed.getAsJsonArray().isEmpty()) {
            return AssistantService.error(400, "empty", "Nothing to translate.");
        }
        JsonArray array = listed.getAsJsonArray();
        if (array.size() > config.maxTranslateBatch) {
            return AssistantService.error(400, "too_many", "At most " + config.maxTranslateBatch + " lines at a time.");
        }
        List<Translator.Item> items = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject item = element.isJsonObject() ? element.getAsJsonObject() : null;
            String line = item == null ? null : text(item, "text");
            if (line == null || line.isBlank()) {
                return AssistantService.error(400, "empty", "A line was empty.");
            }
            if (line.length() > config.maxTranslateChars) {
                return AssistantService.error(400, "too_long", "At most " + config.maxTranslateChars + " characters a line.");
            }
            String source = text(item, "source");
            if (source == null || !(source.equals("auto") || LANGUAGE.matcher(source).matches())) {
                source = "auto";
            }
            items.add(new Translator.Item(line.strip(), source));
        }

        // What is already known costs nothing.
        Translator.Result[] results = new Translator.Result[items.size()];
        List<Integer> missing = new ArrayList<>();
        synchronized (kept) {
            for (int i = 0; i < items.size(); i++) {
                results[i] = kept.get(key(items.get(i), target));
                if (results[i] == null) {
                    missing.add(i);
                }
            }
        }
        boolean admin = config.isAdmin(session.uuid(), session.name());
        if (!missing.isEmpty()) {
            switch (ledger.canTranslate(session.uuid(), admin, missing.size())) {
                case PLAYER_LIMIT:
                    return AssistantService.error(429, "player_limit", "No translations left today.");
                case BUDGET_DAY:
                    return AssistantService.error(503, "budget_day", "Translations are resting until tomorrow.");
                case BUDGET_MONTH:
                    return AssistantService.error(503, "budget_month", "Translations are resting until next month.");
                default:
                    break;
            }
            List<Translator.Item> asked = new ArrayList<>();
            for (int index : missing) {
                asked.add(items.get(index));
            }
            Translator.Batch batch;
            try {
                if (!working.tryAcquire(20, TimeUnit.SECONDS)) {
                    return AssistantService.error(503, "busy", "Many lines to translate; try again in a moment.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AssistantService.error(503, "busy", "Busy, try again.");
            }
            try {
                batch = translator.translate(asked, target, AssistantService.userId(session.uuid()));
            } catch (Answerer.Failure e) {
                Log.warn("Could not translate for " + session.name() + ": " + e.getMessage());
                return AssistantService.error(503, e.busy() ? "busy" : "upstream", "Could not translate right now.");
            } finally {
                working.release();
            }
            int done = 0;
            synchronized (kept) {
                for (int i = 0; i < missing.size(); i++) {
                    Translator.Result result = i < batch.results().size() ? batch.results().get(i) : null;
                    if (result != null && result.text() != null && !result.text().isBlank()) {
                        results[missing.get(i)] = result;
                        kept.put(key(items.get(missing.get(i)), target), result);
                        done++;
                    }
                }
            }
            ledger.recordTranslations(session.uuid(), done, batch.costUsd());
        }

        JsonObject out = AssistantService.ok();
        JsonArray translated = new JsonArray();
        for (Translator.Result result : results) {
            JsonObject item = new JsonObject();
            if (result != null) {
                item.addProperty("text", result.text());
                if (result.source() != null) {
                    item.addProperty("source", result.source());
                }
            }
            translated.add(item);
        }
        out.add("items", translated);
        out.addProperty("left", ledger.translationsLeft(session.uuid(), admin));
        return new AssistantService.Response(200, out);
    }

    private static String key(Translator.Item item, String target) {
        return target + '\u0000' + item.source() + '\u0000' + item.text();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
