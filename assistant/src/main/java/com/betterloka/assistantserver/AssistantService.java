package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * What the server does, without the HTTP: logging a player in, answering, taking feedback and
 * proposals, and letting admins approve them. Every method returns the JSON the mod gets and the
 * HTTP status to send it with.
 */
final class AssistantService {
    record Response(int status, JsonObject body) {
    }

    private record Answered(String uuid, String question, String answer) {
    }

    static final String VERSION = "1";
    private static final int RECENT = 1000;

    private final ServerConfig config;
    private final KnowledgeBase knowledge;
    private final Answerer answerer;
    private final Ledger ledger;
    private final Sessions sessions;
    private final MojangCheck mojang;
    private final Community community;
    private final Discord discord;
    private final Path questionLog;
    private final Clock clock;
    private final Semaphore answering;
    private final Map<String, Answered> recent = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Answered> eldest) {
            return size() > RECENT;
        }
    };
    private long nextAnswer = 1;

    AssistantService(ServerConfig config, KnowledgeBase knowledge, Answerer answerer, Ledger ledger,
                     Sessions sessions, MojangCheck mojang, Community community, Discord discord,
                     Path questionLog, Clock clock) {
        this.config = config;
        this.knowledge = knowledge;
        this.answerer = answerer;
        this.ledger = ledger;
        this.sessions = sessions;
        this.mojang = mojang;
        this.community = community;
        this.discord = discord;
        this.questionLog = questionLog;
        this.clock = clock;
        this.answering = new Semaphore(Math.max(1, config.concurrentAnswers), true);
    }

    Response status() {
        JsonObject body = ok();
        body.addProperty("version", VERSION);
        body.addProperty("ready", answerer != null);
        body.addProperty("knowledgeTokens", knowledge.current().estimatedTokens());
        body.addProperty("open", ledger.monthUsd() < config.monthlyBudgetUsd && ledger.dayUsd() < ledger.allowedToday());
        body.addProperty("maxQuestionChars", config.maxQuestionChars);
        body.addProperty("maxProposalChars", config.maxProposalChars);
        return new Response(200, body);
    }

    Response loginStart(JsonObject request) {
        String name = string(request, "name");
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            return error(400, "bad_name", "A Minecraft name is 1-16 letters, digits or _.");
        }
        JsonObject body = ok();
        body.addProperty("serverId", sessions.challenge(name));
        return new Response(200, body);
    }

    Response loginFinish(JsonObject request) {
        String name = string(request, "name");
        String serverId = string(request, "serverId");
        if (name == null || serverId == null || !sessions.redeem(serverId, name)) {
            return error(401, "bad_challenge", "Start the login again.");
        }
        Optional<MojangCheck.Profile> profile;
        try {
            profile = mojang.hasJoined(name, serverId);
        } catch (IOException e) {
            Log.warn("Mojang check failed: " + e.getMessage());
            return error(503, "mojang_down", "Mojang could not be asked; try again in a minute.");
        }
        if (profile.isEmpty()) {
            return error(401, "not_verified", "Mojang did not confirm this player.");
        }
        String uuid = profile.get().uuid();
        String realName = profile.get().name();
        boolean admin = config.isAdmin(uuid, realName);
        JsonObject body = ok();
        body.addProperty("token", sessions.ticket(uuid, realName));
        body.addProperty("expiresAt", sessions.ticketExpiresAtMillis());
        body.addProperty("uuid", uuid);
        body.addProperty("name", realName);
        body.addProperty("admin", admin);
        body.addProperty("questionsLeft", Math.min(ledger.questionsLeft(uuid, admin), 9999));
        Log.info("Login: " + realName + (admin ? " (admin)" : ""));
        return new Response(200, body);
    }

    Response me(Sessions.Session session) {
        boolean admin = admin(session);
        JsonObject body = ok();
        body.addProperty("name", session.name());
        body.addProperty("admin", admin);
        body.addProperty("questionsLeft", Math.min(ledger.questionsLeft(session.uuid(), admin), 9999));
        return new Response(200, body);
    }

    Response ask(Sessions.Session session, JsonObject request) {
        if (answerer == null) {
            return error(503, "no_key", "The assistant has no Claude API key yet.");
        }
        String question = string(request, "question");
        if (question == null || question.isBlank()) {
            return error(400, "empty", "Ask a question.");
        }
        question = question.strip();
        if (question.length() > config.maxQuestionChars) {
            return error(400, "too_long", "At most " + config.maxQuestionChars + " characters.");
        }
        boolean admin = admin(session);
        switch (ledger.canAsk(session.uuid(), admin)) {
            case PLAYER_LIMIT:
                return error(429, "player_limit", "No questions left today.");
            case BUDGET_DAY:
                return error(503, "budget_day", "The assistant is resting until tomorrow.");
            case BUDGET_MONTH:
                return error(503, "budget_month", "The assistant is resting until next month.");
            default:
                break;
        }
        String language = string(request, "lang");
        if (language == null || !language.matches("[a-z]{2,3}(_[a-z0-9]{2,4})?")) {
            language = "en_us";
        }
        List<Answerer.Turn> history = history(request);

        boolean acquired;
        try {
            acquired = answering.tryAcquire(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(503, "busy", "Busy, try again.");
        }
        if (!acquired) {
            return error(503, "busy", "Many players are asking; try again in a moment.");
        }
        Answerer.Reply reply;
        try {
            reply = answerer.answer(new Answerer.Question(knowledge.current().text(), history, question, language,
                    userId(session.uuid())));
        } catch (Answerer.Failure e) {
            Log.warn("No answer for " + session.name() + ": " + e.getMessage());
            return error(503, e.busy() ? "busy" : "upstream", "The assistant could not answer right now.");
        } finally {
            answering.release();
        }

        ledger.recordQuestion(session.uuid(), reply.costUsd());
        String id;
        synchronized (recent) {
            id = "a" + Long.toString(clock.millis(), 36) + Long.toString(nextAnswer++, 36);
            recent.put(id, new Answered(session.uuid(), question, reply.text()));
        }
        log(session, id, language, question, reply);

        if (reply.status() == Answerer.Status.UNKNOWN) {
            Community.OpenQuestion open = community.noteOpen(question, "unknown", null);
            if (open.count == 1) {
                discord.post("❓ The assistant did not know: \"" + Discord.quote(question) + "\"");
            }
        }

        JsonObject body = ok();
        body.addProperty("id", id);
        body.addProperty("answer", reply.text());
        body.addProperty("status", reply.status().name().toLowerCase(Locale.ROOT));
        body.addProperty("questionsLeft", Math.min(ledger.questionsLeft(session.uuid(), admin), 9999));
        return new Response(200, body);
    }

    /** A thumbs-down turns the question into an open one, so a player can write the right answer. */
    Response feedback(Sessions.Session session, JsonObject request) {
        String id = string(request, "id");
        Answered answered;
        synchronized (recent) {
            answered = id == null ? null : recent.get(id);
            if (answered == null || !answered.uuid().equals(session.uuid())) {
                return error(404, "unknown_answer", "That answer is too old to rate.");
            }
            // One rating per answer.
            recent.remove(id);
        }
        boolean good = request.has("good") && request.get("good").getAsBoolean();
        appendLog(feedbackLine(session, id, good));
        if (!good) {
            Community.OpenQuestion open = community.noteOpen(answered.question(), "disputed", answered.answer());
            if (open.count == 1) {
                discord.post("👎 " + session.name() + " says this answer is wrong: \"" + Discord.quote(answered.question())
                        + "\" → \"" + Discord.quote(answered.answer()) + "\"");
            }
        }
        return new Response(200, ok());
    }

    Response open() {
        JsonArray list = new JsonArray();
        for (Community.OpenQuestion open : community.open(60)) {
            JsonObject item = new JsonObject();
            item.addProperty("id", open.id);
            item.addProperty("question", open.question);
            item.addProperty("kind", open.kind);
            if (open.botAnswer != null) {
                item.addProperty("botAnswer", open.botAnswer);
            }
            item.addProperty("count", open.count);
            item.addProperty("lastAsked", open.lastAsked);
            list.add(item);
        }
        JsonObject body = ok();
        body.add("questions", list);
        return new Response(200, body);
    }

    Response propose(Sessions.Session session, JsonObject request) {
        String openId = string(request, "openId");
        String question = string(request, "question");
        String answer = string(request, "answer");
        if (openId != null) {
            Optional<Community.OpenQuestion> open = community.openQuestion(openId);
            if (open.isEmpty()) {
                return error(404, "unknown_question", "Someone has answered that one already.");
            }
            question = open.get().question;
        }
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return error(400, "empty", "Write the question and the answer.");
        }
        if (question.strip().length() > config.maxQuestionChars) {
            return error(400, "too_long", "The question is too long.");
        }
        if (answer.strip().length() > config.maxProposalChars) {
            return error(400, "too_long", "At most " + config.maxProposalChars + " characters.");
        }
        boolean admin = admin(session);
        if (!ledger.tryProposal(session.uuid(), admin)) {
            return error(429, "proposal_limit", "No more proposals today.");
        }
        Community.Proposal proposal = community.propose(openId, question.strip(), answer.strip(), session.uuid(), session.name());
        discord.post("📝 " + session.name() + " proposes an answer to \"" + Discord.quote(proposal.question) + "\": \""
                + Discord.quote(proposal.answer) + "\" (approve it in the game: Loka Assistant → Review)");
        JsonObject body = ok();
        body.addProperty("id", proposal.id);
        return new Response(200, body);
    }

    Response pending(Sessions.Session session) {
        if (!admin(session)) {
            return error(403, "not_admin", "Only the assistant's admins can review.");
        }
        JsonArray list = new JsonArray();
        for (Community.Proposal proposal : community.pending()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", proposal.id);
            item.addProperty("question", proposal.question);
            item.addProperty("answer", proposal.answer);
            item.addProperty("by", proposal.byName);
            item.addProperty("time", proposal.time);
            list.add(item);
        }
        JsonObject body = ok();
        body.add("proposals", list);
        return new Response(200, body);
    }

    Response review(Sessions.Session session, JsonObject request) {
        if (!admin(session)) {
            return error(403, "not_admin", "Only the assistant's admins can review.");
        }
        String id = string(request, "id");
        boolean approve = request.has("approve") && request.get("approve").getAsBoolean();
        Optional<Community.Proposal> reviewed = community.review(id, approve, string(request, "question"),
                string(request, "answer"), session.name());
        if (reviewed.isEmpty()) {
            return error(404, "unknown_proposal", "That proposal was already reviewed.");
        }
        if (approve) {
            knowledge.touch();
            Log.info(session.name() + " approved an answer to: " + reviewed.get().question);
        }
        return new Response(200, ok());
    }

    Response dismiss(Sessions.Session session, JsonObject request) {
        if (!admin(session)) {
            return error(403, "not_admin", "Only the assistant's admins can review.");
        }
        return community.dismiss(string(request, "id")) ? new Response(200, ok())
                : error(404, "unknown_question", "Already gone.");
    }

    Optional<Sessions.Session> session(String ticket) {
        return sessions.verify(ticket);
    }

    private boolean admin(Sessions.Session session) {
        return config.isAdmin(session.uuid(), session.name());
    }

    private List<Answerer.Turn> history(JsonObject request) {
        List<Answerer.Turn> turns = new ArrayList<>();
        JsonElement element = request.get("history");
        if (element == null || !element.isJsonArray()) {
            return turns;
        }
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            String q = string(item.getAsJsonObject(), "q");
            String a = string(item.getAsJsonObject(), "a");
            if (q != null && a != null && !q.isBlank() && !a.isBlank()) {
                turns.add(new Answerer.Turn(cut(q.strip(), config.maxQuestionChars), cut(a.strip(), 1500)));
            }
        }
        int keep = Math.max(0, config.historyTurns);
        return turns.size() > keep ? new ArrayList<>(turns.subList(turns.size() - keep, turns.size())) : turns;
    }

    private void log(Sessions.Session session, String id, String language, String question, Answerer.Reply reply) {
        JsonObject line = new JsonObject();
        line.addProperty("time", clock.instant().toString());
        line.addProperty("id", id);
        line.addProperty("player", session.name());
        line.addProperty("uuid", session.uuid());
        line.addProperty("lang", language);
        line.addProperty("question", question);
        line.addProperty("answer", reply.text());
        line.addProperty("status", reply.status().name().toLowerCase(Locale.ROOT));
        line.addProperty("model", reply.model());
        line.addProperty("costUsd", Math.round(reply.costUsd() * 1_000_000) / 1_000_000.0);
        line.addProperty("cacheRead", reply.usage().cacheRead());
        line.addProperty("cacheWrite", reply.usage().cacheWrite5m() + reply.usage().cacheWrite1h());
        line.addProperty("input", reply.usage().input());
        line.addProperty("output", reply.usage().output());
        appendLog(line);
        Log.info(String.format(Locale.ROOT, "%s asked (%s, $%.4f, month $%.2f/%.0f): %s", session.name(),
                reply.status().name().toLowerCase(Locale.ROOT), reply.costUsd(), ledger.monthUsd(),
                config.monthlyBudgetUsd, cut(question, 80)));
    }

    private JsonObject feedbackLine(Sessions.Session session, String id, boolean good) {
        JsonObject line = new JsonObject();
        line.addProperty("time", clock.instant().toString());
        line.addProperty("feedback", id);
        line.addProperty("player", session.name());
        line.addProperty("good", good);
        return line;
    }

    private synchronized void appendLog(JsonObject line) {
        try {
            Files.createDirectories(questionLog.toAbsolutePath().getParent());
            Files.writeString(questionLog, Json.COMPACT.toJson(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.warn("Could not write " + questionLog + ": " + e.getMessage());
        }
    }

    /** An id for the API that says which player without saying who. */
    static String userId(String uuid) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("betterloka:" + uuid).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    static String string(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    static JsonObject ok() {
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        return body;
    }

    static Response error(int status, String code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error", code);
        body.addProperty("message", message);
        return new Response(status, body);
    }
}
