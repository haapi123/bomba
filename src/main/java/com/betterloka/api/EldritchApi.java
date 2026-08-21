package com.betterloka.api;

import com.betterloka.api.model.EldritchStats;
import com.betterloka.api.model.FightDetail;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for <a href="https://eldritchbot.com">EldritchBot</a>, which parses Loka's Conquest fight
 * logs and publishes per-player career totals.
 *
 * <p>It has one JSON endpoint ({@code /api/player/<name>}, case sensitive, kills and deaths only);
 * everything else — assists, consumables, wins, golems, lamps, nemesis — is on the server-rendered
 * player page, so those are read out of the HTML. The markup is plain server-side templating with
 * stable class names, and every field is parsed defensively: a layout change degrades individual
 * numbers to zero rather than failing the lookup.
 */
public final class EldritchApi {
    public static final String BASE_URL = "https://eldritchbot.com";

    /** {@code <tr><td>Label: </td><td class="text-right player-stats-value">1234</td></tr>} */
    private static final Pattern STAT_ROW = Pattern.compile(
            "<td>\\s*([A-Za-z ]+?):\\s*</td>\\s*<td[^>]*player-stats-value[^>]*>\\s*([\\-0-9,]*)\\s*</td>");

    private static final Pattern PLAYER_NAME = Pattern.compile("<h1 id=\"player\"[^>]*>\\s*<span>([^<]*)</span>");
    private static final Pattern PLAYER_TOWN = Pattern.compile(
            "<h1 id=\"player\"[^>]*>\\s*<span>[^<]*</span>\\s*</h1>\\s*<h3[^>]*>([^<]*)</h3>");
    private static final Pattern LAST_FIGHT = Pattern.compile("Last Fight:\\s*<span[^>]*>([^<]*)</span>");
    private static final Pattern NEMESIS_NAME = Pattern.compile("<h2 id=\"nemesis\"[^>]*>\\s*<span>([^<]*)</span>");
    private static final Pattern NEMESIS_DEATHS = Pattern.compile("<span class=\"deaths\">\\s*([0-9,]+)\\s*Deaths?\\s*</span>");

    private static final Pattern RECENT_FIGHT_ROW = Pattern.compile("<tr>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern ROW_FIGHT_ID = Pattern.compile("fight\\?id=([^\"&]+)");
    /** The four cells of a row: date, result, then the two towns. */
    private static final Pattern ROW_CELL = Pattern.compile("<h5>(.*?)</h5>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private static final Pattern FIGHT_DATA = Pattern.compile("var\\s+fightData\\s*=\\s*");

    private final HttpTransport transport;

    public EldritchApi(HttpTransport transport) {
        this.transport = transport;
    }

    public ExecutorService executor() {
        return transport.executor();
    }

    public ExecutorService bulkExecutor() {
        return transport.bulkExecutor();
    }

    /**
     * Kills and deaths only, from the JSON endpoint. Small and quick, which is what the in-world
     * nameplate overlay needs — the full page is 30 KB of HTML for the same two numbers.
     *
     * <p>The endpoint is case sensitive and answers {@code {}} for a name it does not know.
     *
     * @return the summary, or {@code null} if EldritchBot has never seen that player.
     */
    public EldritchStats fetchQuickStats(String name) throws ApiException {
        // Background: the nameplate overlay fills in as players come into view, so it must never
        // delay a lookup somebody is sitting in front of.
        String body = transport.get(BASE_URL + "/api/player/" + encode(name), true);
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (!json.has("name")) {
                return null;
            }
            int wins = intOf(json, "wins");
            int fights = intOf(json, "fights");
            return new EldritchStats(
                    stringOf(json, "name"), stringOf(json, "_id"), stringOf(json, "town"), null,
                    intOf(json, "kills"), intOf(json, "deaths"), 0,
                    0, 0, 0, 0,
                    wins, Math.max(0, fights - wins), 0, 0, 0, 0,
                    null, 0, List.of());
        } catch (JsonSyntaxException e) {
            throw new ApiException("Malformed response from EldritchBot for " + name, e);
        }
    }

    /**
     * The player's full career record. Accepts a name in any casing or an undashed UUID; EldritchBot
     * redirects away for players it does not know, which surfaces as a not-found {@link ApiException}.
     */
    public EldritchStats fetchStats(String nameOrUuid) throws ApiException {
        String html = transport.get(BASE_URL + "/player/" + encode(nameOrUuid));
        requirePlayerPage(html, nameOrUuid);
        return parsePlayerPage(html);
    }

    /**
     * @throws ApiException not-found when the response is not a player page. EldritchBot answers an
     *                      unknown name with a redirect, whose body carries no player markup.
     */
    static void requirePlayerPage(String html, String nameOrUuid) throws ApiException {
        if (group(PLAYER_NAME, html) == null) {
            throw new ApiException("EldritchBot has no player page for " + nameOrUuid, true);
        }
    }

    /** Reads a player page's markup. Package-visible so the parser can be tested without HTTP. */
    static EldritchStats parsePlayerPage(String html) {
        String name = group(PLAYER_NAME, html);
        Stats stats = new Stats(html);
        int nemesisDeaths = 0;
        String nemesisDeathsRaw = group(NEMESIS_DEATHS, html);
        if (nemesisDeathsRaw != null) {
            nemesisDeaths = parseInt(nemesisDeathsRaw);
        }

        return new EldritchStats(
                unescape(name),
                null,
                unescape(group(PLAYER_TOWN, html)),
                unescape(group(LAST_FIGHT, html)),
                stats.get("Kills"),
                stats.get("Deaths"),
                stats.get("Assists"),
                stats.get("Food"),
                stats.get("Potions"),
                stats.get("Pearls"),
                stats.get("Ancient Ingots"),
                stats.get("Wins"),
                stats.get("Losses"),
                stats.get("Golems"),
                stats.get("Lamps"),
                stats.get("First Bloods"),
                stats.get("Close Calls"),
                unescape(group(NEMESIS_NAME, html)),
                nemesisDeaths,
                parseRecentFights(html));
    }

    private static List<EldritchStats.RecentFight> parseRecentFights(String html) {
        int start = html.indexOf("recentFights");
        if (start < 0) {
            return List.of();
        }
        // Confine the scan to the table so the greedy inter-cell matching cannot wander off it.
        int end = html.indexOf("</table>", start);
        String table = end > start ? html.substring(start, end) : html.substring(start);

        List<EldritchStats.RecentFight> fights = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher rows = RECENT_FIGHT_ROW.matcher(table);
        while (rows.find()) {
            String row = rows.group(1);

            Matcher id = ROW_FIGHT_ID.matcher(row);
            if (!id.find() || !seen.add(id.group(1))) {
                continue;
            }

            // Cells in order: date, victory/defeat, attacking town, defending town. The player's own
            // side is whichever town cell carries the "assists" class, which moves between the two.
            List<String> cells = new ArrayList<>(4);
            String ownTown = null;
            Matcher cell = ROW_CELL.matcher(row);
            while (cell.find()) {
                String raw = cell.group(1);
                String text = unescape(TAG.matcher(raw).replaceAll(""));
                if (cells.size() >= 2 && raw.contains("\'assists\'")) {
                    ownTown = text;
                }
                cells.add(text);
            }
            if (cells.size() < 4) {
                continue;
            }

            fights.add(new EldritchStats.RecentFight(
                    id.group(1), cells.get(0), "victory".equalsIgnoreCase(cells.get(1)),
                    cells.get(2), cells.get(3), ownTown));
        }
        return List.copyOf(fights);
    }

    /**
     * One player's numbers from a single fight.
     *
     * @return the detail, or {@code null} if that player was not in the fight.
     */
    public FightDetail fetchFight(String fightId, String playerName) throws ApiException {
        String html = transport.get(BASE_URL + "/fight?id=" + encode(fightId));
        FightDetail detail = parseFightData(html, fightId, playerName);
        if (detail == null && extractFightData(html) == null) {
            throw new ApiException("No fight data on EldritchBot page for " + fightId, false);
        }
        return detail;
    }

    /**
     * Reads one player's line out of a fight page. Package-visible so the parser can be tested
     * without HTTP.
     *
     * @return the detail, or {@code null} if the page has no data or that player was not there.
     */
    static FightDetail parseFightData(String html, String fightId, String playerName) {
        JsonObject fight = extractFightData(html);
        if (fight == null) {
            return null;
        }

        JsonObject attackers = fight.getAsJsonObject("attackers");
        JsonObject defenders = fight.getAsJsonObject("defenders");
        int attackerCount = playerCount(attackers);
        int defenderCount = playerCount(defenders);

        JsonObject line = findPlayer(attackers, playerName);
        boolean attacked = line != null;
        if (line == null) {
            line = findPlayer(defenders, playerName);
        }
        if (line == null) {
            return null;
        }

        return new FightDetail(
                fightId,
                stringOf(fight, "location"),
                fight.has("attackersWon") && !fight.get("attackersWon").isJsonNull()
                        && fight.get("attackersWon").getAsBoolean(),
                attackerCount,
                defenderCount,
                attacked,
                stringOf(line, "town"),
                intOf(line, "pkills"),
                intOf(line, "gkills"),
                intOf(line, "deaths"),
                intOf(line, "assists2"),
                intOf(line, "lamps"),
                intOf(line, "potions"),
                intOf(line, "pearls"),
                intOf(line, "closeCalls"));
    }

    private static int playerCount(JsonObject side) {
        JsonArray players = side == null ? null : side.getAsJsonArray("players");
        return players == null ? 0 : players.size();
    }

    private static JsonObject findPlayer(JsonObject side, String playerName) {
        JsonArray players = side == null ? null : side.getAsJsonArray("players");
        if (players == null) {
            return null;
        }
        for (JsonElement element : players) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject player = element.getAsJsonObject();
            if (playerName.equalsIgnoreCase(stringOf(player, "name"))) {
                return player;
            }
        }
        return null;
    }

    /**
     * Pulls the {@code var fightData = {...}} object out of the page. Brace counting rather than a
     * regex, because the blob is deeply nested and contains braces inside string values.
     */
    private static JsonObject extractFightData(String html) {
        Matcher matcher = FIGHT_DATA.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        int open = html.indexOf('{', matcher.end());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
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
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                try {
                    JsonElement parsed = JsonParser.parseString(html.substring(open, i + 1));
                    return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
                } catch (JsonSyntaxException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The label/value rows of the three stat tables, keyed by their label. */
    private static final class Stats {
        private final java.util.Map<String, Integer> values = new java.util.HashMap<>();

        Stats(String html) {
            Matcher matcher = STAT_ROW.matcher(html);
            while (matcher.find()) {
                values.put(matcher.group(1).trim(), parseInt(matcher.group(2)));
            }
        }

        int get(String label) {
            return values.getOrDefault(label, 0);
        }
    }

    private static int parseInt(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String group(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? null : value;
    }

    private static String stringOf(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static int intOf(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return 0;
        }
    }

    /** The page is plain server-rendered HTML; only a handful of entities ever show up in names. */
    private static String unescape(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Case-normalised key for caching lookups that are themselves case-insensitive. */
    public static String cacheKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
