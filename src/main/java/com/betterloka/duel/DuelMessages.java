package com.betterloka.duel;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reading Loka's duel lines, in plain Java so it is tested outside the game.
 *
 * <p>The one that matters is {@code Match Ready! [Click to join]}, the line a found duel is announced
 * with. It has to be the whole line: Loka sends player chat as system messages too, so somebody typing
 * the same words shows up as {@code Name: Match Ready! [Click to join]} — and must not join anyone to
 * anything.
 */
public final class DuelMessages {
    private static final Pattern MATCH_READY =
            Pattern.compile("(?i)^match ready!?\\s*(\\[?\\s*click to join\\s*]?)?\\s*$");

    /** "vs Name", "against Name", "opponent: Name" — however a line might name the other player. */
    private static final Pattern OPPONENT = Pattern.compile(
            "(?i)(?:\\bvs\\.?|\\bversus|\\bagainst|\\bopponent\\s*:?|\\bfighting)\\s+([A-Za-z0-9_]{3,16})\\b");

    private DuelMessages() {
    }

    /**
     * The opponent, if the duel's line names them anywhere — in its text or in what shows on
     * hovering it. Loka's own line, as far as is known, does not; then this is {@code null} and
     * the opponent is only known once the duel starts.
     *
     * @param texts the line's text and its tooltips
     * @param self  the player's own name, never taken for the opponent
     */
    public static String opponentIn(Iterable<String> texts, String self) {
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            java.util.regex.Matcher matcher = OPPONENT.matcher(text);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (self == null || !name.equalsIgnoreCase(self)) {
                    return name;
                }
            }
        }
        return null;
    }

    public static boolean isMatchReady(String line) {
        return line != null && MATCH_READY.matcher(line.strip()).matches();
    }

    /**
     * The command to send for a found duel: the one the message's own button runs, when it is a
     * {@code /join}, else plain {@code join}. Never anything else, whatever the button says.
     *
     * @param clickCommand the command the line's click event runs, or {@code null}
     * @return the command without its slash
     */
    public static String joinCommand(String clickCommand) {
        if (clickCommand != null) {
            String command = clickCommand.strip();
            while (command.startsWith("/")) {
                command = command.substring(1);
            }
            String lower = command.toLowerCase(Locale.ROOT);
            if ((lower.equals("join") || lower.startsWith("join ")) && command.length() <= 64
                    && command.chars().noneMatch(c -> c < 32)) {
                return command;
            }
        }
        return "join";
    }
}
