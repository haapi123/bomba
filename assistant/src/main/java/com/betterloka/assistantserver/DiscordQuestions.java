package com.betterloka.assistantserver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * "Help it learn" on Discord. Every question the assistant could not answer (or a player marked
 * wrong) is posted to one channel with an Answer button; people with an answering role write the
 * answer in a form, and people with an approving role approve it, edit it or reject it. The same
 * questions and proposals are in the game, and whichever side acts, the messages follow.
 *
 * <p>Who may do what is decided by the roles Discord itself reports with each click, never by
 * anything a user typed, and only clicks in the configured channel count. Approvers' own answers go
 * straight in; everyone else's wait, so a role handed out too freely cannot teach the assistant
 * anything on its own.
 */
final class DiscordQuestions implements Community.Listener {
    static final String PREFIX = "bla:";
    private static final BigInteger ADMINISTRATOR = BigInteger.valueOf(0x8);
    /** How many earlier questions and proposals are posted when the channel is first set up. */
    private static final int BACKLOG = 20;
    private static final int MAX_REMEMBERED = 1000;
    /** A message somebody deleted by hand: not posted again. */
    private static final String GONE = "-";

    private static final int COLOR_OPEN = 0xF0B232;
    private static final int COLOR_DISPUTED = 0xE0605C;
    private static final int COLOR_PROPOSAL = 0x5B8DEF;
    private static final int COLOR_DONE = 0x5FD37A;
    private static final int COLOR_CLOSED = 0x8A8F98;

    static final class State {
        Map<String, String> questions = new LinkedHashMap<>();
        Map<String, String> proposals = new LinkedHashMap<>();
    }

    /** Who clicked, as Discord reports it. */
    record Member(String userId, String name, Set<String> roles, boolean administrator) {
        static Member of(JsonObject interaction) {
            JsonObject member = object(interaction, "member");
            if (member == null) {
                return null;
            }
            JsonObject user = object(member, "user");
            String userId = user == null ? null : string(user, "id");
            if (userId == null) {
                return null;
            }
            Set<String> roles = new HashSet<>();
            if (member.has("roles") && member.get("roles").isJsonArray()) {
                for (JsonElement role : member.getAsJsonArray("roles")) {
                    roles.add(role.getAsString());
                }
            }
            boolean administrator = false;
            String permissions = string(member, "permissions");
            if (permissions != null && permissions.matches("\\d+")) {
                administrator = new BigInteger(permissions).and(ADMINISTRATOR).signum() != 0;
            }
            String name = firstNonBlank(string(member, "nick"), string(user, "global_name"), string(user, "username"), userId);
            return new Member(userId, name, roles, administrator);
        }
    }

    private final ServerConfig config;
    private final ServerConfig.DiscordQuestions settings;
    private final Community community;
    private final Ledger ledger;
    private final KnowledgeBase knowledge;
    private final DiscordRest rest;
    private final Path stateFile;
    private final State state;
    private final Map<String, String> text;
    private final ThreadPoolExecutor outbox = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(500), runnable -> daemon(runnable, "discord-questions-out"),
            (task, pool) -> Log.warn("Discord questions: too much to post at once, one update dropped"));
    private final ExecutorService inbox = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "discord-questions-in"));

    DiscordQuestions(ServerConfig config, Community community, Ledger ledger, KnowledgeBase knowledge,
                     DiscordRest rest, Path stateFile) {
        this.config = config;
        this.settings = config.discordQuestions;
        this.community = community;
        this.ledger = ledger;
        this.knowledge = knowledge;
        this.rest = rest;
        this.stateFile = stateFile;
        State loaded = Json.read(stateFile, State.class, State::new);
        if (loaded.questions == null) {
            loaded.questions = new LinkedHashMap<>();
        }
        if (loaded.proposals == null) {
            loaded.proposals = new LinkedHashMap<>();
        }
        this.state = loaded;
        this.text = "en".equalsIgnoreCase(settings.language) ? EN : PL;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Starts following the community's changes and posts what is already waiting. */
    void start() {
        Community.Backlog backlog = community.listen(this, BACKLOG);
        outbox.execute(() -> postBacklog(backlog));
    }

    void stop() {
        community.listen(null, 0);
        outbox.shutdown();
        inbox.shutdown();
    }

    /** Waits for everything queued so far; the tests use it. */
    void flush() throws Exception {
        inbox.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
        outbox.submit(() -> {
        }).get(10, TimeUnit.SECONDS);
    }

    // ---- From the community: keep the channel in step -------------------------------------------

    @Override
    public void opened(Community.OpenView open, boolean isNew) {
        outbox.execute(() -> showQuestion(open, isNew));
    }

    @Override
    public void proposed(Community.ProposalView proposal) {
        outbox.execute(() -> showProposal(proposal));
    }

    @Override
    public void decided(Community.ProposalView proposal) {
        outbox.execute(() -> showProposal(proposal));
    }

    @Override
    public void closed(String openId, String question, String reason, String by) {
        outbox.execute(() -> closeQuestion(openId, question, reason, by));
    }

    /** Questions and proposals from before the channel was set up (or while it was offline), once. */
    private void postBacklog(Community.Backlog backlog) {
        for (Community.OpenView open : backlog.open()) {
            if (remembered(state.questions, open.id()) == null) {
                showQuestion(open, false);
            }
        }
        for (Community.ProposalView proposal : backlog.pending()) {
            if (remembered(state.proposals, proposal.id()) == null) {
                showProposal(proposal);
            }
        }
    }

    private void showQuestion(Community.OpenView open, boolean isNew) {
        String messageId = remembered(state.questions, open.id());
        if (GONE.equals(messageId)) {
            return;
        }
        try {
            if (messageId == null) {
                boolean ping = isNew && !settings.pingRoleId.isBlank();
                remember(state.questions, open.id(), rest.post(settings.channelId, questionMessage(open, ping)));
            } else {
                rest.edit(settings.channelId, messageId, questionMessage(open, false));
            }
        } catch (IOException | InterruptedException e) {
            failed(state.questions, open.id(), messageId, e);
        }
    }

    private void closeQuestion(String openId, String question, String reason, String by) {
        String messageId = forget(state.questions, openId);
        if (messageId == null || GONE.equals(messageId)) {
            return;
        }
        try {
            rest.edit(settings.channelId, messageId, closedQuestionMessage(openId, question, reason, by));
        } catch (IOException | InterruptedException e) {
            failed(null, openId, messageId, e);
        }
    }

    private void showProposal(Community.ProposalView proposal) {
        String messageId = remembered(state.proposals, proposal.id());
        if (GONE.equals(messageId)) {
            return;
        }
        try {
            if (proposal.pending()) {
                if (messageId == null) {
                    remember(state.proposals, proposal.id(), rest.post(settings.channelId, proposalMessage(proposal)));
                } else {
                    rest.edit(settings.channelId, messageId, proposalMessage(proposal));
                }
            } else if (messageId != null) {
                forget(state.proposals, proposal.id());
                rest.edit(settings.channelId, messageId, proposalMessage(proposal));
            }
        } catch (IOException | InterruptedException e) {
            failed(proposal.pending() ? state.proposals : null, proposal.id(), messageId, e);
        }
    }

    private void failed(Map<String, String> map, String key, String messageId, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        if (map != null && messageId != null && e.getMessage() != null && e.getMessage().contains(" 404")) {
            // Deleted by hand: respect that rather than posting it again.
            remember(map, key, GONE);
            return;
        }
        Log.warn("Discord questions: " + e.getMessage());
    }

    private String remembered(Map<String, String> map, String key) {
        synchronized (state) {
            return map.get(key);
        }
    }

    private void remember(Map<String, String> map, String key, String messageId) {
        synchronized (state) {
            map.put(key, messageId);
            while (map.size() > MAX_REMEMBERED) {
                map.remove(map.keySet().iterator().next());
            }
            Json.write(stateFile, state);
        }
    }

    private String forget(Map<String, String> map, String key) {
        synchronized (state) {
            String removed = map.remove(key);
            if (removed != null) {
                Json.write(stateFile, state);
            }
            return removed;
        }
    }

    // ---- Messages ---------------------------------------------------------------------------------

    JsonObject questionMessage(Community.OpenView open, boolean ping) {
        boolean disputed = "disputed".equals(open.kind());
        JsonObject embed = embed(t(disputed ? "q_disputed" : "q_unknown"), disputed ? COLOR_DISPUTED : COLOR_OPEN,
                block(open.question(), config.maxQuestionChars), "Loka Assistant · " + open.id() + " · " + t("q_footer"));
        JsonArray fields = new JsonArray();
        fields.add(field(t("asked"), open.count() + "×", true));
        if (disputed && open.botAnswer() != null) {
            fields.add(field(t("bot_answer"), block(open.botAnswer(), 950), false));
        }
        embed.add("fields", fields);
        JsonObject message = message(embed, row(
                button(1, t("btn_answer"), "answer:" + open.id()),
                button(4, t("btn_dismiss"), "dismiss:" + open.id())));
        if (ping) {
            message.addProperty("content", "<@&" + settings.pingRoleId + ">");
            message.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").add(settings.pingRoleId);
        }
        return message;
    }

    JsonObject closedQuestionMessage(String openId, String question, String reason, String by) {
        String who = by == null || by.isBlank() ? "admin" : by;
        String title = switch (reason) {
            case "answered" -> t("q_answered", who);
            case "dismissed" -> t("q_dismissed", who);
            default -> t("q_expired");
        };
        int color = "answered".equals(reason) ? COLOR_DONE : COLOR_CLOSED;
        return message(embed(title, color, block(question, config.maxQuestionChars), "Loka Assistant · " + openId), null);
    }

    JsonObject proposalMessage(Community.ProposalView proposal) {
        String description = "**" + t("p_question") + "**\n" + block(proposal.question(), config.maxQuestionChars)
                + "\n**" + t("p_answer") + "**\n" + block(proposal.answer(), config.maxProposalChars);
        String footer = "Loka Assistant · " + proposal.id() + (proposal.openId() == null ? "" : " · " + proposal.openId());
        if (proposal.pending()) {
            return message(embed(t("p_title", plain(proposal.byName())), COLOR_PROPOSAL, description, footer), row(
                    button(3, t("btn_approve"), "approve:" + proposal.id()),
                    button(2, t("btn_edit"), "edit:" + proposal.id()),
                    button(4, t("btn_reject"), "reject:" + proposal.id())));
        }
        String by = plain(proposal.reviewedBy());
        if ("approved".equals(proposal.status())) {
            return message(embed(t("p_approved", plain(proposal.byName()), by), COLOR_DONE, description, footer), null);
        }
        String title = by.endsWith(" (answered)") ? t("p_superseded", plain(proposal.byName()))
                : t("p_rejected", plain(proposal.byName()), by);
        return message(embed(title, COLOR_CLOSED, description, footer), null);
    }

    private static JsonObject message(JsonObject embed, JsonObject row) {
        JsonObject message = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        message.add("embeds", embeds);
        JsonArray components = new JsonArray();
        if (row != null) {
            components.add(row);
        }
        // An empty list takes the buttons off a message that is done with.
        message.add("components", components);
        message.add("allowed_mentions", noMentions());
        return message;
    }

    private static JsonObject noMentions() {
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        allowed.add("roles", new JsonArray());
        return allowed;
    }

    private static JsonObject embed(String title, int color, String description, String footer) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", clip(title, 250));
        embed.addProperty("color", color);
        embed.addProperty("description", clip(description, 4000));
        JsonObject foot = new JsonObject();
        foot.addProperty("text", clip(footer, 2000));
        embed.add("footer", foot);
        return embed;
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", clip(value, 1024));
        field.addProperty("inline", inline);
        return field;
    }

    private static JsonObject row(JsonObject... buttons) {
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        for (JsonObject button : buttons) {
            components.add(button);
        }
        row.add("components", components);
        return row;
    }

    /** Styles: 1 primary, 2 secondary, 3 green, 4 red. */
    private static JsonObject button(int style, String label, String action) {
        JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("style", style);
        button.addProperty("label", clip(label, 80));
        button.addProperty("custom_id", PREFIX + action);
        return button;
    }

    /** Text a player wrote, shown inert: inside a code block nothing pings, links or formats. */
    static String block(String text, int max) {
        String inert = text == null ? "" : text.replace("`", "'").strip();
        return "```\n" + clip(inert, Math.max(1, max)) + "\n```";
    }

    /** A name for a title: one line, nothing that pings. */
    private static String plain(String text) {
        return text == null ? "" : clip(text.replaceAll("\\s+", " ").replace("@", "@​").replace("`", "'").strip(), 60);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }

    // ---- Clicks and forms -------------------------------------------------------------------------

    /** Takes a button click or a form sent from one of these messages; leaves everything else alone. */
    boolean handle(JsonObject interaction) {
        int type = interaction.has("type") ? interaction.get("type").getAsInt() : 0;
        if (type != 3 && type != 5) {
            return false;
        }
        JsonObject data = object(interaction, "data");
        String customId = data == null ? null : string(data, "custom_id");
        if (customId == null || !customId.startsWith(PREFIX)) {
            return false;
        }
        inbox.execute(() -> {
            try {
                rest.respond(string(interaction, "id"), string(interaction, "token"), decide(interaction, customId));
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Log.warn("Discord questions: could not answer a click: " + e.getMessage());
            }
        });
        return true;
    }

    /** What to answer a click or a form with; does whatever it asked for first. */
    JsonObject decide(JsonObject interaction, String customId) {
        String channel = string(interaction, "channel_id");
        if (channel == null && object(interaction, "channel") != null) {
            channel = string(object(interaction, "channel"), "id");
        }
        if (!settings.channelId.equals(channel)) {
            return reply("wrong_channel");
        }
        Member member = Member.of(interaction);
        if (member == null) {
            return reply("no_role");
        }
        String rest = customId.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        String action = colon < 0 ? rest : rest.substring(0, colon);
        String target = colon < 0 ? "" : rest.substring(colon + 1);
        JsonObject data = object(interaction, "data");

        switch (action) {
            case "answer" -> {
                if (!canAnswer(member)) {
                    return reply("no_role");
                }
                Optional<Community.OpenView> open = community.openView(target);
                if (open.isEmpty()) {
                    return reply("closed");
                }
                return form("answer-form:" + target, t("modal_answer"), open.get().question(), "");
            }
            case "answer-form" -> {
                if (!canAnswer(member)) {
                    return reply("no_role");
                }
                Optional<Community.OpenView> open = community.openView(target);
                if (open.isEmpty()) {
                    return reply("closed");
                }
                Map<String, String> values = formValues(data);
                return saveAnswer(member, open.get(), values.get("question"), values.get("answer"));
            }
            case "dismiss" -> {
                if (!canApprove(member)) {
                    return reply("no_approve");
                }
                return community.dismiss(target, displayName(member)) ? reply("dismissed") : reply("closed");
            }
            case "approve", "reject" -> {
                if (!canApprove(member)) {
                    return reply("no_approve");
                }
                return review(member, target, "approve".equals(action), null, null);
            }
            case "edit" -> {
                if (!canApprove(member)) {
                    return reply("no_approve");
                }
                Optional<Community.ProposalView> proposal = community.proposal(target);
                if (proposal.isEmpty() || !proposal.get().pending()) {
                    return reply("proposal_gone");
                }
                return form("edit-form:" + target, t("modal_edit"), proposal.get().question(), proposal.get().answer());
            }
            case "edit-form" -> {
                if (!canApprove(member)) {
                    return reply("no_approve");
                }
                Map<String, String> values = formValues(data);
                String question = values.getOrDefault("question", "").strip();
                String answer = values.getOrDefault("answer", "").strip();
                if (question.isEmpty() || answer.isEmpty()) {
                    return reply("empty");
                }
                if (question.length() > config.maxQuestionChars || answer.length() > config.maxProposalChars) {
                    return reply("too_long", config.maxQuestionChars, config.maxProposalChars);
                }
                return review(member, target, true, question, answer);
            }
            default -> {
                return reply("failed");
            }
        }
    }

    private JsonObject saveAnswer(Member member, Community.OpenView open, String questionText, String answerText) {
        String question = questionText == null || questionText.isBlank() ? open.question() : questionText.strip();
        String answer = answerText == null ? "" : answerText.strip();
        if (answer.isEmpty()) {
            return reply("empty");
        }
        if (question.length() > config.maxQuestionChars || answer.length() > config.maxProposalChars) {
            return reply("too_long", config.maxQuestionChars, config.maxProposalChars);
        }
        boolean approver = canApprove(member);
        if (!ledger.tryProposal("discord:" + member.userId(), approver)) {
            return reply("limit");
        }
        String name = displayName(member);
        Community.Proposal proposal = community.propose(open.id(), question, answer, "discord:" + member.userId(), name);
        if (approver) {
            community.review(proposal.id, true, null, null, name);
            knowledge.touch();
            Log.info(name + " answered on Discord, straight into the knowledge base: " + question);
            return reply("saved_direct");
        }
        Log.info(name + " proposed an answer on Discord: " + question);
        return reply("saved_pending");
    }

    private JsonObject review(Member member, String proposalId, boolean approve, String question, String answer) {
        String name = displayName(member);
        Optional<Community.Proposal> reviewed = community.review(proposalId, approve, question, answer, name);
        if (reviewed.isEmpty()) {
            return reply("proposal_gone");
        }
        if (approve) {
            knowledge.touch();
            Log.info(name + " approved on Discord an answer to: " + reviewed.get().question);
        }
        return reply(approve ? "approved" : "rejected");
    }

    private boolean canAnswer(Member member) {
        return canApprove(member) || member.roles().stream().anyMatch(settings.answerRoleIds::contains);
    }

    private boolean canApprove(Member member) {
        return member.administrator() || member.roles().stream().anyMatch(settings.approveRoleIds::contains);
    }

    private static String displayName(Member member) {
        return clip(member.name().replaceAll("\\s+", " ").strip(), 40) + " (Discord)";
    }

    private JsonObject form(String action, String title, String question, String answer) {
        JsonArray rows = new JsonArray();
        rows.add(inputRow(input("question", t("field_question"), 1, 1, config.maxQuestionChars, question, null)));
        rows.add(inputRow(input("answer", t("field_answer"), 2, 2, config.maxProposalChars, answer, t("answer_hint"))));
        JsonObject data = new JsonObject();
        data.addProperty("custom_id", PREFIX + action);
        data.addProperty("title", clip(title, 45));
        data.add("components", rows);
        JsonObject response = new JsonObject();
        response.addProperty("type", 9);
        response.add("data", data);
        return response;
    }

    private static JsonObject inputRow(JsonObject input) {
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        components.add(input);
        row.add("components", components);
        return row;
    }

    /** Styles: 1 one line, 2 a paragraph. */
    private static JsonObject input(String id, String label, int style, int min, int max, String value, String hint) {
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", id);
        input.addProperty("label", clip(label, 45));
        input.addProperty("style", style);
        input.addProperty("min_length", min);
        input.addProperty("max_length", Math.max(min, Math.min(4000, max)));
        input.addProperty("required", true);
        if (value != null && !value.isBlank()) {
            input.addProperty("value", clip(value.strip(), Math.min(4000, max)));
        }
        if (hint != null) {
            input.addProperty("placeholder", clip(hint, 100));
        }
        return input;
    }

    /** What was typed into a form, by field id, wherever Discord nested it. */
    static Map<String, String> formValues(JsonObject data) {
        Map<String, String> values = new LinkedHashMap<>();
        collect(data == null ? null : data.get("components"), values);
        return values;
    }

    private static void collect(JsonElement element, Map<String, String> values) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement each : element.getAsJsonArray()) {
                collect(each, values);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String id = string(object, "custom_id");
        String value = string(object, "value");
        if (id != null && value != null) {
            values.put(id, value);
        }
        collect(object.get("components"), values);
        collect(object.get("component"), values);
    }

    private JsonObject reply(String key, Object... args) {
        JsonObject data = new JsonObject();
        data.addProperty("content", t(key, args));
        data.addProperty("flags", 64);
        data.add("allowed_mentions", noMentions());
        JsonObject response = new JsonObject();
        response.addProperty("type", 4);
        response.add("data", data);
        return response;
    }

    private String t(String key, Object... args) {
        String pattern = text.getOrDefault(key, key);
        return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull() || !parent.get(key).isJsonPrimitive()) {
            return null;
        }
        return parent.get(key).getAsString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "?";
    }

    private static final Map<String, String> PL = Map.ofEntries(
            Map.entry("q_unknown", "❓ Asystent nie znał odpowiedzi"),
            Map.entry("q_disputed", "👎 Gracz zgłosił złą odpowiedź"),
            Map.entry("q_footer", "odpowiedz przyciskiem, a asystent się tego nauczy"),
            Map.entry("asked", "Zapytano"),
            Map.entry("bot_answer", "Co odpowiedział asystent"),
            Map.entry("btn_answer", "Odpowiedz"),
            Map.entry("btn_dismiss", "Odrzuć pytanie"),
            Map.entry("q_answered", "✅ Odpowiedziano (zatwierdził: %s)"),
            Map.entry("q_dismissed", "🗑️ Pytanie odrzucone przez: %s"),
            Map.entry("q_expired", "⌛ Pytanie wygasło"),
            Map.entry("p_title", "📝 Propozycja odpowiedzi od: %s"),
            Map.entry("p_question", "Pytanie"),
            Map.entry("p_answer", "Odpowiedź"),
            Map.entry("btn_approve", "Zatwierdź"),
            Map.entry("btn_edit", "Popraw i zatwierdź"),
            Map.entry("btn_reject", "Odrzuć"),
            Map.entry("p_approved", "✅ Odpowiedź od %s zatwierdzona przez: %s"),
            Map.entry("p_rejected", "❌ Odpowiedź od %s odrzucona przez: %s"),
            Map.entry("p_superseded", "Odpowiedź od %s zamknięta: zatwierdzono inną"),
            Map.entry("modal_answer", "Odpowiedź dla asystenta"),
            Map.entry("modal_edit", "Popraw i zatwierdź"),
            Map.entry("field_question", "Pytanie (możesz poprawić)"),
            Map.entry("field_answer", "Odpowiedź"),
            Map.entry("answer_hint", "Krótko i konkretnie, z koordynatami albo komendą, jeśli trzeba"),
            Map.entry("saved_pending", "📝 Dzięki! Twoja odpowiedź czeka na zatwierdzenie."),
            Map.entry("saved_direct", "✅ Dodane do bazy wiedzy. Asystent zna to od teraz."),
            Map.entry("approved", "✅ Zatwierdzone. Asystent zna to od teraz."),
            Map.entry("rejected", "❌ Odrzucone."),
            Map.entry("dismissed", "🗑️ Pytanie odrzucone."),
            Map.entry("no_role", "Nie masz rangi, która może tu odpowiadać."),
            Map.entry("no_approve", "To mogą zrobić tylko osoby z rangą od zatwierdzania."),
            Map.entry("closed", "To pytanie jest już zamknięte."),
            Map.entry("proposal_gone", "Ta propozycja została już rozpatrzona."),
            Map.entry("wrong_channel", "Ten przycisk działa tylko na kanale asystenta."),
            Map.entry("empty", "Wpisz pytanie i odpowiedź."),
            Map.entry("too_long", "Za długie: pytanie do %d znaków, odpowiedź do %d."),
            Map.entry("limit", "Na dziś wystarczy propozycji, spróbuj jutro."),
            Map.entry("failed", "Coś poszło nie tak, spróbuj jeszcze raz."));

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("q_unknown", "❓ The assistant did not know"),
            Map.entry("q_disputed", "👎 A player says this answer is wrong"),
            Map.entry("q_footer", "answer with the button and the assistant learns it"),
            Map.entry("asked", "Asked"),
            Map.entry("bot_answer", "What the assistant said"),
            Map.entry("btn_answer", "Answer"),
            Map.entry("btn_dismiss", "Dismiss question"),
            Map.entry("q_answered", "✅ Answered (approved by %s)"),
            Map.entry("q_dismissed", "🗑️ Dismissed by %s"),
            Map.entry("q_expired", "⌛ Question expired"),
            Map.entry("p_title", "📝 Proposed answer from %s"),
            Map.entry("p_question", "Question"),
            Map.entry("p_answer", "Answer"),
            Map.entry("btn_approve", "Approve"),
            Map.entry("btn_edit", "Edit and approve"),
            Map.entry("btn_reject", "Reject"),
            Map.entry("p_approved", "✅ Answer from %s approved by %s"),
            Map.entry("p_rejected", "❌ Answer from %s rejected by %s"),
            Map.entry("p_superseded", "Answer from %s closed: another one was approved"),
            Map.entry("modal_answer", "Answer for the assistant"),
            Map.entry("modal_edit", "Edit and approve"),
            Map.entry("field_question", "Question (you may correct it)"),
            Map.entry("field_answer", "Answer"),
            Map.entry("answer_hint", "Short and to the point, with coordinates or a command if needed"),
            Map.entry("saved_pending", "📝 Thanks! Your answer is waiting for approval."),
            Map.entry("saved_direct", "✅ Added to the knowledge base. The assistant knows it now."),
            Map.entry("approved", "✅ Approved. The assistant knows it now."),
            Map.entry("rejected", "❌ Rejected."),
            Map.entry("dismissed", "🗑️ Question dismissed."),
            Map.entry("no_role", "You do not have a role that can answer here."),
            Map.entry("no_approve", "Only people with the approving role can do that."),
            Map.entry("closed", "That question is already closed."),
            Map.entry("proposal_gone", "That proposal has already been reviewed."),
            Map.entry("wrong_channel", "This button only works in the assistant's channel."),
            Map.entry("empty", "Write the question and the answer."),
            Map.entry("too_long", "Too long: the question up to %d characters, the answer up to %d."),
            Map.entry("limit", "That is enough proposals for today, try again tomorrow."),
            Map.entry("failed", "Something went wrong, try again."));
}
