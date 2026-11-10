package com.betterloka.assistant;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Talks to a Loka Assistant server: logs the player in, asks, rates answers, proposes answers, and
 * (for the assistant's admins) approves them. Every call runs off the game thread and hands back a
 * future.
 *
 * <p>Logging in proves who the player is the way joining any Minecraft server does: the server hands
 * out a one-time id, the game tells Mojang the player is "joining" it ({@link Joiner}), and the
 * server asks Mojang whether they did. The player's Minecraft token goes only to Mojang. What the
 * server gives back is a ticket of its own, kept in memory for a few days.
 *
 * <p>Plain Java; the game is reached only through the {@link Joiner} and the two suppliers.
 */
public final class AssistantClient {
    /** Tells Mojang the player is joining {@code serverId}. */
    @FunctionalInterface
    public interface Joiner {
        void join(String serverId) throws Exception;
    }

    public record Turn(String question, String answer) {
    }

    public record Answer(String id, String text, String status, int questionsLeft) {
        public boolean unknown() {
            return "unknown".equals(status);
        }
    }

    public record OpenQuestion(String id, String question, String kind, String botAnswer, int count) {
        public boolean disputed() {
            return "disputed".equals(kind);
        }
    }

    public record Proposal(String id, String question, String answer, String by, long time) {
    }

    public record Status(boolean ready, int maxQuestionChars, int maxProposalChars) {
    }

    /** Why a call failed: one of the server's error codes, or {@code offline} / {@code no_server}. */
    public static final class Failure extends Exception {
        private final String code;

        public Failure(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record Login(String base, String player, String token, long expiresAt) {
    }

    private static final Gson GSON = new Gson();

    private final Supplier<String> server;
    private final Supplier<String> player;
    private final Joiner joiner;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "BetterLoka assistant");
        thread.setDaemon(true);
        return thread;
    });

    private Login login;
    private volatile boolean admin;
    private volatile int questionsLeft = -1;
    private volatile String loggedInAs;

    public AssistantClient(Supplier<String> server, Supplier<String> player, Joiner joiner) {
        this.server = server;
        this.player = player;
        this.joiner = joiner;
    }

    public boolean admin() {
        return admin;
    }

    /** Questions the player has left today, or -1 before the server has said. */
    public int questionsLeft() {
        return questionsLeft;
    }

    public String loggedInAs() {
        return loggedInAs;
    }

    /** {@code play.example.com:8787} becomes {@code http://play.example.com:8787}; no trailing slash. */
    public static String normalize(String address) {
        if (address == null) {
            return "";
        }
        String base = address.strip();
        if (base.isEmpty()) {
            return "";
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    public CompletableFuture<Status> status() {
        return call(() -> {
            JsonObject body = request("GET", "/v1/status", null, null, 10);
            return new Status(bool(body, "ready"), integer(body, "maxQuestionChars", 300), integer(body, "maxProposalChars", 1200));
        });
    }

    /** Logs in (if not already) and says who the server thinks the player is. */
    public CompletableFuture<String> connect() {
        return call(() -> {
            authed("GET", "/v1/me", null, 15);
            return loggedInAs;
        });
    }

    public CompletableFuture<Answer> ask(String question, String language, List<Turn> history) {
        return call(() -> {
            JsonObject request = new JsonObject();
            request.addProperty("question", question);
            request.addProperty("lang", language);
            JsonArray turns = new JsonArray();
            for (Turn turn : history) {
                JsonObject item = new JsonObject();
                item.addProperty("q", turn.question());
                item.addProperty("a", turn.answer());
                turns.add(item);
            }
            request.add("history", turns);
            JsonObject body = authed("POST", "/v1/ask", request, 75);
            Answer answer = new Answer(text(body, "id"), text(body, "answer"), text(body, "status"),
                    integer(body, "questionsLeft", -1));
            questionsLeft = answer.questionsLeft();
            return answer;
        });
    }

    public CompletableFuture<Void> feedback(String answerId, boolean good) {
        return call(() -> {
            JsonObject request = new JsonObject();
            request.addProperty("id", answerId);
            request.addProperty("good", good);
            authed("POST", "/v1/feedback", request, 15);
            return null;
        });
    }

    public CompletableFuture<List<OpenQuestion>> openQuestions() {
        return call(() -> {
            JsonObject body = authed("POST", "/v1/open", new JsonObject(), 15);
            List<OpenQuestion> list = new ArrayList<>();
            for (JsonElement element : array(body, "questions")) {
                JsonObject item = element.getAsJsonObject();
                list.add(new OpenQuestion(text(item, "id"), text(item, "question"), text(item, "kind"),
                        text(item, "botAnswer"), integer(item, "count", 1)));
            }
            return list;
        });
    }

    /** Proposes an answer, to an open question ({@code openId}) or to a question of the player's own. */
    public CompletableFuture<String> propose(String openId, String question, String answer) {
        return call(() -> {
            JsonObject request = new JsonObject();
            if (openId != null) {
                request.addProperty("openId", openId);
            }
            request.addProperty("question", question);
            request.addProperty("answer", answer);
            return text(authed("POST", "/v1/propose", request, 15), "id");
        });
    }

    public CompletableFuture<List<Proposal>> pending() {
        return call(() -> {
            JsonObject body = authed("POST", "/v1/admin/pending", new JsonObject(), 15);
            List<Proposal> list = new ArrayList<>();
            for (JsonElement element : array(body, "proposals")) {
                JsonObject item = element.getAsJsonObject();
                list.add(new Proposal(text(item, "id"), text(item, "question"), text(item, "answer"), text(item, "by"),
                        item.has("time") ? item.get("time").getAsLong() : 0));
            }
            return list;
        });
    }

    public CompletableFuture<Void> review(String proposalId, boolean approve) {
        return call(() -> {
            JsonObject request = new JsonObject();
            request.addProperty("id", proposalId);
            request.addProperty("approve", approve);
            authed("POST", "/v1/admin/review", request, 15);
            return null;
        });
    }

    public CompletableFuture<Void> dismiss(String openId) {
        return call(() -> {
            JsonObject request = new JsonObject();
            request.addProperty("id", openId);
            authed("POST", "/v1/admin/dismiss", request, 15);
            return null;
        });
    }

    /** Forgets the login, as when the server address changes. */
    public synchronized void logout() {
        login = null;
        admin = false;
        questionsLeft = -1;
        loggedInAs = null;
    }

    // ---- plumbing ----

    private interface Call<T> {
        T run() throws Failure;
    }

    private <T> CompletableFuture<T> call(Call<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(call.run());
            } catch (Failure e) {
                future.completeExceptionally(e);
            } catch (RuntimeException e) {
                future.completeExceptionally(new Failure("server", String.valueOf(e.getMessage())));
            }
        });
        return future;
    }

    /** A call that needs the ticket: logs in first if needed, and once more if the ticket was refused. */
    private JsonObject authed(String method, String path, JsonObject body, int timeoutSeconds) throws Failure {
        String token = ticket(false);
        try {
            return request(method, path, body, token, timeoutSeconds);
        } catch (Failure e) {
            if (!"login".equals(e.code())) {
                throw e;
            }
            return request(method, path, body, ticket(true), timeoutSeconds);
        }
    }

    private synchronized String ticket(boolean fresh) throws Failure {
        String base = normalize(server.get());
        String name = player.get();
        if (!fresh && login != null && login.base().equals(base) && login.player().equals(name)
                && login.expiresAt() > System.currentTimeMillis() + 60_000) {
            return login.token();
        }
        login = null;
        JsonObject start = new JsonObject();
        start.addProperty("name", name);
        String serverId = text(request("POST", "/v1/login/start", start, null, 15), "serverId");
        try {
            joiner.join(serverId);
        } catch (Exception e) {
            // Offline accounts and test clients cannot join; the server decides whether that is fine.
        }
        JsonObject finish = new JsonObject();
        finish.addProperty("name", name);
        finish.addProperty("serverId", serverId);
        JsonObject body = request("POST", "/v1/login/finish", finish, null, 20);
        admin = bool(body, "admin");
        questionsLeft = integer(body, "questionsLeft", -1);
        loggedInAs = text(body, "name");
        login = new Login(base, name, text(body, "token"), body.has("expiresAt") ? body.get("expiresAt").getAsLong() : 0);
        return login.token();
    }

    private JsonObject request(String method, String path, JsonObject body, String token, int timeoutSeconds) throws Failure {
        String base = normalize(server.get());
        if (base.isEmpty()) {
            throw new Failure("no_server", "No assistant server is set.");
        }
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeoutSeconds));
        } catch (IllegalArgumentException e) {
            throw new Failure("no_server", "Not an address: " + base);
        }
        request.header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body == null ? new JsonObject() : body)));
        } else {
            request.GET();
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Failure("offline", String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("offline", "interrupted");
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new Failure("offline", "Not an assistant server (HTTP " + response.statusCode() + ")");
        }
        if (!bool(json, "ok")) {
            String code = text(json, "error");
            throw new Failure(code == null ? "server" : code.toLowerCase(Locale.ROOT), String.valueOf(text(json, "message")));
        }
        return json;
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }
}
