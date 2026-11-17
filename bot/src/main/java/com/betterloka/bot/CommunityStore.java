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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the server's own features have to remember across a restart: how many suggestions there have
 * been, so each gets a number of its own ("Suggestion #12") that is never handed out twice; and, per
 * Discord server, the stat channels {@code /stats setup} made, so they keep being updated.
 *
 * <p>Everything else they need — which role a verification button gives, which panel a form came
 * from — travels in the button itself, so a restart loses nothing.
 */
public final class CommunityStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Saved {
        int suggestions;
        Map<String, StatChannelSet> stats = new LinkedHashMap<>();
    }

    /** One Discord server's stat channels: their category, and each tile's channel by tile name. */
    public static final class StatChannelSet {
        public String category = "";
        public Map<String, String> channels = new LinkedHashMap<>();
    }

    private final Path file;
    private Saved saved = new Saved();

    public CommunityStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Saved loaded = GSON.fromJson(reader, Saved.class);
            if (loaded != null) {
                saved = loaded;
            }
        } catch (IOException | RuntimeException e) {
            // A damaged file starts the count again rather than stopping the bot: the numbers are
            // a convenience, the suggestions themselves are on Discord.
            saved = new Saved();
        }
    }

    /** The next suggestion's number, written down before it is handed out. */
    public synchronized int nextSuggestion() {
        saved.suggestions++;
        save();
        return saved.suggestions;
    }

    /** Every server's stat channels, by server id; a copy. */
    public synchronized Map<String, StatChannelSet> statChannels() {
        Map<String, StatChannelSet> copy = new LinkedHashMap<>();
        if (saved.stats != null) {
            for (Map.Entry<String, StatChannelSet> entry : saved.stats.entrySet()) {
                StatChannelSet set = new StatChannelSet();
                set.category = entry.getValue().category;
                set.channels = new LinkedHashMap<>(entry.getValue().channels);
                copy.put(entry.getKey(), set);
            }
        }
        return copy;
    }

    public synchronized void setStatChannels(String guildId, StatChannelSet set) {
        if (saved.stats == null) {
            saved.stats = new LinkedHashMap<>();
        }
        if (set == null || set.channels.isEmpty() && (set.category == null || set.category.isEmpty())) {
            saved.stats.remove(guildId);
        } else {
            saved.stats.put(guildId, set);
        }
        save();
    }

    private void save() {
        if (file == null) {
            return;
        }
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
            // The number has been handed out either way; losing the file only risks reusing it later.
        }
    }
}
