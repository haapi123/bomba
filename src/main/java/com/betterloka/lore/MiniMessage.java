package com.betterloka.lore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads MiniMessage — the markup Loka's lore editor (the Adventure "MiniMessage Viewer") takes — into
 * lines of styled spans, for previewing a lore as the game will show it.
 *
 * <p>Covers what lore uses: the sixteen named colours and {@code #rrggbb} (bare or as
 * {@code <color:…>}), the five decorations and their negations ({@code <!italic>},
 * {@code <bold:false>}), {@code <reset>}, {@code <gradient:…>} with any number of stops,
 * {@code <rainbow>}, and {@code <newline>}/{@code <br>}. Tags with no look of their own — click,
 * hover, insertion, font — keep their contents. Anything else is shown as typed, which is what
 * MiniMessage itself does with a tag it does not know.
 *
 * <p>Plain Java, so it is tested outside the game.
 */
public final class MiniMessage {
    private MiniMessage() {
    }

    /** One run of text in one style. {@code color} is {@code 0xRRGGBB}, or -1 for "inherit". */
    public record Span(String text, int color, Boolean bold, Boolean italic, Boolean underlined,
                       Boolean strikethrough, Boolean obfuscated) {
    }

    public static final Map<String, Integer> NAMED_COLORS = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("grey", 0xAAAAAA),
            Map.entry("dark_gray", 0x555555), Map.entry("dark_grey", 0x555555), Map.entry("blue", 0x5555FF),
            Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF), Map.entry("red", 0xFF5555),
            Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55), Map.entry("white", 0xFFFFFF));

    private static final Map<String, Integer> DECORATIONS = Map.ofEntries(
            Map.entry("bold", 0), Map.entry("b", 0),
            Map.entry("italic", 1), Map.entry("i", 1), Map.entry("em", 1),
            Map.entry("underlined", 2), Map.entry("u", 2),
            Map.entry("strikethrough", 3), Map.entry("st", 3),
            Map.entry("obfuscated", 4), Map.entry("obf", 4));

    /** Tags that change nothing a tooltip shows, so only their contents count. */
    private static final List<String> TRANSPARENT = List.of("click", "hover", "insert", "insertion", "font",
            "shadow", "transition", "sprite", "head");

    // --- the tree ---

    private abstract static sealed class Node permits Text, Tag {
    }

    private static final class Text extends Node {
        final StringBuilder text = new StringBuilder();
    }

    private static final class Tag extends Node {
        final String name;
        final List<String> args;
        final List<Node> children = new ArrayList<>();

        Tag(String name, List<String> args) {
            this.name = name;
            this.args = args;
        }
    }

    /** The lines of a MiniMessage string, each a list of spans. A blank line is an empty list. */
    public static List<List<Span>> parse(String input) {
        Tag root = new Tag("", List.of());
        List<Tag> open = new ArrayList<>();
        open.add(root);
        int i = 0;
        String source = input == null ? "" : input.replace("\r", "");
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length() && (source.charAt(i + 1) == '<' || source.charAt(i + 1) == '\\')) {
                text(open, String.valueOf(source.charAt(i + 1)));
                i += 2;
                continue;
            }
            if (c == '<') {
                int end = tagEnd(source, i);
                if (end > i) {
                    String inside = source.substring(i + 1, end);
                    if (handleTag(open, inside)) {
                        i = end + 1;
                        continue;
                    }
                }
            }
            text(open, String.valueOf(c));
            i++;
        }

        Renderer renderer = new Renderer();
        renderer.render(root, Style.BASE);
        return renderer.finish();
    }

    /** Where the tag starting at {@code start} closes, quotes respected; -1 if it never does. */
    private static int tagEnd(String source, int start) {
        char quote = 0;
        for (int i = start + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                return i;
            } else if (c == '<' || c == '\n') {
                return -1;
            }
        }
        return -1;
    }

    private static void text(List<Tag> open, String value) {
        Tag current = open.get(open.size() - 1);
        Node last = current.children.isEmpty() ? null : current.children.get(current.children.size() - 1);
        if (last instanceof Text existing) {
            existing.text.append(value);
        } else {
            Text fresh = new Text();
            fresh.text.append(value);
            current.children.add(fresh);
        }
    }

    /** @return false when the tag is not one MiniMessage knows, so it stays as text. */
    private static boolean handleTag(List<Tag> open, String inside) {
        if (inside.isEmpty()) {
            return false;
        }
        boolean closing = inside.startsWith("/");
        List<String> parts = split(closing ? inside.substring(1) : inside);
        String name = parts.isEmpty() ? "" : parts.get(0).toLowerCase(Locale.ROOT);
        List<String> args = parts.size() > 1 ? parts.subList(1, parts.size()) : List.of();
        if (closing) {
            if (name.isEmpty()) {
                // "</>" closes whatever is innermost.
                if (open.size() > 1) {
                    open.remove(open.size() - 1);
                }
                return true;
            }
            for (int k = open.size() - 1; k >= 1; k--) {
                if (sameTag(open.get(k).name, name)) {
                    while (open.size() > k) {
                        open.remove(open.size() - 1);
                    }
                    return true;
                }
            }
            return known(name);
        }
        if (name.equals("newline") || name.equals("br")) {
            text(open, "\n");
            return true;
        }
        if (name.equals("reset")) {
            while (open.size() > 1) {
                open.remove(open.size() - 1);
            }
            return true;
        }
        if (name.equals("key") || name.equals("lang") || name.equals("tr") || name.equals("translate")) {
            text(open, args.isEmpty() ? "" : args.get(0));
            return true;
        }
        if (!known(name)) {
            return false;
        }
        Tag tag = new Tag(name, List.copyOf(args));
        open.get(open.size() - 1).children.add(tag);
        open.add(tag);
        return true;
    }

    /** A colour closes by its name, by {@code color}, or by {@code c}; a decoration by any of its names. */
    private static boolean sameTag(String opened, String closing) {
        if (opened.equals(closing)) {
            return true;
        }
        String a = canonical(opened);
        String b = canonical(closing);
        return a.equals(b);
    }

    private static String canonical(String name) {
        String bare = name.startsWith("!") ? name.substring(1) : name;
        if (bare.equals("c") || bare.equals("colour") || bare.equals("color")) {
            return "color";
        }
        Integer decoration = DECORATIONS.get(bare);
        if (decoration != null) {
            return "decoration" + decoration;
        }
        return bare;
    }

    private static boolean known(String name) {
        String bare = name.startsWith("!") ? name.substring(1) : name;
        return NAMED_COLORS.containsKey(bare) || DECORATIONS.containsKey(bare) || isHex(bare)
                || bare.equals("color") || bare.equals("colour") || bare.equals("c")
                || bare.equals("gradient") || bare.equals("rainbow") || TRANSPARENT.contains(bare);
    }

    /** {@code gradient:#fff:'#000'} into its parts, quotes removed. */
    private static List<String> split(String inside) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    part.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ':') {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        parts.add(part.toString());
        return parts;
    }

    static boolean isHex(String value) {
        return value.length() == 7 && value.charAt(0) == '#' && value.substring(1).chars()
                .allMatch(ch -> Character.digit(ch, 16) >= 0);
    }

    /** A colour argument — a name or {@code #rrggbb} — or -1 when it is neither. */
    static int colorOf(String value) {
        if (value == null) {
            return -1;
        }
        String lower = value.toLowerCase(Locale.ROOT).trim();
        if (isHex(lower)) {
            return Integer.parseInt(lower.substring(1), 16);
        }
        Integer named = NAMED_COLORS.get(lower);
        return named == null ? -1 : named;
    }

    // --- rendering ---

    /** The style in force: a colour or a colour source, and the five decorations (null = unset). */
    private record Style(int color, Colorizer colorizer, Boolean bold, Boolean italic, Boolean underlined,
                         Boolean strikethrough, Boolean obfuscated) {
        static final Style BASE = new Style(-1, null, null, null, null, null, null);

        Style withColor(int rgb) {
            return new Style(rgb, null, bold, italic, underlined, strikethrough, obfuscated);
        }

        Style withColorizer(Colorizer source) {
            return new Style(-1, source, bold, italic, underlined, strikethrough, obfuscated);
        }

        Style withDecoration(int which, boolean on) {
            // Boxed on purpose: a ternary of boolean and Boolean unboxes the unset (null) ones.
            Boolean value = on;
            return new Style(color, colorizer, which == 0 ? value : bold, which == 1 ? value : italic,
                    which == 2 ? value : underlined, which == 3 ? value : strikethrough,
                    which == 4 ? value : obfuscated);
        }
    }

    /** Hands out a colour per character: a gradient or a rainbow across a run of text. */
    private static final class Colorizer {
        final int[] stops;
        final boolean rainbow;
        final double phase;
        final int length;
        int index;

        Colorizer(int[] stops, boolean rainbow, double phase, int length) {
            this.stops = stops;
            this.rainbow = rainbow;
            this.phase = phase;
            this.length = Math.max(1, length);
        }

        int next() {
            double t = length <= 1 ? 0 : (double) index / (length - 1);
            index++;
            if (rainbow) {
                double hue = (t * (length - 1) / length + phase) % 1.0;
                return java.awt.Color.HSBtoRGB((float) (hue < 0 ? hue + 1 : hue), 1f, 1f) & 0xFFFFFF;
            }
            if (stops.length == 1) {
                return stops[0];
            }
            double shifted = t + phase;
            shifted = shifted - Math.floor(shifted);
            if (phase == 0) {
                shifted = t;
            }
            double position = shifted * (stops.length - 1);
            int segment = Math.min(stops.length - 2, (int) Math.floor(position));
            double local = position - segment;
            return lerp(stops[segment], stops[segment + 1], local);
        }
    }

    static int lerp(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static final class Renderer {
        private final List<List<Span>> lines = new ArrayList<>();
        private List<Span> line = new ArrayList<>();

        void render(Node node, Style style) {
            if (node instanceof Text text) {
                for (int i = 0; i < text.text.length(); i++) {
                    char c = text.text.charAt(i);
                    if (c == '\n') {
                        lines.add(line);
                        line = new ArrayList<>();
                        continue;
                    }
                    int color = style.colorizer() != null ? style.colorizer().next() : style.color();
                    append(String.valueOf(c), color, style);
                }
                return;
            }
            Tag tag = (Tag) node;
            Style inner = apply(tag, style);
            for (Node child : tag.children) {
                render(child, inner);
            }
        }

        private Style apply(Tag tag, Style style) {
            String name = tag.name;
            if (name.isEmpty()) {
                return style;
            }
            boolean negated = name.startsWith("!");
            String bare = negated ? name.substring(1) : name;
            Integer decoration = DECORATIONS.get(bare);
            if (decoration != null) {
                boolean on = !negated && !(tag.args.size() > 0 && tag.args.get(0).equalsIgnoreCase("false"));
                return style.withDecoration(decoration, on);
            }
            if (NAMED_COLORS.containsKey(bare)) {
                return style.withColor(NAMED_COLORS.get(bare));
            }
            if (isHex(bare)) {
                return style.withColor(Integer.parseInt(bare.substring(1), 16));
            }
            if (bare.equals("color") || bare.equals("colour") || bare.equals("c")) {
                int color = tag.args.isEmpty() ? -1 : colorOf(tag.args.get(0));
                return color < 0 ? style : style.withColor(color);
            }
            if (bare.equals("gradient")) {
                List<Integer> stops = new ArrayList<>();
                double phase = 0;
                for (String arg : tag.args) {
                    int color = colorOf(arg);
                    if (color >= 0) {
                        stops.add(color);
                    } else {
                        try {
                            phase = Double.parseDouble(arg);
                        } catch (NumberFormatException ignored) {
                            // Not a colour and not a phase: skipped, as MiniMessage does.
                        }
                    }
                }
                if (stops.isEmpty()) {
                    stops.add(0xFFFFFF);
                    stops.add(0x000000);
                }
                int[] array = stops.stream().mapToInt(Integer::intValue).toArray();
                return style.withColorizer(new Colorizer(array, false, phase, visibleLength(tag)));
            }
            if (bare.equals("rainbow")) {
                double phase = 0;
                for (String arg : tag.args) {
                    try {
                        phase = Double.parseDouble(arg.replace("!", ""));
                    } catch (NumberFormatException ignored) {
                        // "!" alone reverses the rainbow; close enough to leave it as it is.
                    }
                }
                return style.withColorizer(new Colorizer(new int[0], true, phase, visibleLength(tag)));
            }
            return style;
        }

        private void append(String text, int color, Style style) {
            if (!line.isEmpty()) {
                Span last = line.get(line.size() - 1);
                if (last.color() == color && java.util.Objects.equals(last.bold(), style.bold())
                        && java.util.Objects.equals(last.italic(), style.italic())
                        && java.util.Objects.equals(last.underlined(), style.underlined())
                        && java.util.Objects.equals(last.strikethrough(), style.strikethrough())
                        && java.util.Objects.equals(last.obfuscated(), style.obfuscated())) {
                    line.set(line.size() - 1, new Span(last.text() + text, color, last.bold(), last.italic(),
                            last.underlined(), last.strikethrough(), last.obfuscated()));
                    return;
                }
            }
            line.add(new Span(text, color, style.bold(), style.italic(), style.underlined(), style.strikethrough(),
                    style.obfuscated()));
        }

        List<List<Span>> finish() {
            lines.add(line);
            return lines;
        }
    }

    /** How many characters a tag's text runs to, newlines aside: what a gradient is spread across. */
    private static int visibleLength(Tag tag) {
        int length = 0;
        for (Node child : tag.children) {
            if (child instanceof Text text) {
                for (int i = 0; i < text.text.length(); i++) {
                    if (text.text.charAt(i) != '\n') {
                        length++;
                    }
                }
            } else {
                length += visibleLength((Tag) child);
            }
        }
        return length;
    }

    /** The text without any markup, for measuring a line. */
    public static String plain(List<Span> line) {
        StringBuilder out = new StringBuilder();
        for (Span span : line) {
            out.append(span.text());
        }
        return out.toString();
    }
}
