package com.betterloka.translate;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A rolling record of what players have said, with each message translated into the player's
 * language while the Translator is open.
 *
 * <p>Translating is lazy on purpose. Every chat line is a request to the translation service, and
 * Loka's chat runs all evening; translating it in the background would spend those requests on
 * conversations nobody reads. Lines are kept as they arrive, and asked for only when the screen that
 * shows them is open — then newest first, each once per language, with repeats served from the
 * service's own cache. A line with no letters in it — a number, a smiley — is never sent at all.
 *
 * <p>Only actual chat is kept. Servers push a great deal of other text through the same channel —
 * join notices, territory announcements, command output — and translating all of it buries the
 * conversation and wastes requests, so anything that does not parse as somebody speaking is dropped.
 *
 * <p>Town and alliance chat are kept too, and remembered as such. Loka writes them
 * {@code [Alliance] [Town] <icon> Nick: message}, which with a chat timestamp in front runs to fifty
 * characters before the message even starts, so the sender is found by scanning for the separator
 * rather than by assuming the decoration is short.
 */
public final class ChatLog {
    /** How many messages to keep. Enough to catch up on a fight call without holding a session. */
    private static final int CAPACITY = 60;

    /** Minecraft account names: 3-16 of letters, digits and underscore. */
    private static final String NAME = "[A-Za-z0-9_]{3,16}";

    /** {@code <Nick> message} — vanilla and most proxies. */
    private static final Pattern ANGLE_FORM = Pattern.compile("^<(" + NAME + ")>\\s*(.+)$", Pattern.DOTALL);

    /**
     * A colon or arrow with a space after it: the candidate boundaries between who is speaking and
     * what they said. Every one is tried in turn, so however much rank, alliance, town and timestamp
     * decoration Loka puts in front, the sender is still found.
     *
     * <p>The space matters — it is what keeps the {@code 21:56:36} of a chat timestamp from looking
     * like a sender boundary.
     */
    private static final Pattern SEPARATOR = Pattern.compile("([:»›→])\\s+");

    /** The sender is the last name-shaped token before the separator. */
    private static final Pattern LAST_NAME = Pattern.compile("(" + NAME + ")\\s*$");

    /**
     * A leading channel marker, where the server writes one. Colour is the reliable signal on Loka,
     * but a tag both confirms it and has to come off before the sender can be read.
     */
    private static final Pattern CHANNEL_TAG = Pattern.compile(
            "^\\[\\s*(TC|AC|T|A|TOWN|ALLIANCE|ALLY|NATION)\\s*\\]\\s*", Pattern.CASE_INSENSITIVE);

    /** One captured chat message. The translation fills in once the request comes back. */
    public static final class Line {
        private final String sender;
        private final String message;
        private final ChatChannel channel;
        private final long receivedAt;
        private volatile String translated;
        private volatile String translatedTo;
        private volatile String requestedFor;
        private volatile String source;
        private volatile boolean failed;

        Line(String sender, String message, ChatChannel channel) {
            this.sender = sender;
            this.message = message;
            this.channel = channel;
            this.receivedAt = System.currentTimeMillis();
        }

        public String sender() {
            return sender;
        }

        /** What they said, without the name or any rank decoration. */
        public String message() {
            return message;
        }

        /** Which conversation this came from — public chat, your town, or your alliance. */
        public ChatChannel channel() {
            return channel;
        }

        public long receivedAt() {
            return receivedAt;
        }

        /** The translation, or {@code null} while it is still in flight. */
        public String translated() {
            return translated;
        }

        /** The language the original was detected as, or {@code null} until it has been asked. */
        public String source() {
            return source;
        }

        public boolean failed() {
            return failed;
        }

        /** Whether a translation into {@code target} has been asked for and not yet come back. */
        public boolean pending(String target) {
            return target.equals(requestedFor) && !target.equals(translatedTo) && !failed;
        }

        /** Whether this line has nothing to translate: no letters, or already in {@code target}. */
        public boolean needsNoTranslation(String target) {
            if (!hasLetters(message)) {
                return true;
            }
            return target.equals(translatedTo) && (sameLanguage(source, target)
                    || translated == null || translated.isBlank() || translated.equalsIgnoreCase(message));
        }

        /** Whether there is a translation into {@code target} worth showing under the original. */
        public boolean hasTranslation(String target) {
            return target.equals(translatedTo) && !needsNoTranslation(target);
        }
    }

    private static boolean sameLanguage(String source, String target) {
        return source != null && new TranslationService.Translation("", source).sameLanguage(target);
    }

    /** Whether there is a word in it at all, which is what is worth translating. */
    static boolean hasLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private final TranslationService translations;
    private final BetterLokaConfig config;
    private final Deque<Line> lines = new ArrayDeque<>();

    /** Whether the Translator is open, which is the only time lines are translated. */
    private volatile boolean watching;

    public ChatLog(TranslationService translations, BetterLokaConfig config) {
        this.translations = translations;
        this.config = config;
    }

    /**
     * Records a chat message and, while the Translator is open, starts translating it.
     * Anything that is not somebody speaking is ignored.
     */
    public void record(ChatMessage message) {
        Line line = parse(message);
        if (line == null) {
            return;
        }
        synchronized (lines) {
            lines.addLast(line);
            while (lines.size() > CAPACITY) {
                lines.removeFirst();
            }
        }
        if (watching) {
            translate(line);
        }
    }

    /**
     * Opens or closes the tap: while the Translator is open, everything not yet in the reader's
     * language is translated, and every new line as it arrives.
     */
    public void setWatching(boolean watching) {
        this.watching = watching;
        if (watching) {
            translateAll();
        }
    }

    /** @return the parsed message, or {@code null} if the text is not player chat. */
    static Line parse(String raw) {
        return parse(ChatMessage.plain(raw == null ? "" : raw));
    }

    /** @return the parsed message, or {@code null} if the text is not player chat. */
    static Line parse(ChatMessage message) {
        if (message == null || message.length() == 0) {
            return null;
        }
        // Leading whitespace is dropped by shifting the whole message, so colour offsets stay aligned
        // with the text they belong to.
        ChatMessage body = message.substring(leadingSpace(message.text()));
        String text = body.text().stripTrailing();
        if (text.isEmpty()) {
            return null;
        }

        ChatChannel tagged = ChatChannel.PUBLIC;
        Matcher tag = CHANNEL_TAG.matcher(text);
        if (tag.find()) {
            tagged = channelOfTag(tag.group(1));
            body = body.substring(tag.end());
            text = body.text().stripTrailing();
            if (text.isEmpty()) {
                return null;
            }
        }

        Matcher angle = ANGLE_FORM.matcher(text);
        if (angle.matches()) {
            int start = text.length() - angle.group(2).length();
            return lineOf(angle.group(1), angle.group(2), channel(tagged, body, start));
        }

        // Colons first, then arrows: in public chat an arrow is far more likely to be part of what
        // was said, so it is only accepted when the colour says this is team chat.
        Matcher separator = SEPARATOR.matcher(text);
        Line arrowFallback = null;
        while (separator.find()) {
            Matcher name = LAST_NAME.matcher(text.substring(0, separator.start()));
            if (!name.find()) {
                continue;
            }
            String said = text.substring(separator.end());
            ChatChannel channel = channel(tagged, body, separator.end());
            if (":".equals(separator.group(1))) {
                return lineOf(name.group(1), said, channel);
            }
            if (arrowFallback == null && channel.isTeamChannel()) {
                arrowFallback = lineOf(name.group(1), said, channel);
            }
        }
        return arrowFallback;
    }

    /** The channel a message belongs to: what its body is coloured, or what its tag said. */
    private static ChatChannel channel(ChatChannel tagged, ChatMessage message, int bodyStart) {
        ChatChannel colored = message.channelOf(bodyStart, message.length());
        if (colored.isTeamChannel()) {
            return colored;
        }
        // Some servers colour only the decoration, so fall back to the line as a whole, then the tag.
        ChatChannel whole = message.channelOf(0, message.length());
        return whole.isTeamChannel() ? whole : tagged;
    }

    private static int leadingSpace(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static ChatChannel channelOfTag(String tag) {
        return switch (tag.toUpperCase(Locale.ROOT)) {
            case "T", "TC", "TOWN" -> ChatChannel.TOWN;
            case "A", "AC", "ALLIANCE", "ALLY", "NATION" -> ChatChannel.ALLIANCE;
            default -> ChatChannel.PUBLIC;
        };
    }

    private static Line lineOf(String sender, String message, ChatChannel channel) {
        String said = message.strip();
        return said.isEmpty() ? null : new Line(sender, said, channel);
    }

    /** Starts the translation of one message into the reader's language, unless it needs none. */
    public void translate(Line line) {
        String target = config.nativeLanguage();
        if (!hasLetters(line.message()) || target.equals(line.translatedTo) || line.pending(target)) {
            return;
        }
        line.requestedFor = target;
        line.failed = false;
        translations.translate(line.message(), "auto", target)
                .whenComplete((result, error) -> {
                    if (!target.equals(line.requestedFor)) {
                        // Asked again in another language meanwhile; that answer is the one wanted.
                        return;
                    }
                    if (error != null) {
                        line.failed = true;
                        BetterLoka.LOGGER.debug("Could not translate a chat message", error);
                    } else {
                        line.translated = result.text();
                        line.source = result.source();
                        line.translatedTo = target;
                    }
                });
    }

    /**
     * Translates everything captured that is not yet in the reader's language, newest first — on
     * opening the Translator, and when the language is changed.
     */
    public void translateAll() {
        for (Line line : recent(CAPACITY)) {
            translate(line);
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
