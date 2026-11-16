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

    private DuelMessages() {
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
