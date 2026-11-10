package com.betterloka.assistantserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The knowledge base: every {@code .md} and {@code .txt} file under {@code kb/}, read whole and
 * sent with each question (cached by the API, so a repeat costs a tenth or less).
 *
 * <p>Edit a file, add one, or approve a player's answer, and the next question sees it: the folder
 * is looked at again at most every {@link #RECHECK_MILLIS}, no restart needed. The players' approved
 * answers ({@code community.md}) come last.
 */
final class KnowledgeBase {
    static final long RECHECK_MILLIS = 10_000;
    static final String COMMUNITY = "community.md";

    /** The knowledge base as one text, with what it is made of. */
    record Snapshot(String text, int files, long chars, String fingerprint) {
        /** Roughly four characters to a token for English prose. */
        int estimatedTokens() {
            return (int) Math.ceil(chars / 3.6);
        }
    }

    private final Path dir;
    private Snapshot snapshot;
    private String filesKey = "";
    private long checkedAt;

    KnowledgeBase(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    synchronized Snapshot current() {
        long now = System.currentTimeMillis();
        if (snapshot != null && now - checkedAt < RECHECK_MILLIS) {
            return snapshot;
        }
        checkedAt = now;
        List<Path> files = files();
        String key = key(files);
        if (snapshot == null || !key.equals(filesKey)) {
            snapshot = read(files);
            filesKey = key;
            Log.info("Knowledge base: " + snapshot.files() + " files, about " + snapshot.estimatedTokens() + " tokens");
        }
        return snapshot;
    }

    /** Makes the next {@link #current()} look at the folder again at once. */
    synchronized void touch() {
        checkedAt = 0;
    }

    private List<Path> files() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return (name.endsWith(".md") || name.endsWith(".txt")) && !name.startsWith(".");
                    })
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString().equals(COMMUNITY))
                            .thenComparing(path -> dir.relativize(path).toString().replace('\\', '/')))
                    .toList();
        } catch (IOException e) {
            Log.warn("Could not list " + dir + ": " + e.getMessage());
            return List.of();
        }
    }

    private static String key(List<Path> files) {
        StringBuilder key = new StringBuilder();
        for (Path file : files) {
            try {
                key.append(file).append(':').append(Files.size(file)).append(':')
                        .append(Files.getLastModifiedTime(file).toMillis()).append(';');
            } catch (IOException e) {
                key.append(file).append(":?;");
            }
        }
        return key.toString();
    }

    private Snapshot read(List<Path> files) {
        StringBuilder text = new StringBuilder();
        List<String> read = new ArrayList<>();
        for (Path file : files) {
            String body;
            try {
                body = Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                Log.warn("Could not read " + file + ": " + e.getMessage());
                continue;
            }
            if (body.isEmpty()) {
                continue;
            }
            String name = dir.relativize(file).toString().replace('\\', '/');
            text.append("<document path=\"").append(name).append("\">\n").append(body).append("\n</document>\n\n");
            read.add(name);
        }
        String all = text.toString().strip();
        return new Snapshot(all, read.size(), all.length(), fingerprint(all));
    }

    static String fingerprint(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
