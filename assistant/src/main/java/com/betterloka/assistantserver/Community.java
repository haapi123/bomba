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

    /** An open question as it was at one moment, safe to hand to another thread. */
    record OpenView(String id, String question, String kind, String botAnswer, int count) {
    }

    /** A proposal as it was at one moment. */
    record ProposalView(String id, String openId, String question, String answer, String byName,
                        String status, String reviewedBy) {
        boolean pending() {
            return "pending".equals(status);
        }
    }

    /**
     * Told about every change, from the game or from Discord, so both show the same thing. Called
     * while the change is being made: an implementation only queues work, it never waits.
     */
    interface Listener {
        /** A new open question, or one asked again or newly marked wrong. */
        default void opened(OpenView open, boolean isNew) {
        }

        default void proposed(ProposalView proposal) {
        }

        /** Approved or rejected, including the ones closed because another answer was approved. */
        default void decided(ProposalView proposal) {
        }

        /** An open question went away: {@code answered}, {@code dismissed} or {@code expired}. */
        default void closed(String openId, String question, String reason, String by) {
        }
    }

    private static final Listener NOBODY = new Listener() {
    };

    private final Path file;
    private final Path communityFile;
    private final Clock clock;
    private final State state;
    private volatile Listener listener = NOBODY;

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

    /** What was already there when a listener started: it hears about everything after. */
    record Backlog(List<OpenView> open, List<ProposalView> pending) {
    }

    /**
     * Starts telling {@code listener} about changes and returns what is already waiting, in one
     * step, so nothing falls between the two and nothing is reported twice.
     */
    synchronized Backlog listen(Listener listener, int limit) {
        this.listener = listener == null ? NOBODY : listener;
        List<ProposalView> pending = pendingViews();
        return new Backlog(openViews(limit), pending.subList(0, Math.min(limit, pending.size())));
    }

    static OpenView view(OpenQuestion open) {
        return new OpenView(open.id, open.question, open.kind, open.botAnswer, open.count);
    }

    static ProposalView view(Proposal proposal) {
        return new ProposalView(proposal.id, proposal.openId, proposal.question, proposal.answer, proposal.byName,
                proposal.status, proposal.reviewedBy);
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
                listener.opened(view(open), false);
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
        OpenQuestion forgotten = null;
        if (state.open.size() > MAX_OPEN) {
            // Forget the least asked, oldest ones first.
            forgotten = state.open.values().stream()
                    .filter(q -> q != open)
                    .min(Comparator.comparingInt((OpenQuestion q) -> q.count).thenComparingLong(q -> q.lastAsked))
                    .orElse(null);
            if (forgotten != null) {
                state.open.remove(forgotten.id);
            }
        }
        save();
        if (forgotten != null) {
            listener.closed(forgotten.id, forgotten.question, "expired", null);
        }
        listener.opened(view(open), true);
        return open;
    }

    /** Open questions, the most asked first. */
    synchronized List<OpenQuestion> open(int limit) {
        return state.open.values().stream()
                .sorted(Comparator.comparingInt((OpenQuestion q) -> -q.count).thenComparingLong(q -> -q.lastAsked))
                .limit(limit)
                .toList();
    }

    /** The most asked open questions, as views. */
    synchronized List<OpenView> openViews(int limit) {
        return open(limit).stream().map(Community::view).toList();
    }

    synchronized Optional<OpenQuestion> openQuestion(String id) {
        return Optional.ofNullable(state.open.get(id));
    }

    synchronized Optional<OpenView> openView(String id) {
        return Optional.ofNullable(state.open.get(id)).map(Community::view);
    }

    synchronized Optional<ProposalView> proposal(String id) {
        return Optional.ofNullable(state.proposals.get(id)).map(Community::view);
    }

    synchronized List<ProposalView> pendingViews() {
        return pending().stream().map(Community::view).toList();
    }

    synchronized boolean dismiss(String openId) {
        return dismiss(openId, null);
    }

    synchronized boolean dismiss(String openId, String by) {
        OpenQuestion removed = openId == null ? null : state.open.remove(openId);
        if (removed == null) {
            return false;
        }
        save();
        listener.closed(removed.id, removed.question, "dismissed", by);
        return true;
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
        listener.proposed(view(proposal));
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
        List<Proposal> moot = new ArrayList<>();
        OpenQuestion answered = null;
        if (approve) {
            appendToKnowledge(proposal);
            if (proposal.openId != null) {
                answered = state.open.remove(proposal.openId);
            }
            // Other proposals for the same question are now moot.
            for (Proposal other : state.proposals.values()) {
                if ("pending".equals(other.status) && proposal.openId != null && proposal.openId.equals(other.openId)) {
                    other.status = "rejected";
                    other.reviewedBy = admin + " (answered)";
                    moot.add(other);
                }
            }
        }
        listener.decided(view(proposal));
        for (Proposal other : moot) {
            listener.decided(view(other));
        }
        if (answered != null) {
            listener.closed(answered.id, answered.question, "answered", admin);
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
