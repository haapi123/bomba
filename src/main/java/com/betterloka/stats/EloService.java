package com.betterloka.stats;

import com.betterloka.BetterLoka;
import com.betterloka.api.ApiException;
import com.betterloka.api.HttpTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conquest Elo, from <a href="https://loka-elo.pages.dev">Loka Elo</a>.
 *
 * <p>The site publishes its whole leaderboard as one static file — every rated player with their
 * UUID, name, former names and rating — which is exactly what its own page loads. That file, and the
 * small {@code meta.json} that says which version is current, are all that is read: the site's
 * {@code /api/} is behind a challenge and disallowed to robots, and is never touched. Players the
 * site has been asked to hide (its {@code meta.hide} list) are hidden here too.
 *
 * <p>About a megabyte compressed, it is fetched once a version — the site regenerates it once a day
 * or so — kept on disk, and checked again at most every half hour. Lookups are local and instant,
 * which is what lets a nameplate carry a rating without a request per player, and what finds a
 * player under a name they no longer use: the file lists every name each account has had.
 */
public final class EloService {
    public static final String SITE = "https://loka-elo.pages.dev";

    /** How often the site is asked whether there is a newer leaderboard. */
    private static final long CHECK_EVERY_MILLIS = 30 * 60 * 1000L;

    /** The oldest saved leaderboard still worth using while a newer one is fetched. */
    private static final long DISK_MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000;

    /** One rated player. */
    public record Entry(UUID uuid, String name, List<String> aliases, double elo, double peakElo, int games,
                        int rank, String town, String lastActive) {
        /** The rating as it is shown: rounded to a whole number. */
        public long shown() {
            return Math.round(elo);
        }
    }

    /** The whole leaderboard, indexed three ways; replaced whole, never edited. */
    record Board(String version, long savedAt, Map<UUID, Entry> byUuid, Map<String, Entry> byName,
                         Map<String, Entry> byAlias, int players) {
        static final Board EMPTY = new Board(null, 0, Map.of(), Map.of(), Map.of(), 0);
    }

    private final HttpTransport transport;
    private final Path cacheFile;
    private final Executor executor;
    private volatile Board board = Board.EMPTY;
    private volatile long checkedAt;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public EloService(HttpTransport transport, Path cacheFile, Executor executor) {
        this.transport = transport;
        this.cacheFile = cacheFile;
        this.executor = executor;
    }

    /** Reads the saved leaderboard back, then checks the site for a newer one; both off this thread. */
    public void start() {
        executor.execute(() -> {
            restore();
            refreshNow();
        });
    }

    /** Checks for a newer leaderboard if the last check was long enough ago; never blocks. */
    public void refreshIfDue() {
        if (System.currentTimeMillis() - checkedAt < CHECK_EVERY_MILLIS) {
            return;
        }
        executor.execute(this::refreshNow);
    }

    /** Whether there is a leaderboard to look players up in yet. */
    public boolean ready() {
        return board.players() > 0;
    }

    /** How many players the leaderboard holds. */
    public int size() {
        return board.players();
    }

    /** A player by their account, or {@code null} if they are not rated. Instant. */
    public Entry byUuid(UUID uuid) {
        return uuid == null ? null : board.byUuid().get(uuid);
    }

    /**
     * A player by any name they have gone by: their current one first, then former ones. Instant.
     *
     * @return the player, or {@code null} if no rated player has used the name
     */
    public Entry byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        Board current = board;
        Entry entry = current.byName().get(key);
        return entry != null ? entry : current.byAlias().get(key);
    }

    /** Puts a parsed leaderboard in place, for tests. */
    void useForTest(Board parsed) {
        board = parsed;
    }

    private void refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            checkedAt = System.currentTimeMillis();
            JsonObject meta = JsonParser.parseString(transport.get(SITE + "/meta.json", true)).getAsJsonObject();
            String version = meta.has("version") ? meta.get("version").getAsString() : null;
            if (version != null && version.equals(board.version())) {
                return;
            }
            Set<String> hidden = new HashSet<>();
            JsonElement hide = meta.get("hide");
            if (hide != null && hide.isJsonArray()) {
                for (JsonElement element : hide.getAsJsonArray()) {
                    hidden.add(element.getAsString());
                }
            }
            String url = SITE + "/leaderboard.json" + (version == null ? "" : "?v=" + version);
            String body = transport.getCompressed(url, true);
            Board fresh = parse(body, version, System.currentTimeMillis(), hidden);
            if (fresh.players() > 0) {
                board = fresh;
                save(fresh);
                BetterLoka.LOGGER.info("[betterloka] Loka Elo leaderboard {}: {} players", version, fresh.players());
            }
        } catch (ApiException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not refresh the Loka Elo leaderboard", e);
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * Reads the site's leaderboard file, keeping what is shown and skipping hidden and unrated
     * players. Streamed: the file is three megabytes and most of each record is not needed.
     */
    static Board parse(String json, String version, long savedAt, Set<String> hidden) {
        Map<UUID, Entry> byUuid = new HashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"players".equals(reader.nextName())) {
                    reader.skipValue();
                    continue;
                }
                reader.beginArray();
                while (reader.hasNext()) {
                    Entry entry = readPlayer(reader);
                    if (entry != null && entry.games() > 0 && !hidden.contains(h32(entry.uuid().toString()))) {
                        byUuid.put(entry.uuid(), entry);
                    }
                }
                reader.endArray();
            }
            reader.endObject();
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the Loka Elo leaderboard", e);
            return Board.EMPTY;
        }
        return index(version, savedAt, byUuid);
    }

    private static Board index(String version, long savedAt, Map<UUID, Entry> byUuid) {
        Map<String, Entry> byName = new HashMap<>();
        Map<String, Entry> byAlias = new HashMap<>();
        for (Entry entry : byUuid.values()) {
            byName.put(entry.name().toLowerCase(Locale.ROOT), entry);
        }
        for (Entry entry : byUuid.values()) {
            for (String alias : entry.aliases()) {
                String key = alias.toLowerCase(Locale.ROOT);
                // A name two accounts have used goes to the one that fought more under the board.
                Entry other = byAlias.get(key);
                if (!byName.containsKey(key) && (other == null || other.games() < entry.games())) {
                    byAlias.put(key, entry);
                }
            }
        }
        return new Board(version, savedAt, Map.copyOf(byUuid), Map.copyOf(byName), Map.copyOf(byAlias),
                byUuid.size());
    }

    private static Entry readPlayer(JsonReader reader) throws IOException {
        String id = null;
        String name = null;
        List<String> aliases = new ArrayList<>();
        double elo = 0;
        double peak = 0;
        int games = 0;
        int rank = 0;
        String town = null;
        String lastActive = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                continue;
            }
            switch (field) {
                case "id" -> id = reader.nextString();
                case "name" -> name = reader.nextString();
                case "elo" -> elo = reader.nextDouble();
                case "peak_elo" -> peak = reader.nextDouble();
                case "games" -> games = reader.nextInt();
                case "rank" -> rank = reader.nextInt();
                case "town" -> town = reader.nextString();
                case "last_active" -> lastActive = reader.nextString();
                case "aliases" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        aliases.add(reader.nextString());
                    }
                    reader.endArray();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (id == null || name == null) {
            return null;
        }
        try {
            return new Entry(UUID.fromString(id), name, List.copyOf(aliases), elo, peak, games, rank, town, lastActive);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The site's own hash for its hide list: 32-bit FNV-1a over the UUID's text, in hex. */
    static String h32(String text) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x01000193;
        }
        return String.format("%08x", hash);
    }

    // --- on disk ---

    private void restore() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            long savedAt = saved.get("savedAt").getAsLong();
            if (System.currentTimeMillis() - savedAt > DISK_MAX_AGE_MILLIS) {
                return;
            }
            Map<UUID, Entry> byUuid = new HashMap<>();
            for (JsonElement element : saved.getAsJsonArray("players")) {
                JsonArray row = element.getAsJsonArray();
                List<String> aliases = new ArrayList<>();
                for (JsonElement alias : row.get(8).getAsJsonArray()) {
                    aliases.add(alias.getAsString());
                }
                Entry entry = new Entry(UUID.fromString(row.get(0).getAsString()), row.get(1).getAsString(),
                        List.copyOf(aliases), row.get(2).getAsDouble(), row.get(3).getAsDouble(),
                        row.get(4).getAsInt(), row.get(5).getAsInt(),
                        row.get(6).isJsonNull() ? null : row.get(6).getAsString(),
                        row.get(7).isJsonNull() ? null : row.get(7).getAsString());
                byUuid.put(entry.uuid(), entry);
            }
            String version = saved.has("version") && !saved.get("version").isJsonNull()
                    ? saved.get("version").getAsString() : null;
            if (board.players() == 0) {
                board = index(version, savedAt, byUuid);
            }
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the saved Loka Elo leaderboard", e);
        }
    }

    private void save(Board saved) {
        if (cacheFile == null) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", saved.version());
            root.addProperty("savedAt", saved.savedAt());
            JsonArray players = new JsonArray();
            for (Entry entry : saved.byUuid().values()) {
                JsonArray row = new JsonArray();
                row.add(entry.uuid().toString());
                row.add(entry.name());
                row.add(entry.elo());
                row.add(entry.peakElo());
                row.add(entry.games());
                row.add(entry.rank());
                row.add(entry.town());
                row.add(entry.lastActive());
                JsonArray aliases = new JsonArray();
                entry.aliases().forEach(aliases::add);
                row.add(aliases);
                players.add(row);
            }
            root.add("players", players);
            Files.createDirectories(cacheFile.getParent());
            Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not save the Loka Elo leaderboard", e);
        }
    }
}
