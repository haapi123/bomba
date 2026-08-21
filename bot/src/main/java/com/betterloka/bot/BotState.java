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
import java.util.LinkedHashSet;
import java.util.List;
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
    }

    private final Path file;
    private Saved saved = new Saved();

    public BotState(Path file) {
        this.file = file;
    }

    public void load() {
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
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    /** True until the first sweep has completed, i.e. while there is no baseline to compare with. */
    public boolean isFirstRun() {
        return !saved.seeded;
    }

    public void clearFirstRun() {
        saved.seeded = true;
    }

    /**
     * Records these keys as seen.
     *
     * @return true if any of them are new, i.e. whether there is anything to announce
     */
    public boolean markSeen(List<String> keys) {
        boolean anyNew = false;
        for (String key : keys) {
            anyNew |= saved.announced.add(key);
        }
        return anyNew;
    }

    public int announcedCount() {
        return saved.announced.size();
    }

    /** Writes through a temporary file, so a kill mid-save cannot corrupt the memory. */
    public void save() {
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
