package com.betterloka.translate;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rolling record of what players have said, with each message translated into the player's
 * language.
 *
 * <p>Only actual chat is kept. Servers push a great deal of other text through the same channel —
 * join notices, territory announcements, command output — and translating all of it buries the
 * conversation and wastes requests, so anything that does not parse as somebody speaking is dropped.
 */
public final class ChatLog {
    /** How many messages to keep. Enough to catch up on a fight call without holding a session. */
    private static final int CAPACITY = 60;

    /** Minecraft account names: 3-16 of letters, digits and underscore. */
    private static final String NAME = "[A-Za-z0-9_]{3,16}";

    /** {@code <Nick> message} — vanilla and most proxies. */
    private static final Pattern ANGLE_FORM = Pattern.compile("^<(" + NAME + ")>\\s*(.+)$", Pattern.DOTALL);

    /**
     * {@code [Tag] Rank Nick: message} — Loka and most plugin chats. The sender half may carry ranks,
     * town tags and separators, so the name is taken as the last name-shaped token before the colon.
     */
    private static final Pattern COLON_FORM = Pattern.compile("^(.{0,48}?)\\s*:\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern LAST_NAME = Pattern.compile("(" + NAME + ")\\s*$");

    /** One captured chat message. {@code translated} fills in once the request comes back. */
    public static final class Line {
        private final String sender;
        private final String message;
        private final long receivedAt;
        private volatile String translated;
        private volatile boolean failed;

        Line(String sender, String message) {
            this.sender = sender;
            this.message = message;
            this.receivedAt = System.currentTimeMillis();
        }

        public String sender() {
            return sender;
        }

        /** What they said, without the name or any rank decoration. */
        public String message() {
            return message;
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
            return translated != null && !translated.isBlank() && !translated.equalsIgnoreCase(message);
        }
    }

    private final TranslationService translations;
    private final BetterLokaConfig config;
    private final Deque<Line> lines = new ArrayDeque<>();

    public ChatLog(TranslationService translations, BetterLokaConfig config) {
        this.translations = translations;
        this.config = config;
    }

    /**
     * Records a chat message and, when incoming translation is on, starts translating it.
     * Anything that is not somebody speaking is ignored.
     */
    public void record(String raw) {
        Line line = parse(raw);
        if (line == null) {
            return;
        }
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

    /** @return the parsed message, or {@code null} if the text is not player chat. */
    static Line parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }

        Matcher angle = ANGLE_FORM.matcher(text);
        if (angle.matches()) {
            return lineOf(angle.group(1), angle.group(2));
        }

        Matcher colon = COLON_FORM.matcher(text);
        if (colon.matches()) {
            Matcher name = LAST_NAME.matcher(colon.group(1));
            if (name.find()) {
                return lineOf(name.group(1), colon.group(2));
            }
        }
        return null;
    }

    private static Line lineOf(String sender, String message) {
        String body = message.strip();
        return body.isEmpty() ? null : new Line(sender, body);
    }

    /** Starts (or restarts) the translation of one message. */
    public void translate(Line line) {
        translations.translate(line.message(), "auto", config.nativeLanguage())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        line.failed = true;
                        BetterLoka.LOGGER.debug("Could not translate a chat message", error);
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

    /** @return up to {@code limit} messages, newest first. */
    public List<Line> recent(int limit) {
        synchronized (lines) {
            List<Line> all = new ArrayList<>(lines);
            Collections.reverse(all);
            return List.copyOf(all.subList(0, Math.min(limit, all.size())));
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
