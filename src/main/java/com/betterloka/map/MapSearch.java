package com.betterloka.map;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds a territory or a town on one continent from what somebody typed.
 *
 * <p>People type what they read on Loka's map — {@code Forest 65} — but also {@code forest65},
 * {@code 65}, half a town's name, or the area without the number. All of those should land on
 * something sensible, so a query is matched in decreasing order of how sure the match is: the whole
 * label exactly, the number alone, a town's whole name, every word of the query starting a word of
 * the label, and finally the query appearing anywhere in it.
 *
 * <p>A number in the query is held to the number on the hex: {@code forest 6} goes to Forest 6
 * before Forest 65, and only reaches Forest 65 when there is no Forest 6.
 *
 * <p>Plain Java with no Minecraft in it, so it is tested outside the game. Built once per snapshot:
 * the words of every label are worked out here rather than on every keystroke.
 */
public final class MapSearch {
    public enum Kind { TERRITORY, TOWN }

    /**
     * What a query found.
     *
     * @param name        what to call it: the territory's label or the town's name
     * @param focus       the territory to centre on and select — the hex itself, or the town's seat —
     *                    or {@code null} for a town that holds no ground
     * @param territories everything to light up: the hex, or every hex the town holds
     * @param x           where to look, world X: the focus's middle, or the town's marker
     * @param z           the same, world Z
     */
    public record Hit(Kind kind, String name, MapTerritory focus, List<MapTerritory> territories,
                      double x, double z) {
    }

    private record Entry(Kind kind, String name, String compact, List<String> words, String number,
                         MapTerritory territory, MapTown town) {
    }

    private record Scored(Entry entry, int score) {
    }

    // How sure a match is. Only the order matters; the gaps leave room for the word score.
    private static final int EXACT_TERRITORY = 1000;
    private static final int EXACT_NUMBER = 950;
    private static final int EXACT_TOWN = 900;
    private static final int WORDS = 500;
    private static final int ANYWHERE = 200;

    private final List<Entry> entries;
    private final List<MapTerritory> territories;

    private MapSearch(List<Entry> entries, List<MapTerritory> territories) {
        this.entries = entries;
        this.territories = territories;
    }

    public static MapSearch of(List<MapTerritory> territories, List<MapTown> towns) {
        List<Entry> entries = new ArrayList<>();
        for (MapTerritory territory : territories) {
            String key = key(territory.label());
            entries.add(new Entry(Kind.TERRITORY, territory.label(), compact(key), words(key),
                    key(territory.number()), territory, null));
        }
        // Towns from their markers, and any town that holds ground without one, once each.
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (MapTown town : towns) {
            addTown(byName, town.name(), town);
        }
        for (MapTerritory territory : territories) {
            if (!territory.neutral()) {
                addTown(byName, territory.owner(), null);
            }
        }
        entries.addAll(byName.values());
        return new MapSearch(List.copyOf(entries), List.copyOf(territories));
    }

    private static void addTown(Map<String, Entry> byName, String name, MapTown town) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = key(name);
        if (key.isEmpty()) {
            return;
        }
        byName.putIfAbsent(name.toLowerCase(Locale.ROOT),
                new Entry(Kind.TOWN, name, compact(key), words(key), null, null, town));
    }

    /** The best match, or {@code null} when nothing matches at all. */
    public Hit find(String query) {
        List<Hit> hits = find(query, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** Up to {@code limit} matches, best first. */
    public List<Hit> find(String query, int limit) {
        String key = key(query == null ? "" : query);
        if (key.isEmpty() || limit <= 0) {
            return List.of();
        }
        String compactQuery = compact(key);
        List<String> queryWords = words(key);

        List<Scored> scored = new ArrayList<>();
        for (Entry entry : entries) {
            int score = score(entry, compactQuery, queryWords);
            if (score > 0) {
                scored.add(new Scored(entry, score));
            }
        }
        scored.sort(Comparator.comparingInt((Scored s) -> -s.score())
                .thenComparingInt(s -> s.entry().compact().length())
                .thenComparingLong(s -> numeric(s.entry().number()))
                .thenComparing(s -> s.entry().compact()));

        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < scored.size() && hits.size() < limit; i++) {
            hits.add(hit(scored.get(i).entry()));
        }
        return hits;
    }

    private static int score(Entry entry, String compactQuery, List<String> queryWords) {
        if (entry.compact().equals(compactQuery)) {
            return entry.kind() == Kind.TERRITORY ? EXACT_TERRITORY : EXACT_TOWN;
        }
        if (entry.kind() == Kind.TERRITORY && compactQuery.equals(entry.number())) {
            return EXACT_NUMBER;
        }
        int words = wordScore(entry.words(), queryWords);
        if (words > 0) {
            // Fewer words left over is the closer match: "forest" is Forest 12 before Birch Forest 90.
            int extra = Math.max(0, entry.words().size() - queryWords.size());
            return WORDS + words * 10 - Math.min(9, extra);
        }
        if (compactQuery.length() >= 2 && entry.compact().contains(compactQuery)) {
            return ANYWHERE;
        }
        return 0;
    }

    /**
     * How well every word of the query is met by some word of the entry: 3 for the same word, 2 for
     * the start of one, 1 for somewhere inside one. Zero when any query word goes unmet.
     */
    private static int wordScore(List<String> entryWords, List<String> queryWords) {
        int total = 0;
        for (String wanted : queryWords) {
            boolean digits = isDigits(wanted);
            int best = 0;
            for (String word : entryWords) {
                if (word.equals(wanted)) {
                    best = 3;
                    break;
                }
                if (word.startsWith(wanted)) {
                    best = Math.max(best, 2);
                } else if (!digits && wanted.length() >= 3 && word.contains(wanted)) {
                    best = Math.max(best, 1);
                }
            }
            if (best == 0) {
                return 0;
            }
            total += best;
        }
        return total;
    }

    private Hit hit(Entry entry) {
        if (entry.kind() == Kind.TERRITORY) {
            MapTerritory territory = entry.territory();
            return new Hit(Kind.TERRITORY, entry.name(), territory, List.of(territory),
                    territory.centerX(), territory.centerZ());
        }
        List<MapTerritory> held = new ArrayList<>();
        for (MapTerritory territory : territories) {
            if (!territory.neutral() && territory.owner().equalsIgnoreCase(entry.name())) {
                held.add(territory);
            }
        }
        MapTerritory seat = seatOf(held, entry.town());
        if (seat != null) {
            return new Hit(Kind.TOWN, entry.name(), seat, List.copyOf(held), seat.centerX(), seat.centerZ());
        }
        MapTown town = entry.town();
        return new Hit(Kind.TOWN, entry.name(), null, List.of(),
                town == null ? 0 : town.x(), town == null ? 0 : town.z());
    }

    /** The hex the town is built on: marked as a seat, or under its marker, or failing both its first. */
    private static MapTerritory seatOf(List<MapTerritory> held, MapTown town) {
        for (MapTerritory territory : held) {
            if (territory.seat()) {
                return territory;
            }
        }
        if (town != null) {
            for (MapTerritory territory : held) {
                if (territory.contains(town.x(), town.z())) {
                    return territory;
                }
            }
        }
        return held.isEmpty() ? null : held.get(0);
    }

    /**
     * Text reduced to what matters for matching: lower case, accents off, and anything that is not
     * a letter or a digit turned into a single space — so {@code #65}, {@code "Forest-65"} and
     * {@code  forest   65 } all read the same.
     */
    static String key(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        plain = plain.replace('ł', 'l').replace('Ł', 'L');
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String compact(String key) {
        return key.replace(" ", "");
    }

    private static List<String> words(String key) {
        return key.isEmpty() ? List.of() : List.of(key.split(" "));
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** A territory number for ordering, so Forest 9 comes before Forest 10; towns sort last. */
    private static long numeric(String number) {
        if (number == null || !isDigits(number) || number.length() > 18) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(number);
    }
}
