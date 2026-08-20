package com.betterloka.towns;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the territory log on disk, so what fell is still there next session and a restart does not
 * start from a blank baseline.
 *
 * <p>The deleted-town names are kept too. They are the expensive half of the sweep — forty requests —
 * and they never change once written, since a town cannot be un-deleted.
 */
public final class TownLogStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** How many events to keep before the oldest are dropped. */
    private static final int MAX_EVENTS = 400;

    /** The saved shape. Plain fields so Gson handles it without adapters. */
    private static final class Saved {
        List<TownLogEvent> events = new ArrayList<>();
        Map<String, Territory> territories = new LinkedHashMap<>();
        Map<String, DeadTown> deletedTowns = new LinkedHashMap<>();
        long deletedTownsLoadedAt;
        Set<String> reported = new HashSet<>();
    }

    /** Only the name is worth keeping: it is the one thing a deleted town cannot be asked for. */
    private record DeadTown(String id, String name, String world) {
    }

    private final Path file;
    private Saved saved = new Saved();

    public TownLogStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Saved loaded = GSON.fromJson(reader, new TypeToken<Saved>() {
            }.getType());
            if (loaded != null) {
                saved = loaded;
                if (saved.events == null) {
                    saved.events = new ArrayList<>();
                }
                if (saved.territories == null) {
                    saved.territories = new LinkedHashMap<>();
                }
                if (saved.deletedTowns == null) {
                    saved.deletedTowns = new LinkedHashMap<>();
                }
                if (saved.reported == null) {
                    saved.reported = new HashSet<>();
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            BetterLoka.LOGGER.warn("Could not read the saved town log, starting fresh", e);
            saved = new Saved();
        }
    }

    /**
     * Folds one sweep in.
     *
     * <p>A fallen town is a standing fact about a territory rather than a moment, so the same one
     * comes back on every sweep until Loka clears the claim; those are recorded once. Handovers are
     * genuine changes and are always appended — the same territory really can change hands twice.
     */
    public synchronized void record(Map<String, Territory> territories, List<TownLogEvent> events,
                                    Map<String, LokaTown> deletedTowns, long deletedTownsLoadedAt) {
        for (TownLogEvent event : events) {
            if (event.kind().isTownGone() && !saved.reported.add(event.identity())) {
                continue;
            }
            saved.events.add(event);
        }
        while (saved.events.size() > MAX_EVENTS) {
            saved.events.remove(0);
        }

        saved.territories = new LinkedHashMap<>(territories);
        if (deletedTowns != null && !deletedTowns.isEmpty()) {
            Map<String, DeadTown> dead = new LinkedHashMap<>();
            for (LokaTown town : deletedTowns.values()) {
                dead.put(town.id(), new DeadTown(town.id(), town.name(), town.world()));
            }
            saved.deletedTowns = dead;
            saved.deletedTownsLoadedAt = deletedTownsLoadedAt;
        }
        save();
    }

    public synchronized List<TownLogEvent> events() {
        return List.copyOf(saved.events);
    }

    public synchronized Map<String, Territory> territories() {
        return Map.copyOf(saved.territories);
    }

    /** The deleted towns as the logger wants them: id to town. */
    public synchronized Map<String, LokaTown> deletedTowns() {
        Map<String, LokaTown> towns = new LinkedHashMap<>();
        for (DeadTown dead : saved.deletedTowns.values()) {
            towns.put(dead.id(), LokaTown.deleted(dead.id(), dead.name(), dead.world()));
        }
        return Map.copyOf(towns);
    }

    public synchronized long deletedTownsLoadedAt() {
        return saved.deletedTownsLoadedAt;
    }

    public synchronized void clearEvents() {
        saved.events.clear();
        saved.reported.clear();
        save();
    }

    /** Writes through a temporary file so an interrupted save cannot truncate the log. */
    private void save() {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BetterLoka.LOGGER.warn("Could not save the town log", e);
        }
    }
}
