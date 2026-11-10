package com.betterloka.assistantserver;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.Set;

/** A web page's content as plain text with its structure kept: headings, paragraphs, lists, rows. */
final class HtmlText {
    private static final Set<String> BLOCK = Set.of("p", "div", "section", "article", "header", "footer", "main",
            "blockquote", "pre", "table", "thead", "tbody", "tr", "ul", "ol", "dl", "dt", "dd", "figure",
            "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "hr", "aside");
    private static final String DROP = "script, style, noscript, svg, iframe, form, button, nav, input, select, "
            + "template, .mw-editsection, .reference, .references, .navbox, .toc, #toc, .printfooter, "
            + ".bbCodeBlock-expandLink, .js-extraPhrases";

    private HtmlText() {
    }

    static String of(Element root) {
        Element copy = root.clone();
        copy.select(DROP).remove();
        StringBuilder out = new StringBuilder();
        walk(copy, out);
        return tidy(out.toString());
    }

    private static void walk(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            String value = text.getWholeText().replaceAll("[\\s\\u00a0]+", " ");
            if (value.isBlank()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n' && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            } else {
                out.append(value);
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.normalName();
        boolean block = BLOCK.contains(tag);
        if (block) {
            newline(out);
        }
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> out.append("#".repeat(tag.charAt(1) - '0')).append(' ');
            case "li" -> out.append("- ");
            case "td", "th" -> out.append(" | ");
            case "img" -> {
                String alt = element.attr("alt").strip();
                // A file name says nothing; a description might.
                if (!alt.isEmpty() && alt.length() < 120 && !alt.matches("(?i).*\\.(png|jpe?g|gif|webp)$")) {
                    out.append("[").append(alt).append("]");
                }
            }
            default -> {
            }
        }
        for (Node child : element.childNodes()) {
            walk(child, out);
        }
        if (block) {
            newline(out);
        }
    }

    private static void newline(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    /** Trims each line and keeps at most one empty line in a row. */
    static String tidy(String text) {
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String raw : text.split("\n")) {
            String line = raw.strip().replaceAll(" {2,}", " ");
            if (line.equals("-") || line.equals("|") || line.matches("#+")) {
                continue;
            }
            if (line.isEmpty()) {
                blank++;
                if (blank > 1) {
                    continue;
                }
            } else {
                blank = 0;
            }
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }
}
