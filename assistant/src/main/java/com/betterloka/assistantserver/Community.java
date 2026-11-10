package com.betterloka.assistantserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How players teach the assistant. A question it could not answer (or an answer a player marked
 * wrong) becomes an open question; any player may propose an answer; an admin approves it, and only
 * then does it go into {@code kb/community.md}. Nothing a player writes reaches the knowledge base
 * on its own: one troll could otherwise teach every player something false.
 */
final class Community {
    static final int MAX_OPEN = 200;

    static final class OpenQuestion {
        String id;
        String question;
        /** {@code unknown}: the assistant did not know; {@code disputed}: a player said it was wrong. */
        String kind;
        /** For a disputed one, what the assistant said. */
        String botAnswer;
        int count;
        long firstAsked;
        long lastAsked;
    }

    static final class Proposal {
        String id;
        String openId;
        String question;
        String answer;
        String byUuid;
        String byName;
        long time;
        /** pending, approved, rejected */
        String status;
        String reviewedBy;
    }

    static final class State {
        long nextId = 1;
        Map<String, OpenQuestion> open = new LinkedHashMap<>();
        Map<String, Proposal> proposals = new LinkedHashMap<>();
    }

    private final Path file;
    private final Path communityFile;
    private final Clock clock;
    private final State state;

    Community(Path file, Path communityFile, Clock clock) {
        this.file = file;
        this.communityFile = communityFile;
        this.clock = clock;
        State loaded = Json.read(file, State.class, State::new);
        if (loaded.open == null) {
            loaded.open = new LinkedHashMap<>();
        }
        if (loaded.proposals == null) {
            loaded.proposals = new LinkedHashMap<>();
        }
        this.state = loaded;
    }

    /** Notes a question the assistant could not answer; the same question asked again counts up. */
    synchronized OpenQuestion noteOpen(String question, String kind, String botAnswer) {
        String key = normalize(question);
        long now = clock.millis();
        for (OpenQuestion open : state.open.values()) {
            if (normalize(open.question).equals(key)) {
                open.count++;
                open.lastAsked = now;
                if ("disputed".equals(kind)) {
                    open.kind = kind;
                    open.botAnswer = botAnswer;
                }
                save();
                return open;
            }
        }
        OpenQuestion open = new OpenQuestion();
        open.id = "q" + state.nextId++;
        open.question = question;
        open.kind = kind;
        open.botAnswer = botAnswer;
        open.count = 1;
        open.firstAsked = now;
        open.lastAsked = now;
        state.open.put(open.id, open);
        if (state.open.size() > MAX_OPEN) {
            // Forget the least asked, oldest ones first.
            state.open.values().stream()
                    .min(Comparator.comparingInt((OpenQuestion q) -> q.count).thenComparingLong(q -> q.lastAsked))
                    .ifPresent(oldest -> state.open.remove(oldest.id));
        }
        save();
        return open;
    }

    /** Open questions, the most asked first. */
    synchronized List<OpenQuestion> open(int limit) {
        return state.open.values().stream()
                .sorted(Comparator.comparingInt((OpenQuestion q) -> -q.count).thenComparingLong(q -> -q.lastAsked))
                .limit(limit)
                .toList();
    }

    synchronized Optional<OpenQuestion> openQuestion(String id) {
        return Optional.ofNullable(state.open.get(id));
    }

    synchronized boolean dismiss(String openId) {
        boolean removed = state.open.remove(openId) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    synchronized Proposal propose(String openId, String question, String answer, String byUuid, String byName) {
        Proposal proposal = new Proposal();
        proposal.id = "p" + state.nextId++;
        proposal.openId = openId;
        proposal.question = question;
        proposal.answer = answer;
        proposal.byUuid = byUuid;
        proposal.byName = byName;
        proposal.time = clock.millis();
        proposal.status = "pending";
        state.proposals.put(proposal.id, proposal);
        save();
        return proposal;
    }

    synchronized List<Proposal> pending() {
        return state.proposals.values().stream().filter(p -> "pending".equals(p.status)).toList();
    }

    /**
     * Approves (writing the answer into {@code community.md}, with the admin's edits if any) or
     * rejects a proposal. Approving also closes the open question it answered.
     */
    synchronized Optional<Proposal> review(String id, boolean approve, String question, String answer, String admin) {
        Proposal proposal = state.proposals.get(id);
        if (proposal == null || !"pending".equals(proposal.status)) {
            return Optional.empty();
        }
        if (question != null && !question.isBlank()) {
            proposal.question = question.strip();
        }
        if (answer != null && !answer.isBlank()) {
            proposal.answer = answer.strip();
        }
        proposal.status = approve ? "approved" : "rejected";
        proposal.reviewedBy = admin;
        if (approve) {
            appendToKnowledge(proposal);
            if (proposal.openId != null) {
                state.open.remove(proposal.openId);
            }
            // Other proposals for the same question are now moot.
            for (Proposal other : state.proposals.values()) {
                if ("pending".equals(other.status) && proposal.openId != null && proposal.openId.equals(other.openId)) {
                    other.status = "rejected";
                    other.reviewedBy = admin + " (answered)";
                }
            }
        }
        // Keep only recent decisions; pending ones always stay.
        List<Proposal> decided = new ArrayList<>(state.proposals.values().stream()
                .filter(p -> !"pending".equals(p.status)).toList());
        while (decided.size() > 500) {
            state.proposals.remove(decided.remove(0).id);
        }
        save();
        return Optional.of(proposal);
    }

    private void appendToKnowledge(Proposal proposal) {
        try {
            Files.createDirectories(communityFile.toAbsolutePath().getParent());
            StringBuilder entry = new StringBuilder();
            if (!Files.exists(communityFile)) {
                entry.append("# Community answers\n\n")
                        .append("Answers players wrote and the BetterLoka team approved. Newer entries come later.\n");
            }
            entry.append("\n## ").append(oneLine(proposal.question)).append("\n")
                    .append(proposal.answer.strip()).append("\n")
                    .append("_Approved ").append(LocalDate.now(clock)).append(", written by ")
                    .append(oneLine(proposal.byName)).append("._\n");
            Files.writeString(communityFile, entry.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.warn("Could not write " + communityFile + ": " + e.getMessage());
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** The same question however it is typed: lower case, no accents or punctuation. */
    static String normalize(String question) {
        String folded = Normalizer.normalize(question.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l');
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private void save() {
        Json.write(file, state);
    }
}
