package com.betterloka.api.model;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One item on sale in Loka's market.
 *
 * <p>The API hands the item over as Bukkit's {@code ItemStack.toString()}, so the interesting parts
 * — the custom name, the lore and the enchantments that make a sword worth thousands rather than a
 * hundred — have to be picked out of that string.
 */
public final class MarketListing {
    /** {@code "text":"..."} runs inside an embedded text component. */
    private static final Pattern TEXT_RUN = Pattern.compile("\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ENCHANT = Pattern.compile("([a-z_]+(?::[a-z_]+)?)=(\\d+)");

    private final String id;
    private final String ownerId;
    private final double price;
    private final int quantity;
    private final int stackSize;
    private final String type;
    private final String customName;
    private final List<String> lore;
    private final List<String> enchantments;

    private MarketListing(String id, String ownerId, double price, int quantity, int stackSize, String type,
                          String customName, List<String> lore, List<String> enchantments) {
        this.id = id;
        this.ownerId = ownerId;
        this.price = price;
        this.quantity = quantity;
        this.stackSize = stackSize;
        this.type = type;
        this.customName = customName;
        this.lore = lore;
        this.enchantments = enchantments;
    }

    public static MarketListing fromJson(JsonObject json) {
        String itemStack = Json.string(json, "itemStack");
        return new MarketListing(
                Json.string(json, "id"),
                Json.string(json, "ownerId"),
                Json.doubleValue(json, "price", 0),
                Json.integer(json, "quantity", 0),
                Json.integer(json, "stackSize", 1),
                Json.string(json, "type"),
                parseCustomName(itemStack),
                parseLore(itemStack),
                parseEnchantments(itemStack));
    }

    public String id() {
        return id;
    }

    /** The seller's identity ID; resolve it through {@code /players/search/findByIdentityId}. */
    public String ownerId() {
        return ownerId;
    }

    /** Price for the whole listing, in Loka's currency. */
    public double price() {
        return price;
    }

    /** How many of the item are still on offer. */
    public int quantity() {
        return quantity;
    }

    public int stackSize() {
        return stackSize;
    }

    public String type() {
        return type;
    }

    /** The item's custom name, or {@code null} for a plain stack. */
    public String customName() {
        return customName;
    }

    public List<String> lore() {
        return lore;
    }

    public List<String> enchantments() {
        return enchantments;
    }

    public double pricePerUnit() {
        return quantity <= 0 ? price : price / quantity;
    }

    /**
     * Whether this is a one-off piece of gear rather than stock.
     *
     * <p>Keyed on the custom name alone: on Loka every imbued item carries lore ("Imbued in
     * Asgard"), so lore does not distinguish anything, but a player-given name does.
     */
    public boolean isSpecial() {
        return customName != null;
    }

    /** A readable label: the custom name when it has one, otherwise a tidied material name. */
    public String displayName() {
        return customName != null ? customName : prettyType();
    }

    /** {@code DIAMOND_SWORD} becomes {@code Diamond Sword}. */
    public String prettyType() {
        if (type == null || type.isEmpty()) {
            return "?";
        }
        StringBuilder out = new StringBuilder(type.length());
        for (String word : type.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }

    private static String parseCustomName(String itemStack) {
        String section = section(itemStack, "display-name=");
        if (section == null) {
            return null;
        }
        String name = joinTextRuns(section);
        return name.isBlank() ? null : name;
    }

    /** Lore lines, with Loka's decorative rules and blank spacers dropped. */
    private static List<String> parseLore(String itemStack) {
        String section = section(itemStack, "lore=");
        if (section == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        // Each lore entry is its own component; splitting on "}, {" keeps them apart.
        for (String part : section.split("\\}, \\{")) {
            String line = joinTextRuns(part).strip();
            if (line.isEmpty() || line.chars().allMatch(c -> c == '-' || c == '=' || c == '_')) {
                continue;
            }
            lines.add(line);
        }
        return List.copyOf(lines);
    }

    /** {@code enchants={minecraft:sharpness=5, minecraft:unbreaking=3}} to {@code Sharpness 5}. */
    private static List<String> parseEnchantments(String itemStack) {
        String section = section(itemStack, "enchants=");
        if (section == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher matcher = ENCHANT.matcher(section);
        while (matcher.find()) {
            String name = matcher.group(1);
            int colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
            StringBuilder pretty = new StringBuilder();
            for (String word : name.split("_")) {
                if (word.isEmpty()) {
                    continue;
                }
                if (!pretty.isEmpty()) {
                    pretty.append(' ');
                }
                pretty.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
            out.add(pretty + " " + matcher.group(2));
        }
        return List.copyOf(out);
    }

    /**
     * The balanced {@code {...}} or {@code [...]} that follows {@code key} in the item string.
     * Bracket counting rather than a regex, because these values nest and contain brackets inside
     * their own string literals.
     */
    private static String section(String itemStack, String key) {
        if (itemStack == null) {
            return null;
        }
        int at = itemStack.indexOf(key);
        if (at < 0) {
            return null;
        }
        int start = at + key.length();
        if (start >= itemStack.length()) {
            return null;
        }
        char open = itemStack.charAt(start);
        char close = open == '{' ? '}' : open == '[' ? ']' : 0;
        if (close == 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < itemStack.length(); i++) {
            char c = itemStack.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return itemStack.substring(start, i + 1);
            }
        }
        return itemStack.substring(start);
    }

    /** Loka renders gradient names one character per component, so the runs have to be rejoined. */
    private static String joinTextRuns(String component) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = TEXT_RUN.matcher(component);
        while (matcher.find()) {
            out.append(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return out.toString().strip();
    }
}
