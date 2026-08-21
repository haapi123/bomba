package com.betterloka.data;

import com.betterloka.BetterLoka;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A JSON file that remembers when it was written, so a slow, rarely-changing download can be done
 * once a day instead of once a session.
 *
 * <p>The mod's heaviest requests are lists that barely move — Loka's town roster is a megabyte and
 * changes when somebody founds a town, and the ranked history is another two megabytes that only
 * changes when a season ends. Re-fetching those every time the game starts is most of what the mod
 * costs a connection, and all of it is avoidable.
 */
public final class JsonStore<T> {
    private static final Gson GSON = new Gson();

    /** What actually goes in the file: the payload plus when it was fetched. */
    private record Envelope<T>(long fetchedAt, T value) {
    }

    private final Path file;
    private final Type envelopeType;
    private final long ttlMillis;

    /**
     * @param envelopeType the {@code TypeToken} of {@code Envelope<T>} for the payload being stored
     * @param ttlMillis    how long a saved copy stays good for
     */
    public JsonStore(Path file, Type envelopeType, long ttlMillis) {
        this.file = file;
        this.envelopeType = envelopeType;
        this.ttlMillis = ttlMillis;
    }

    /** The type to build a {@code JsonStore} with, for a payload of type {@code T}. */
    public static Type envelopeOf(Type payload) {
        return com.google.gson.reflect.TypeToken.getParameterized(Envelope.class, payload).getType();
    }

    /** @return the saved value if it is still fresh, otherwise {@code null}. */
    public T read() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Envelope<T> envelope = GSON.fromJson(reader, envelopeType);
            if (envelope == null || envelope.value() == null) {
                return null;
            }
            if (System.currentTimeMillis() - envelope.fetchedAt() > ttlMillis) {
                return null;
            }
            return envelope.value();
        } catch (IOException | RuntimeException e) {
            // A cache that cannot be read is not an error worth surfacing: refetch and overwrite.
            BetterLoka.LOGGER.debug("Could not read {}", file, e);
            return null;
        }
    }

    /** Writes through a temporary file, so an interrupted save cannot leave a torn cache. */
    public void write(T value) {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(new Envelope<>(System.currentTimeMillis(), value), envelopeType, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not write {}", file, e);
        }
    }
}
