package com.betterloka.duel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the fight has begun, read off what the server shows while the duelists wait: a title or
 * action bar that says go ("FIGHT!", "GO!"), a countdown that reaches one (then a second later), or
 * a chat line saying the match has started.
 *
 * <p>Nothing here is Loka's exact wording, which is not known: it is what arenas generally show. The
 * duel's own signs — a hit, a swing, a teleport onto the start — are watched by {@link DuelTracker}
 * beside this. Plain Java, tested on its own.
 */
public final class FightStart {
    /** A title that is only a go word. Not "duel": arenas title the arrival with it. */
    private static final Pattern GO = Pattern.compile("(?i)^\\W*(fight|go|begin|start|walcz(cie|my)?|let'?s\\s+go)\\W*$");
    /** A title that is only a number, as countdowns are. */
    private static final Pattern BARE_COUNT = Pattern.compile("^\\W*(\\d{1,2})\\W*$");
    /** "Starting in 3", "begins in 1 second". */
    private static final Pattern COUNT_IN = Pattern.compile(
            "(?i)\\b(?:start(?:s|ing)?|begin(?:s|ning)?|fight)\\s+in\\s+(\\d{1,2})\\b");
    /** "The match has started", "Duel begins!", "Fight!". */
    private static final Pattern CHAT_GO = Pattern.compile(
            "(?i)(?:\\b(?:match|duel|fight|round)\\s+(?:has\\s+)?(?:started|begun|begins|start)\\b|^\\W*(?:fight|go)\\s*!+\\W*$)");

    private static final Pattern LEGACY_CODES = Pattern.compile("§.");

    private boolean started;
    private long startAt = -1;

    /** A title, subtitle or action bar. @return whether the fight has started */
    public boolean title(String text, long now) {
        if (text == null) {
            return started;
        }
        String line = LEGACY_CODES.matcher(text).replaceAll("").strip();
        if (GO.matcher(line).matches()) {
            started = true;
            return true;
        }
        count(BARE_COUNT.matcher(line), now);
        count(COUNT_IN.matcher(line), now);
        return poll(now);
    }

    /** A line of chat from the server. @return whether the fight has started */
    public boolean chat(String text, long now) {
        if (text == null) {
            return started;
        }
        String line = LEGACY_CODES.matcher(text).replaceAll("").strip();
        if (line.length() <= 80 && CHAT_GO.matcher(line).find()) {
            started = true;
            return true;
        }
        count(COUNT_IN.matcher(line), now);
        return poll(now);
    }

    /** A countdown at one starts the fight a second later; at zero, now. */
    private void count(Matcher matcher, long now) {
        if (!matcher.find()) {
            return;
        }
        int left = Integer.parseInt(matcher.group(1));
        if (left <= 1) {
            long at = now + left * 1000L;
            startAt = startAt < 0 ? at : Math.min(startAt, at);
        }
    }

    public boolean poll(long now) {
        return started || (startAt >= 0 && now >= startAt);
    }
}
