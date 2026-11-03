package com.betterloka.api.model;

import com.betterloka.market.MarketCategories;
import com.betterloka.market.MarketCategory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One offer in Loka's market.
 *
 * <p>An offer sells {@link #quantity} units, each a stack of {@link #stackSize} items, at
 * {@link #price} shards a unit — that is how Loka's API documentation defines the fields. A stack of
 * sixty-four gunpowder at 324 is about five shards a powder; a sword at 15,000 is 15,000.
 *
 * <p>The item itself comes as Bukkit's {@code ItemStack.toString()}, so the interesting parts — the
 * custom name, the lore and the enchantments that make a sword worth thousands rather than a hundred
 * — are picked out of that string.
 */
public final class MarketListing {
    private static final Pattern ENCHANT = Pattern.compile("([a-z_]+(?::[a-z_]+)?)=(\\d+)");
    private static final Pattern MATERIAL = Pattern.compile("^ItemStack\\{([A-Z0-9_]+) x (\\d+)");
    private static final Pattern DAMAGE = Pattern.compile("[{,]\\s*Damage=(\\d+)");
    private static final Pattern POTION = Pattern.compile("potion-type=(?:minecraft:)?([a-z0-9_]+)");
    private static final Pattern ID = Pattern.compile("[A-Z0-9_]+");

    private final String id;
    private final String ownerId;
    private final double price;
    private final int quantity;
    private final int stackSize;
    private final String type;
    private final String material;
    private final String customName;
    private final String customNameJson;
    private final List<String> lore;
    private final List<String> loreJson;
    private final List<String> enchantments;
    private final int damage;
    private final String potionType;
    private final MarketCategory category;

    private MarketListing(String id, String ownerId, double price, int quantity, int stackSize, String type,
                          String itemStack) {
        this.id = id;
        this.ownerId = ownerId;
        this.price = price;
        this.quantity = quantity;
        this.stackSize = Math.max(1, stackSize);
        this.type = type;
        this.material = parseMaterial(itemStack);
        this.customNameJson = parseCustomNameJson(itemStack);
        this.customName = customNameJson == null ? null : blankToNull(plainText(parse(customNameJson)));
        this.loreJson = parseLoreJson(itemStack);
        this.lore = plainLore(loreJson);
        this.enchantments = parseEnchantments(itemStack);
        this.damage = parseInt(DAMAGE, itemStack);
        this.potionType = parseGroup(POTION, itemStack);
        this.category = MarketCategories.classify(type, material);
    }

    public static MarketListing fromJson(JsonObject json) {
        return new MarketListing(
                Json.string(json, "id"),
                Json.string(json, "ownerId"),
                Json.doubleValue(json, "price", 0),
                Json.integer(json, "quantity", 0),
                Json.integer(json, "stackSize", 1),
                Json.string(json, "type"),
                Json.string(json, "itemStack"));
    }

    public String id() {
        return id;
    }

    /** The seller's identity ID; resolve it through {@code /players/search/findByIdentityId}. */
    public String ownerId() {
        return ownerId;
    }

    /** What one unit costs — one purchase, a stack of {@link #stackSize} items — in shards. */
    public double price() {
        return price;
    }

    /** How many units are still on offer. */
    public int quantity() {
        return quantity;
    }

    /** How many items one unit is: 1 for a sword, 64 for a stack of gunpowder. */
    public int stackSize() {
        return stackSize;
    }

    /** What one item works out at, so a stack and a single can be compared. */
    public double itemPrice() {
        return price / stackSize;
    }

    /** Everything on offer, at the asking price. */
    public double stockValue() {
        return price * Math.max(0, quantity);
    }

    /** How many items there are in all. */
    public long itemCount() {
        return (long) Math.max(0, quantity) * stackSize;
    }

    /** The API's item type: {@code DIAMOND_SWORD}, {@code RECALL_STONE}, or free text such as {@code §3Xp Bottles}. */
    public String type() {
        return type;
    }

    /** The game material underneath, {@code EMERALD} under a Recall Stone, or {@code null} if unreadable. */
    public String material() {
        return material;
    }

    /** The game item id to draw it with — the material where known, else the type — lower case. */
    public String itemId() {
        String name = material != null ? material : type;
        return name == null || !ID.matcher(name).matches() ? null : name.toLowerCase(Locale.ROOT);
    }

    /** The item's custom name as plain text, or {@code null} for a plain stack. */
    public String customName() {
        return customName;
    }

    /**
     * The custom name as the game's text-component JSON, colours and all, or {@code null}.
     *
     * <p>Loka writes gradient names one character per component, so this is what draws them as the
     * game does.
     */
    public String customNameJson() {
        return customNameJson;
    }

    /** Lore as plain text, with Loka's decorative rules and blank spacers dropped. */
    public List<String> lore() {
        return lore;
    }

    /** Lore as the game's text-component JSON, a line each, rules and spacers kept. */
    public List<String> loreJson() {
        return loreJson;
    }

    /** {@code Sharpness 5}, {@code Mending 1}: the item's enchantments, or a book's stored ones. */
    public List<String> enchantments() {
        return enchantments;
    }

    /** How much wear the item has taken, 0 for none. */
    public int damage() {
        return damage;
    }

    /** {@code strong_harming} on a tipped arrow or potion, or {@code null}. */
    public String potionType() {
        return potionType;
    }

    /** The market stall it is sold from. */
    public MarketCategory category() {
        return category;
    }

    /** Whether it is one of Loka's own items, such as a Recall Stone. */
    public boolean isLokaItem() {
        return MarketCategories.isLokaItem(type, material);
    }

    /**
     * When the offer went up.
     *
     * <p>Loka publishes no such field, but its ids are MongoDB ObjectIds and those open with the
     * document's creation time, so the date is recoverable from the id the listing already carries.
     *
     * @return when it was listed, or {@code null} if the id is not an ObjectId
     */
    public Instant listedAt() {
        return ObjectIds.timestamp(id);
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

    /** A readable label: the custom name when it has one, otherwise a tidied type name. */
    public String displayName() {
        return customName != null ? customName : prettyType();
    }

    /** {@code DIAMOND_SWORD} becomes {@code Diamond Sword}; free text loses its colour codes. */
    public String prettyType() {
        return pretty(type);
    }

    /** {@code DIAMOND_SWORD} or {@code minecraft:diamond_sword} becomes {@code Diamond Sword}. */
    public static String pretty(String name) {
        if (name == null || name.isEmpty()) {
            return "?";
        }
        String plain = name.replaceAll("§.", "").strip();
        int colon = plain.indexOf(':');
        if (colon >= 0 && plain.substring(0, colon).matches("[a-z0-9_.-]+")) {
            plain = plain.substring(colon + 1);
        }
        if (!plain.matches("[A-Za-z0-9_]+")) {
            return plain;
        }
        StringBuilder out = new StringBuilder(plain.length());
        for (String word : plain.toLowerCase(Locale.ROOT).split("_")) {
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

    /** Lower-case text a search can look through: names, type and enchantments. */
    public String searchText() {
        StringBuilder text = new StringBuilder(prettyType());
        if (customName != null) {
            text.append(' ').append(customName);
        }
        if (material != null) {
            text.append(' ').append(pretty(material));
        }
        for (String enchantment : enchantments) {
            text.append(' ').append(enchantment);
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String parseMaterial(String itemStack) {
        if (itemStack == null) {
            return null;
        }
        Matcher matcher = MATERIAL.matcher(itemStack);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The display name's JSON, whether Bukkit wrote it as a component or as a bare string. */
    private static String parseCustomNameJson(String itemStack) {
        String value = value(itemStack, "display-name=");
        if (value == null) {
            return null;
        }
        JsonElement parsed = parse(value);
        if (parsed == null || plainText(parsed).isBlank()) {
            return null;
        }
        return parsed.toString();
    }

    private static List<String> parseLoreJson(String itemStack) {
        String value = value(itemStack, "lore=");
        JsonElement parsed = value == null ? null : parse(value);
        if (parsed == null || !parsed.isJsonArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement line : parsed.getAsJsonArray()) {
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    private static List<String> plainLore(List<String> json) {
        List<String> lines = new ArrayList<>();
        for (String line : json) {
            String text = plainText(parse(line)).strip();
            if (text.isEmpty() || text.chars().allMatch(c -> c == '-' || c == '=' || c == '_')) {
                continue;
            }
            lines.add(text);
        }
        return List.copyOf(lines);
    }

    /** {@code enchants={minecraft:sharpness=5, minecraft:unbreaking=3}} to {@code Sharpness 5}. */
    private static List<String> parseEnchantments(String itemStack) {
        String section = value(itemStack, "enchants=");
        if (section == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher matcher = ENCHANT.matcher(section);
        while (matcher.find()) {
            out.add(pretty(matcher.group(1)) + " " + matcher.group(2));
        }
        return List.copyOf(out);
    }

    /** The text a component shows, its {@code extra} runs included; a bare string is its own text. */
    static String plainText(JsonElement component) {
        if (component == null || component.isJsonNull()) {
            return "";
        }
        if (component.isJsonPrimitive()) {
            return component.getAsString().replaceAll("§.", "");
        }
        StringBuilder out = new StringBuilder();
        if (component.isJsonArray()) {
            for (JsonElement part : component.getAsJsonArray()) {
                out.append(plainText(part));
            }
            return out.toString();
        }
        JsonObject object = component.getAsJsonObject();
        JsonElement text = object.get("text");
        if (text != null && text.isJsonPrimitive()) {
            out.append(text.getAsString().replaceAll("§.", ""));
        }
        JsonElement extra = object.get("extra");
        if (extra != null && extra.isJsonArray()) {
            JsonArray runs = extra.getAsJsonArray();
            for (JsonElement run : runs) {
                out.append(plainText(run));
            }
        }
        return out.toString();
    }

    private static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /**
     * The value that follows {@code key} in the item string: a balanced {@code {...}} or
     * {@code [...]}, or a quoted string. Bracket counting rather than a regex, because these values
     * nest and contain brackets inside their own string literals.
     */
    static String value(String itemStack, String key) {
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
        if (open == '"') {
            boolean escaped = false;
            for (int i = start + 1; i < itemStack.length(); i++) {
                char c = itemStack.charAt(i);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return itemStack.substring(start, i + 1);
                }
            }
            return null;
        }
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

    private static int parseInt(Pattern pattern, String itemStack) {
        String group = parseGroup(pattern, itemStack);
        try {
            return group == null ? 0 : Integer.parseInt(group);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String parseGroup(Pattern pattern, String itemStack) {
        if (itemStack == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(itemStack);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
