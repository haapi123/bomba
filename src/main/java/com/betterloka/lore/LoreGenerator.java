package com.betterloka.lore;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes a lore in MiniMessage, ready for Loka's lore editor: a title in the scheme's gradient,
 * a few sentences about what the player described, dividers and a rarity line, each optional.
 *
 * <p>The sentences come from the motifs the description mentions — fire, ice, shadow, the sea,
 * kings, war and the rest, in English or Polish — so "a sword forged in dragon fire" reads of embers
 * and dragonfire. With the player's own text chosen instead, that text is the lore and only the
 * styling is the mod's. Plain Java, tested outside the game.
 */
public final class LoreGenerator {
    /** How wide a lore line may run, in characters: about what a tooltip shows comfortably. */
    static final int WRAP = 34;

    /** What kind of thing the lore is for; it only decides the title's noun. */
    public enum Kind {
        SWORD("Blade", "Ostrze", "sword", "miecz", "blade", "ostrz", "katana", "szabl"),
        AXE("Axe", "Topór", "axe", "topor", "siekier"),
        BOW("Bow", "Łuk", "bow", "luk", "kusz", "crossbow"),
        PICKAXE("Pick", "Kilof", "pickaxe", "kilof", "pick"),
        ARMOR("Aegis", "Pancerz", "armor", "armour", "zbroj", "chestplate", "napiersnik", "helm", "boots", "buty", "pancerz"),
        SHIELD("Bulwark", "Tarcza", "shield", "tarcz"),
        TRIDENT("Trident", "Trójząb", "trident", "trojzab"),
        MACE("Mace", "Buzdygan", "mace", "buzdygan", "mlot", "hammer"),
        STAFF("Staff", "Kostur", "staff", "wand", "rod", "kostur", "rozdzk", "laska"),
        RELIC("Relic", "Relikwia", "ring", "amulet", "pierscien", "naszyjnik", "talisman", "talizman", "relic",
                "artefakt", "charm", "crown", "koron", "tiara", "pendant", "wisior", "medal", "totem", "gem", "klejnot"),
        BOOK("Tome", "Księga", "book", "tome", "ksieg", "ksiazk");

        private final String en;
        private final String pl;
        private final String[] words;

        Kind(String en, String pl, String... words) {
            this.en = en;
            this.pl = pl;
            this.words = words;
        }

        public String noun(boolean polish) {
            return polish ? pl : en;
        }

        /** The kind a description names, or {@code null}. */
        public static Kind in(String description) {
            String text = LoreMotif.fold(description);
            for (Kind kind : values()) {
                for (String word : kind.words) {
                    if (LoreMotif.containsWord(text, word)) {
                        return kind;
                    }
                }
            }
            return null;
        }
    }

    /** The line under the text that says how rare the thing is, if any. */
    public enum Rarity {
        NONE(null, 0, 0),
        RARE("RARE", 0x55AAFF, 0x55AAFF),
        EPIC("EPIC", 0xAA55FF, 0xD27BFF),
        LEGENDARY("LEGENDARY", 0xFFAA00, 0xFFE066),
        MYTHIC("MYTHIC", 0xFF55FF, 0x55FFFF);

        private final String label;
        private final int from;
        private final int to;

        Rarity(String label, int from, int to) {
            this.label = label;
            this.from = from;
            this.to = to;
        }

        public String label() {
            return label;
        }
    }

    /** Everything a lore is written from. */
    public record Request(String description, String title, Kind kind, LoreScheme scheme, boolean polish,
                          int sentences, boolean ownText, boolean titleLine, boolean dividers, boolean italic,
                          Rarity rarity, long seed) {
    }

    private LoreGenerator() {
    }

    /** The lore, in MiniMessage, one line per line. */
    public static String write(Request request) {
        List<String> lines = new ArrayList<>();
        LoreScheme scheme = request.scheme();
        String description = request.description() == null ? "" : request.description().strip();
        List<LoreMotif> motifs = LoreMotif.in(description);
        LoreMotif main = !motifs.isEmpty() ? motifs.get(0)
                : scheme.motif() != null ? scheme.motif() : LoreMotif.LEGEND;
        Kind kind = Kind.in(description);
        if (kind == null) {
            kind = request.kind() != null ? request.kind() : Kind.RELIC;
        }

        if (request.titleLine()) {
            String title = request.title() != null && !request.title().isBlank() ? request.title().strip()
                    : title(kind, main, request.polish());
            lines.add(gradient(scheme, "<bold>" + escape(title) + "</bold>"));
        }
        if (request.dividers()) {
            lines.add(divider());
        }

        String body;
        if (request.ownText() && !description.isEmpty()) {
            body = description;
        } else {
            body = String.join(" ", sentences(motifs, main, request.polish(), Math.max(1, request.sentences()),
                    request.seed()));
        }
        for (String line : wrap(body, WRAP)) {
            lines.add("<" + LoreScheme.hex(scheme.text()) + ">" + escape(line));
        }

        if (request.rarity() != null && request.rarity() != Rarity.NONE) {
            if (request.dividers()) {
                lines.add(divider());
            }
            Rarity rarity = request.rarity();
            lines.add("<" + LoreScheme.hex(scheme.accent()) + ">« </" + LoreScheme.hex(scheme.accent()) + ">"
                    + "<gradient:" + LoreScheme.hex(rarity.from) + ":" + LoreScheme.hex(rarity.to) + "><bold>"
                    + rarity.label() + "</bold></gradient>"
                    + "<" + LoreScheme.hex(scheme.accent()) + "> »");
        }

        if (!request.italic()) {
            lines.replaceAll(line -> "<!i>" + line);
        }
        return String.join("\n", lines);
    }

    /** {@code Blade of Embers}, {@code Ostrze Żaru}. */
    static String title(Kind kind, LoreMotif motif, boolean polish) {
        return polish ? kind.noun(true) + " " + motif.noun(true) : kind.noun(false) + " of " + motif.noun(false);
    }

    /**
     * {@code count} sentences: most from the motif mentioned most, one from the next if another was
     * mentioned, and the rest from the legends every item can carry. No sentence twice.
     */
    static List<String> sentences(List<LoreMotif> motifs, LoreMotif main, boolean polish, int count, long seed) {
        Random random = new Random(seed);
        List<String> primary = new ArrayList<>(main.sentences(polish));
        List<String> secondary = new ArrayList<>(motifs.size() > 1 ? motifs.get(1).sentences(polish)
                : main == LoreMotif.LEGEND ? List.of() : LoreMotif.LEGEND.sentences(polish));
        java.util.Collections.shuffle(primary, random);
        java.util.Collections.shuffle(secondary, random);
        List<String> out = new ArrayList<>();
        int fromSecondary = secondary.isEmpty() ? 0 : count >= 3 ? 1 : 0;
        for (int i = 0; out.size() < count - fromSecondary && i < primary.size(); i++) {
            out.add(primary.get(i));
        }
        for (int i = 0; out.size() < count && i < secondary.size(); i++) {
            // Second, so the lore opens and closes on what it is mostly about.
            out.add(Math.min(1, out.size()), secondary.get(i));
        }
        return out;
    }

    /** Breaks text into lines of at most {@code width} characters, at spaces where it can. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                while (word.length() > width) {
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String gradient(LoreScheme scheme, String inner) {
        if (scheme.rainbow()) {
            return "<rainbow>" + inner + "</rainbow>";
        }
        return "<gradient:" + LoreScheme.hex(scheme.titleFrom()) + ":" + LoreScheme.hex(scheme.titleTo()) + ">"
                + inner + "</gradient>";
    }

    /** A thin rule: struck-through spaces, which the game draws as a line. */
    private static String divider() {
        return "<dark_gray><st>" + " ".repeat(30) + "</st></dark_gray>";
    }

    /** Text as MiniMessage reads it: a {@code <} of the player's own would otherwise start a tag. */
    static String escape(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }
}
