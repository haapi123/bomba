package com.betterloka.map;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the markup Loka's map publishes into something worth putting on screen.
 *
 * <p>Its marker labels are little HTML cards — headings, {@code <small>} runs, line breaks — meant
 * for a browser. Every field pulled out of one goes through here, because a pattern that reaches
 * even slightly too far otherwise puts the tags themselves in front of the player: a territory once
 * showed its name as {@code <small>Owner: Grand Daselia</small><small><br/>Moor 56}.
 *
 * <p>Tags are removed before entities are decoded, never the other way round. An escaped
 * {@code &lt;b&gt;} is text somebody meant to be read, and decoding first would turn it into markup
 * and then delete it.
 */
public final class HtmlText {
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Minecraft's own colour codes, which Loka's battle records carry.
     *
     * <p>A defending region comes back as {@code §6The Rivi Shores}. The section sign is not HTML
     * and no tag stripper would touch it, but it is markup all the same and belongs off the screen
     * with the rest of it.
     */
    private static final Pattern COLOR_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    /** The handful Loka's cards actually use, plus the ones any escaper emits. */
    private static final Map<String, String> NAMED = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"",
            "apos", "'", "nbsp", " ", "ndash", "–", "mdash", "—");

    private HtmlText() {
    }

    /**
     * One field of Loka's markup as plain text.
     *
     * <p>Blank comes back as {@code null}, so a field that held nothing but tags reads as absent
     * rather than as an empty line the screen would still make room for.
     */
    public static String plain(String raw) {
        if (raw == null) {
            return null;
        }
        String text = LINE_BREAK.matcher(raw).replaceAll(" ");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = COLOR_CODE.matcher(text).replaceAll("");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return text.isEmpty() ? null : text;
    }

    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String body = matcher.group(1);
            String replacement = null;
            if (body.charAt(0) == '#') {
                try {
                    int code = body.charAt(1) == 'x' || body.charAt(1) == 'X'
                            ? Integer.parseInt(body.substring(2), 16)
                            : Integer.parseInt(body.substring(1));
                    if (Character.isValidCodePoint(code)) {
                        replacement = new String(Character.toChars(code));
                    }
                } catch (NumberFormatException ignored) {
                    replacement = null;
                }
            } else {
                replacement = NAMED.get(body.toLowerCase(java.util.Locale.ROOT));
            }
            // An entity nobody knows is left exactly as written rather than swallowed.
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
