package com.betterloka.translate;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A rolling record of recent server chat, with each line's translation into the player's language.
 *
 * <p>Translation is fired once when a line arrives rather than when the Translator screen opens, so
 * opening it shows a conversation already in the player's own language instead of a loading list.
 */
public final class ChatLog {
    /** How many lines to keep. Enough to catch up on a fight call without holding a session's worth. */
    private static final int CAPACITY = 60;

    /** One captured chat line. {@code translated} fills in once the request comes back. */
    public static final class Line {
        private final String original;
        private final long receivedAt;
        private volatile String translated;
        private volatile boolean failed;

        Line(String original) {
            this.original = original;
            this.receivedAt = System.currentTimeMillis();
        }

        public String original() {
            return original;
        }

        public long receivedAt() {
            return receivedAt;
        }

        /** The translation, or {@code null} while it is still in flight. */
        public String translated() {
            return translated;
        }

        public boolean failed() {
            return failed;
        }

        /** Whether the translation says anything the original did not — i.e. worth showing. */
        public boolean hasUsefulTranslation() {
            return translated != null && !translated.isBlank() && !translated.equalsIgnoreCase(original);
        }
    }

    private final TranslationService translations;
    private final BetterLokaConfig config;
    private final Deque<Line> lines = new ArrayDeque<>();

    public ChatLog(TranslationService translations, BetterLokaConfig config) {
        this.translations = translations;
        this.config = config;
    }

    /** Records a chat line and, when incoming translation is on, starts translating it. */
    public void record(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        Line line = new Line(message.strip());
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > CAPACITY) {
                lines.removeFirst();
            }
        }
        if (config.translateIncoming()) {
            translate(line);
        }
    }

    /** Starts (or restarts) the translation of one line. */
    public void translate(Line line) {
        translations.translate(line.original(), "auto", config.nativeLanguage())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        line.failed = true;
                        BetterLoka.LOGGER.debug("Could not translate a chat line", error);
                    } else {
                        line.translated = result;
                        line.failed = false;
                    }
                });
    }

    /** Translates everything captured so far — used when the player turns translation on. */
    public void translateAll() {
        for (Line line : recent(CAPACITY)) {
            if (line.translated() == null) {
                translate(line);
            }
        }
    }

    /** @return up to {@code limit} lines, newest last. */
    public List<Line> recent(int limit) {
        synchronized (lines) {
            List<Line> all = new ArrayList<>(lines);
            int from = Math.max(0, all.size() - limit);
            return List.copyOf(all.subList(from, all.size()));
        }
    }

    public int size() {
        synchronized (lines) {
            return lines.size();
        }
    }

    public void clear() {
        synchronized (lines) {
            lines.clear();
        }
    }
}
