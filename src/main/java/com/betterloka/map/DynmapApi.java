package com.betterloka.map;

import com.betterloka.api.ApiException;
import com.betterloka.api.HttpTransport;
import com.betterloka.api.model.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Loka's public map.
 *
 * <p>{@code map.lokamc.com} runs Dynmap behind a LiveAtlas front end, one instance per continent,
 * and publishes its territory outlines as marker data — the same file the website draws from. That
 * makes the shapes here Loka's own rather than something approximated from a beacon coordinate: the
 * game API gives one point per territory and nothing about where its borders run.
 *
 * <p>One request per continent, a few hundred kilobytes between all five, and the result barely
 * moves — borders change when a territory is captured, not minute to minute.
 */
public final class DynmapApi {
    private static final String BASE_URL = "https://map.lokamc.com";

    /** {@code <h2>Falcon Fury Territory<br/><small>Owner: Corvus</small>...} */
    private static final Pattern OWNER = Pattern.compile("Owner:\\s*([^<]+)");

    /** The heading names the alliance when one holds it: {@code <h2>Falcon Fury Territory}. */
    private static final Pattern ALLIANCE = Pattern.compile("<h2>\\s*(.+?)\\s+(?:Alliance\\s+)?Territory");

    /** {@code <h3><b>Mutator: Air Support</b></h3>} */
    private static final Pattern MUTATOR = Pattern.compile("Mutator:\\s*([^<]+)");

    /** {@code <br/>Cherry Grove 129<br/>} — the region name and the territory number together. */
    private static final Pattern AREA = Pattern.compile("<br/>\\s*(.*?)\\s*(\\d+)\\s*<br/>");

    /** A town card: {@code <h2>Vanguard<br/><small>ChickenCurry_0 Alliance - 183 strength...} */
    private static final Pattern TOWN_NAME = Pattern.compile("<h2>\\s*([^<]+?)\\s*<br/>");
    /**
     * Anchored to the {@code <small>} the card's detail line opens with.
     *
     * <p>Unanchored, the alliance group swallows the markup before it and reports the town's own
     * name and tags as its alliance.
     */
    private static final Pattern TOWN_STRENGTH =
            Pattern.compile("<small>\\s*(?:([^<>]+?)\\s+-\\s+)?([0-9.]+)\\s+strength");
    private static final Pattern TOWN_COUNTS =
            Pattern.compile("(\\d+)\\s+members\\s*\\|\\s*(\\d+)\\s+territories");

    private final HttpTransport transport;

    public DynmapApi(HttpTransport transport) {
        this.transport = transport;
    }

    public ExecutorService executor() {
        return transport.executor();
    }

    /** Everything one continent's marker file carries: its outlines and its town cards. */
    public record ContinentData(List<MapTerritory> territories, List<MapTown> towns) {
    }

    /** Both halves in one request — they live in the same file. */
    public ContinentData fetchContinent(Continent continent) throws ApiException {
        String url = BASE_URL + "/" + continent.instance()
                + "/tiles/_markers_/marker_" + continent.world() + ".json";
        JsonObject json = getObject(url);
        JsonObject sets = Json.object(json, "sets");
        JsonObject markerSet = sets == null ? null : Json.object(sets, "markers");
        JsonObject markers = markerSet == null ? null : Json.object(markerSet, "markers");
        return new ContinentData(territoriesFrom(json), towns(markers));
    }

    /** Every territory on one continent, outlines and all. */
    public List<MapTerritory> fetchTerritories(Continent continent) throws ApiException {
        String url = BASE_URL + "/" + continent.instance()
                + "/tiles/_markers_/marker_" + continent.world() + ".json";
        return territoriesFrom(getObject(url));
    }

    private static List<MapTerritory> territoriesFrom(JsonObject json) {
        JsonObject sets = Json.object(json, "sets");
        if (sets == null) {
            return List.of();
        }
        JsonObject territories = Json.object(sets, "Territories");
        JsonObject markerSet = Json.object(sets, "markers");
        if (territories == null) {
            return List.of();
        }

        // The outlines and the descriptions are two different marker sets keyed the same way, so the
        // labels are indexed first and each polygon picks up its own.
        Map<String, String> labels = new HashMap<>();
        Map<String, double[]> centers = new HashMap<>();
        Map<String, String> icons = new HashMap<>();
        if (markerSet != null) {
            JsonObject markers = Json.object(markerSet, "markers");
            if (markers != null) {
                for (Map.Entry<String, JsonElement> entry : markers.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject marker = entry.getValue().getAsJsonObject();
                    labels.put(entry.getKey(), Json.string(marker, "label"));
                    icons.put(entry.getKey(), Json.string(marker, "icon"));
                    centers.put(entry.getKey(), new double[] {
                            Json.doubleValue(marker, "x", 0), Json.doubleValue(marker, "z", 0)});
                }
            }
        }

        JsonObject areas = Json.object(territories, "areas");
        if (areas == null) {
            return List.of();
        }

        List<MapTerritory> result = new ArrayList<>(areas.size());
        for (Map.Entry<String, JsonElement> entry : areas.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            MapTerritory territory = parse(entry.getKey(), entry.getValue().getAsJsonObject(),
                    labels.get(entry.getKey()), centers.get(entry.getKey()),
                    icons.get(entry.getKey()));
            if (territory != null) {
                result.add(territory);
            }
        }
        return List.copyOf(result);
    }

    /**
     * The towns on one continent, from the cards their markers show.
     *
     * <p>Same request as the territories, so this is fed the marker set rather than fetching again.
     */
    public static List<MapTown> towns(JsonObject markers) {
        if (markers == null) {
            return List.of();
        }
        List<MapTown> towns = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : markers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject marker = entry.getValue().getAsJsonObject();
            String icon = Json.string(marker, "icon");
            if (icon == null || !icon.startsWith("town")) {
                continue;
            }
            MapTown town = parseTown(Json.string(marker, "label"));
            if (town != null) {
                towns.add(town);
            }
        }
        return List.copyOf(towns);
    }

    static MapTown parseTown(String label) {
        if (label == null) {
            return null;
        }
        Matcher name = TOWN_NAME.matcher(label);
        if (!name.find()) {
            return null;
        }
        String alliance = null;
        double strength = -1;
        Matcher strengthMatch = TOWN_STRENGTH.matcher(label);
        if (strengthMatch.find()) {
            alliance = strengthMatch.group(1) == null ? null : strengthMatch.group(1).trim();
            try {
                strength = Double.parseDouble(strengthMatch.group(2));
            } catch (NumberFormatException ignored) {
                strength = -1;
            }
        }
        int members = 0;
        int territories = 0;
        Matcher counts = TOWN_COUNTS.matcher(label);
        if (counts.find()) {
            members = Integer.parseInt(counts.group(1));
            territories = Integer.parseInt(counts.group(2));
        }
        return new MapTown(name.group(1).trim(), alliance, strength, members, territories);
    }

    /** Background work: a map nobody has opened yet must not queue in front of a search. */
    private JsonObject getObject(String url) throws ApiException {
        String body = transport.get(url, true);
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                throw new ApiException("Unexpected response shape from " + url, false);
            }
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new ApiException("Malformed response from " + url, e);
        }
    }

    private static MapTerritory parse(String key, JsonObject area, String label, double[] center,
                                      String icon) {
        double[] xs = doubles(area, "x");
        double[] zs = doubles(area, "z");
        if (xs.length < 3 || xs.length != zs.length) {
            return null;
        }

        String number = Json.string(area, "label");
        if (number == null || number.isBlank()) {
            // The key is "<world>-<number>"; the label is the friendlier source but not guaranteed.
            int dash = key.lastIndexOf('-');
            number = dash < 0 ? key : key.substring(dash + 1);
        }

        String plain = label == null ? "" : label;
        String owner = group(OWNER, plain);
        String alliance = group(ALLIANCE, plain);
        String mutator = group(MUTATOR, plain);
        String areaName = areaName(plain);

        double centerX = center != null ? center[0] : average(xs);
        double centerZ = center != null ? center[1] : average(zs);

        return new MapTerritory(number, areaName, owner, alliance, mutator, xs, zs,
                centerX, centerZ, color(Json.string(area, "fillcolor")),
                color(Json.string(area, "color")), icon);
    }

    /**
     * The region's name, out of the line that reads {@code Cherry Grove 129}.
     *
     * <p>Taken from the line carrying the territory number rather than the heading, because the
     * heading is the holder's name when somebody holds it and the region's name when nobody does.
     */
    private static String areaName(String label) {
        Matcher matcher = AREA.matcher(label);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? null : value;
    }

    /** {@code #3AB3DA} as Dynmap writes it, or a neutral grey when it is missing or malformed. */
    private static int color(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return 0x8A8F98;
        }
        try {
            return Integer.parseInt(hex.substring(1), 16);
        } catch (NumberFormatException e) {
            return 0x8A8F98;
        }
    }

    private static double[] doubles(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            return new double[0];
        }
        var array = element.getAsJsonArray();
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsDouble();
        }
        return values;
    }

    private static double average(double[] values) {
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return values.length == 0 ? 0 : total / values.length;
    }
}
