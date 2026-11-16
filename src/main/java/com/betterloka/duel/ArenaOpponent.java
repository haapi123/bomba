package com.betterloka.duel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who the opponent is, once the player has arrived at the arena and waits for the fight.
 *
 * <p>Loka's "Match Ready!" line does not name them, but the arena does: its scoreboard shows the
 * two duelists, and they are the only other player there. In order of how sure each is:
 * <ol>
 *   <li>a player on this server whose name is on the sidebar — the arena's own scoreboard;</li>
 *   <li>a name the sidebar labels as the opponent ("Opponent: Name", "vs Name");</li>
 *   <li>the only other player nearby;</li>
 *   <li>the only other player on the server.</li>
 * </ol>
 * More than one candidate at a step is no answer from that step. Plain Java, tested on its own.
 */
public final class ArenaOpponent {
    /** Which of the four ways found them. */
    public enum Source {
        SIDEBAR_PLAYER, SIDEBAR_LABEL, NEARBY, SERVER
    }

    public record Found(String name, Source source) {
    }

    private static final Pattern LABELLED = Pattern.compile(
            "(?i)(?:\\bvs\\.?|\\bversus|\\bagainst|\\bopponent\\s*:?|\\benemy\\s*:?|\\bfighting)\\s*([A-Za-z0-9_]{3,16})\\b");
    /** Old-style colour codes some servers still write into scoreboard text. */
    private static final Pattern LEGACY_CODES = Pattern.compile("§.");

    private ArenaOpponent() {
    }

    /**
     * @param self    the player's own name
     * @param sidebar the sidebar's lines (and its title), as shown
     * @param nearby  names of the other players the game has in the world nearby
     * @param server  names of everybody on the server's player list
     * @return the opponent, or {@code null} while it cannot be told
     */
    public static Found find(String self, List<String> sidebar, List<String> nearby, List<String> server) {
        List<String> lines = new ArrayList<>();
        for (String line : sidebar) {
            if (line != null && !line.isBlank()) {
                lines.add(LEGACY_CODES.matcher(line).replaceAll(""));
            }
        }
        Set<String> present = new LinkedHashSet<>();
        present.addAll(others(self, nearby));
        present.addAll(others(self, server));

        Set<String> onSidebar = new LinkedHashSet<>();
        for (String name : present) {
            Pattern whole = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE);
            for (String line : lines) {
                if (whole.matcher(line).find()) {
                    onSidebar.add(name);
                    break;
                }
            }
        }
        if (onSidebar.size() == 1) {
            return new Found(onSidebar.iterator().next(), Source.SIDEBAR_PLAYER);
        }

        Set<String> labelled = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher matcher = LABELLED.matcher(line);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (self == null || !name.equalsIgnoreCase(self)) {
                    labelled.add(name);
                }
            }
        }
        if (labelled.size() == 1) {
            return new Found(labelled.iterator().next(), Source.SIDEBAR_LABEL);
        }

        List<String> close = others(self, nearby);
        if (close.size() == 1) {
            return new Found(close.get(0), Source.NEARBY);
        }
        List<String> everyone = others(self, server);
        if (everyone.size() == 1) {
            return new Found(everyone.get(0), Source.SERVER);
        }
        return null;
    }

    private static List<String> others(String self, List<String> names) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank() || (self != null && name.equalsIgnoreCase(self))) {
                continue;
            }
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(name);
            }
        }
        return out;
    }
}
