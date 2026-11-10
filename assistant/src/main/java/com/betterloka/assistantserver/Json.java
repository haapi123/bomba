package com.betterloka.assistantserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/** Reading and writing the server's JSON files: whole files, written so a crash never leaves half of one. */
final class Json {
    static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    /** The file's contents, or {@code fallback} when it is missing or unreadable. */
    static <T> T read(Path file, Class<T> type, Supplier<T> fallback) {
        if (!Files.isRegularFile(file)) {
            return fallback.get();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T value = PRETTY.fromJson(reader, type);
            return value != null ? value : fallback.get();
        } catch (IOException | JsonParseException e) {
            Log.warn("Could not read " + file + " (" + e.getMessage() + "), starting it afresh");
            return fallback.get();
        }
    }

    static void write(Path file, Object value) {
        writeText(file, PRETTY.toJson(value));
    }

    /** Writes beside the file first and moves it into place, so readers see the old file or the new one. */
    static void writeText(Path file, String text) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.warn("Could not write " + file + ": " + e.getMessage());
        }
    }
}
