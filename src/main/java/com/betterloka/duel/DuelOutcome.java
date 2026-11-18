package com.betterloka.duel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who won, when the client never sees anybody die.
 *
 * <p>Loka ends a duel on the lethal blow: it does not let the player die, it sends them on. So the
 * death the mod would read the result from never comes, and the result is worked out from two other
 * things instead:
 *
 * <ul>
 *   <li><b>what the server says</b> at the end — a title, a subtitle, the action bar or a line of
 *       chat: <i>Victory</i>, <i>Knucior won the duel</i>, <i>+15 ELO</i>, and so on;</li>
 *   <li><b>the player's own ranked record</b> on Loka, before the duel and after it: a win or a loss
 *       more on one ladder says how it went, and on which ladder it was played.</li>
 * </ul>
 *
 * <p>Plain Java: text and numbers in, a result out.
 */
public final class DuelOutcome {
    private static final Pattern LEGACY_CODES = Pattern.compile("§.");
    private static final String NAME = "([A-Za-z0-9_]{3,16})";

    /** "Winner: Knucior", "Zwycięzca: Knucior". */
    private static final Pattern WINNER_IS = Pattern.compile(
            "(?iU)\\b(?:winner|zwyci[eę]zca|wygrywa)\\s*[:\\-–»|]?\\s*" + NAME);
    /** "Knucior won the duel", "Knucior has won", "Knucior wins!". */
    private static final Pattern NAME_WON = Pattern.compile(
            "(?iU)" + NAME + "\\s+(?:has\\s+)?(?:won|wins|wygra[lł]\\w*|wygrywa)\\b");
    /** "Knucior defeated Steve", "Knucior pokonał Steve". */
    private static final Pattern NAME_BEAT = Pattern.compile(
            "(?iU)" + NAME + "\\s+(?:has\\s+)?(?:defeated|beat|killed|slew|pokona[lł]\\w*|zabi[lł]\\w*)\\s+" + NAME);
    /** "Steve was slain by Knucior", the game's own way of saying somebody died. */
    private static final Pattern SLAIN_BY = Pattern.compile(
            "(?iU)" + NAME + "\\s+(?:was|has\\s+been)\\s+(?:slain|killed|defeated|shot|blown\\s+up|pummeled)\\b.*?\\bby\\s+" + NAME);
    /** "Steve died", "Steve lost the duel". */
    private static final Pattern NAME_LOST = Pattern.compile(
            "(?iU)" + NAME + "\\s+(?:has\\s+)?(?:died|lost|przegra[lł]\\w*)\\b");

    private static final Pattern YOU_WON = Pattern.compile(
            "(?iU)\\b(?:victory|victorious|you\\s+(?:have\\s+)?won|you\\s+win|you\\s+(?:have\\s+)?(?:defeated|killed|beat)"
                    + "|wygra[lł](?:e|a)?[sś]|zwyci[eę]stwo|wygrana)\\b");
    private static final Pattern YOU_LOST = Pattern.compile(
            "(?iU)\\b(?:defeat|you\\s+(?:have\\s+)?lost(?!\\s+connection)|you\\s+lose|you\\s+died"
                    + "|you\\s+(?:were|have\\s+been)\\s+(?:slain|killed|defeated)"
                    + "|przegra[lł](?:e|a)?[sś]|pora[zż]ka|przegrana)\\b");
    /** "+15 ELO", "-12 rating", "ELO: 1200 (+15)". */
    private static final Pattern RATING_AFTER = Pattern.compile(
            "(?iU)([+-])\\s?(\\d{1,3})\\s*(?:elo|rating|rp|mmr|points?|pts|pkt|punkt\\w*)\\b");
    private static final Pattern RATING_BEFORE = Pattern.compile(
            "(?iU)\\b(?:elo|rating|mmr)\\b[^+\\-\\d]{0,12}(?:\\d{1,5}\\s*\\(?\\s*)?([+-])\\s?(\\d{1,3})");

    private DuelOutcome() {
    }

    /** A ranked result read off the record, and the ladder it was on ({@code pot}, {@code barebones}). */
    public record Recorded(DuelStats.Result result, String ladder) {
    }

    /**
     * What one line from the server says about the result, or {@code null} if it says nothing about
     * it — or says both.
     *
     * @param self     the player's name, or {@code null}
     * @param opponent the opponent's name, or {@code null}
     */
    public static DuelStats.Result fromText(String text, String self, String opponent) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String line = LEGACY_CODES.matcher(text).replaceAll("").replaceAll("\\s+", " ").strip();
        Set<DuelStats.Result> said = new LinkedHashSet<>();

        // Named: the strongest, as it says who.
        for (Matcher m = WINNER_IS.matcher(line); m.find(); ) {
            add(said, won(m.group(1), self, opponent));
        }
        for (Matcher m = NAME_WON.matcher(line); m.find(); ) {
            add(said, won(m.group(1), self, opponent));
        }
        for (Matcher m = NAME_BEAT.matcher(line); m.find(); ) {
            add(said, won(m.group(1), self, opponent));
        }
        for (Matcher m = SLAIN_BY.matcher(line); m.find(); ) {
            add(said, won(m.group(2), self, opponent));
        }
        for (Matcher m = NAME_LOST.matcher(line); m.find(); ) {
            DuelStats.Result loser = won(m.group(1), self, opponent);
            add(said, loser == null ? null : loser == DuelStats.Result.WON ? DuelStats.Result.LOST : DuelStats.Result.WON);
        }
        if (said.size() == 1) {
            return said.iterator().next();
        }
        if (!said.isEmpty()) {
            return null;
        }

        // Addressed to the player.
        boolean won = YOU_WON.matcher(line).find();
        boolean lost = YOU_LOST.matcher(line).find();
        if (won != lost) {
            return won ? DuelStats.Result.WON : DuelStats.Result.LOST;
        }
        if (won) {
            return null;
        }

        // A rating change, when it is not somebody else's.
        if (opponent != null && line.toLowerCase(Locale.ROOT).contains(opponent.toLowerCase(Locale.ROOT))) {
            return null;
        }
        int sign = 0;
        for (Pattern pattern : new Pattern[]{RATING_AFTER, RATING_BEFORE}) {
            for (Matcher m = pattern.matcher(line); m.find(); ) {
                if (Integer.parseInt(m.group(2)) == 0) {
                    continue;
                }
                int here = "+".equals(m.group(1)) ? 1 : -1;
                if (sign != 0 && sign != here) {
                    return null;
                }
                sign = here;
            }
        }
        return sign > 0 ? DuelStats.Result.WON : sign < 0 ? DuelStats.Result.LOST : null;
    }

    /**
     * Whether a line of chat could be a player talking rather than the server: it starts with the
     * name of somebody on the server, as "Steve: gg" or "[Town] Steve » you lost" do.
     */
    public static boolean looksLikePlayerChat(String text, Predicate<String> isPlayer) {
        if (text == null) {
            return false;
        }
        String line = LEGACY_CODES.matcher(text).replaceAll("").strip();
        Matcher m = Pattern.compile("^(?:\\[[^\\]]{0,24}\\]\\s*)*(?:<)?" + NAME + "(?:>)?\\s*(?:[:»>|]|\\s-\\s)").matcher(line);
        return m.find() && isPlayer.test(m.group(1));
    }

    /**
     * The ranked record before the duel and after it, per ladder as {wins, losses}: one win more on
     * one ladder is a win there, one loss more a loss. Anything else — nothing yet, several games, a
     * new season — is no answer.
     */
    public static Recorded fromRecords(Map<String, int[]> before, Map<String, int[]> after) {
        if (before == null || after == null) {
            return null;
        }
        Set<String> ladders = new LinkedHashSet<>(before.keySet());
        ladders.addAll(after.keySet());
        Recorded found = null;
        for (String ladder : ladders) {
            int[] was = before.getOrDefault(ladder, new int[2]);
            int[] now = after.getOrDefault(ladder, new int[2]);
            int wins = now[0] - was[0];
            int losses = now[1] - was[1];
            if (wins == 0 && losses == 0) {
                continue;
            }
            if (found != null || wins < 0 || losses < 0 || wins + losses != 1) {
                return null;
            }
            found = new Recorded(wins == 1 ? DuelStats.Result.WON : DuelStats.Result.LOST, ladder);
        }
        return found;
    }

    private static DuelStats.Result won(String name, String self, String opponent) {
        if (name == null) {
            return null;
        }
        if (self != null && name.equalsIgnoreCase(self)) {
            return DuelStats.Result.WON;
        }
        if (opponent != null && name.equalsIgnoreCase(opponent)) {
            return DuelStats.Result.LOST;
        }
        return null;
    }

    private static void add(Set<DuelStats.Result> said, DuelStats.Result result) {
        if (result != null) {
            said.add(result);
        }
    }
}
