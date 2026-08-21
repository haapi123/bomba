package com.betterloka.api;

import com.betterloka.api.model.ArenaEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Client for Loka's ranked 1v1 standings.
 *
 * <p>The leaderboard page at {@code lokamc.com/leaderboards/arena} is an embedded app that reads
 * these endpoints, so the mod reads them directly rather than scraping the page around them.
 *
 * <p>Each response is the whole ladder — a few hundred rows — not one player, so a lookup means
 * fetching a ladder once and indexing it. {@code com.betterloka.stats.ArenaService} does that and
 * keeps the result.
 */
public final class ArenaApi {
    public static final String BASE_URL = "https://webapi.lokamc.com";

    /** The two ranked 1v1 ladders Loka runs. */
    public enum Ladder {
        POTION("potion", "potionranked", "betterloka.arena.potion"),
        BAREBONES("barebones", "barebonesranked", "betterloka.arena.barebones");

        private final String path;
        private final String statsKey;
        private final String translationKey;

        Ladder(String path, String statsKey, String translationKey) {
            this.path = path;
            this.statsKey = statsKey;
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }

        /**
         * Just the ladder's name, for places too tight for the full "Ranked 1v1 — Potion". Not
         * translated: "Potion" and "Barebones" are what Loka calls them in every language.
         */
        public String shortName() {
            return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final HttpTransport transport;

    public ArenaApi(HttpTransport transport) {
        this.transport = transport;
    }

    public ExecutorService executor() {
        return transport.executor();
    }

    public ExecutorService bulkExecutor() {
        return transport.bulkExecutor();
    }

    /** The standings as they are right now, i.e. this season so far. */
    public List<ArenaEntry> fetchCurrent(Ladder ladder) throws ApiException {
        return readLadder(BASE_URL + "/arena/" + ladder.path + "/1v1/players", ladder);
    }

    /**
     * The standings as they stood at the end of one week of a past season. The weekly snapshots are
     * cumulative within their season, so the last week of a season is how that season finished.
     */
    public List<ArenaEntry> fetchHistory(Ladder ladder, int season, int week) throws ApiException {
        return readLadder(BASE_URL + "/arena/history/" + ladder.path + "/1v1/players?season=" + season
                + "&week=" + week, ladder, true);
    }

    /** Every season that has published standings, ascending. */
    public List<Integer> fetchSeasons() throws ApiException {
        return readNumbers(BASE_URL + "/arena/history/seasons");
    }

    /** The weeks a season published, ascending. Seasons skip weeks, so these are not 1..n. */
    public List<Integer> fetchWeeks(int season) throws ApiException {
        return readNumbers(BASE_URL + "/arena/history/" + season + "/weeks");
    }

    private List<ArenaEntry> readLadder(String url, Ladder ladder) throws ApiException {
        return readLadder(url, ladder, false);
    }

    private List<ArenaEntry> readLadder(String url, Ladder ladder, boolean background) throws ApiException {
        List<ArenaEntry> entries = new ArrayList<>();
        int position = 0;
        for (JsonElement element : readArray(url, background)) {
            if (!element.isJsonObject()) {
                continue;
            }
            ArenaEntry entry = ArenaEntry.fromJson(element.getAsJsonObject(), ladder.statsKey, ++position);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private List<Integer> readNumbers(String url) throws ApiException {
        List<Integer> numbers = new ArrayList<>();
        // Always part of building the history index, which nobody is waiting on.
        for (JsonElement element : readArray(url, true)) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                numbers.add(element.getAsInt());
            }
        }
        return numbers;
    }

    private JsonArray readArray(String url, boolean background) throws ApiException {
        String body = transport.get(url, background);
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonArray()) {
                throw new ApiException("Unexpected response shape from " + url, false);
            }
            return parsed.getAsJsonArray();
        } catch (JsonSyntaxException e) {
            throw new ApiException("Malformed response from " + url, e);
        }
    }
}
