package com.betterloka.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the bot has already announced.
 *
 * <p>A fallen town is a standing fact rather than a moment — the dangling claim sits there until Loka
 * clears it — so without this the bot would ping the role with the same dozen towns every five
 * minutes.
 */
public final class BotState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Plain fields so Gson reads it without adapters. */
    private static final class Saved {
        Set<String> announced = new LinkedHashSet<>();
        boolean seeded;
        /**
         * Loka's deleted-town listing as last read, how many it held, and when — so a restart asks
         * only for the count (one small request) instead of reading the forty-odd pages again.
         */
        List<com.betterloka.api.model.LokaTown.Saved> deletedTowns;
        int deletedCount = -1;
        long deletedLoadedAt;
        long lastFullSweepAt;
        /** Alerts Discord has not taken yet, one per place, tried again until it does. */
        List<Pending> pending = new ArrayList<>();
    }

    /**
     * One alert waiting for one place: kept on disk, so neither a Discord outage nor a restart
     * loses it. Taken off once Discord has it; given up on after {@link #GIVE_UP_MILLIS}.
     */
    public static final class Pending {
        String target;
        String content;
        String embeds;
        String what;
        long since;
        long nextAt;
        int attempts;

        /** The place, as {@link BotConfig.Target#key()} names it. */
        public String target() {
            return target;
        }

        public String content() {
            return content;
        }

        /** The embeds as a JSON array. */
        public String embeds() {
            return embeds;
        }

        /** The towns it is about, for the log. */
        public String what() {
            return what;
        }

        public long since() {
            return since;
        }

        public long nextAt() {
            return nextAt;
        }

        public int attempts() {
            return attempts;
        }
    }

    /** How long an alert is tried before it is given up on: by then the territory is long taken. */
    public static final long GIVE_UP_MILLIS = 24 * 60 * 60_000L;
    private static final long FIRST_RETRY_MILLIS = 30_000;
    private static final long LONGEST_RETRY_MILLIS = 30 * 60_000L;

    private final Path file;
    private Saved saved = new Saved();

    public BotState(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Saved loaded = GSON.fromJson(reader, Saved.class);
            if (loaded != null) {
                saved = loaded;
                if (saved.announced == null) {
                    saved.announced = new LinkedHashSet<>();
                }
                if (saved.pending == null) {
                    saved.pending = new ArrayList<>();
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    /** True until the first sweep has completed, i.e. while there is no baseline to compare with. */
    public synchronized boolean isFirstRun() {
        return !saved.seeded;
    }

    public synchronized void clearFirstRun() {
        saved.seeded = true;
    }

    /**
     * Records these keys as seen.
     *
     * @return true if any of them are new, i.e. whether there is anything to announce
     */
    public synchronized boolean markSeen(List<String> keys) {
        boolean anyNew = false;
        for (String key : keys) {
            anyNew |= saved.announced.add(key);
        }
        return anyNew;
    }

    /** The deleted-town count at the last full sweep, or -1 before one. */
    public synchronized int deletedCount() {
        return saved.deletedCount;
    }

    public synchronized long lastFullSweepAt() {
        return saved.lastFullSweepAt;
    }

    public synchronized long deletedLoadedAt() {
        return saved.deletedLoadedAt;
    }

    /** The deleted towns as last read, by id; empty before the first read. */
    public synchronized Map<String, com.betterloka.api.model.LokaTown> deletedTowns() {
        Map<String, com.betterloka.api.model.LokaTown> towns = new HashMap<>();
        if (saved.deletedTowns != null) {
            for (com.betterloka.api.model.LokaTown.Saved town : saved.deletedTowns) {
                if (town != null && town.id() != null) {
                    towns.put(town.id(), com.betterloka.api.model.LokaTown.fromSaved(town));
                }
            }
        }
        return Map.copyOf(towns);
    }

    /** Remembers a full sweep: the count it saw, when, and the deleted-town listing it used. */
    public synchronized void rememberSweep(int deletedCount, long sweptAt, Map<String, com.betterloka.api.model.LokaTown> deleted,
                              long loadedAt) {
        saved.deletedCount = deletedCount;
        saved.lastFullSweepAt = sweptAt;
        List<com.betterloka.api.model.LokaTown.Saved> towns = new ArrayList<>();
        for (com.betterloka.api.model.LokaTown town : deleted.values()) {
            towns.add(town.toSaved());
        }
        saved.deletedTowns = towns;
        saved.deletedLoadedAt = loadedAt;
    }

    /** Whether every one of {@code keys} has been seen. */
    public synchronized boolean allSeen(List<String> keys) {
        return saved.announced.containsAll(keys);
    }

    /** Queues an alert for one place; it is sent from the queue, so a failure is tried again. */
    public synchronized void enqueue(String target, String content, String embeds, String what, long now) {
        Pending alert = new Pending();
        alert.target = target;
        alert.content = content;
        alert.embeds = embeds;
        alert.what = what;
        alert.since = now;
        alert.nextAt = now;
        saved.pending.add(alert);
    }

    /** The alerts waiting, oldest first. */
    public synchronized List<Pending> pending() {
        return List.copyOf(saved.pending);
    }

    /** Discord took it, or it is given up on. */
    public synchronized void remove(Pending alert) {
        saved.pending.remove(alert);
    }

    /** Discord refused it: try again later, waiting longer each time (30 s, 1 min, 2 min … 30 min). */
    public synchronized void retryLater(Pending alert, long now) {
        alert.attempts++;
        long wait = FIRST_RETRY_MILLIS << Math.min(alert.attempts - 1, 10);
        alert.nextAt = now + Math.min(wait, LONGEST_RETRY_MILLIS);
    }

    public synchronized int announcedCount() {
        return saved.announced.size();
    }

    /** Writes through a temporary file, so a kill mid-save cannot corrupt the memory. */
    public synchronized void save() {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }
}
