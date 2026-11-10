package com.betterloka.assistantserver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP side: small JSON requests from the mod, JSON answers back. Everything but the status
 * and the login needs the player's ticket in {@code Authorization: Bearer}.
 */
final class HttpApi {
    static final int MAX_BODY = 32 * 1024;

    private final AssistantService service;
    private final boolean trustForwardedFor;
    private final Map<String, Deque<Long>> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> loginsByIp = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    HttpApi(AssistantService service, boolean trustForwardedFor) {
        this.service = service;
        this.trustForwardedFor = trustForwardedFor;
    }

    void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        executor = Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "assistant-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String ip = clientIp(exchange);
            if (!allow(requestsByIp, ip, 120, 60_000)) {
                send(exchange, AssistantService.error(429, "slow_down", "Too many requests."));
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && ("/".equals(path) || "/v1/status".equals(path))) {
                send(exchange, service.status());
                return;
            }
            if (!"POST".equals(method) && !"GET".equals(method)) {
                send(exchange, AssistantService.error(405, "method", "Use GET or POST."));
                return;
            }
            JsonObject body;
            try {
                body = "POST".equals(method) ? readBody(exchange) : new JsonObject();
            } catch (IOException | JsonParseException | IllegalStateException e) {
                send(exchange, AssistantService.error(400, "bad_json", "The request was not JSON."));
                return;
            }
            if (path.startsWith("/v1/login/")) {
                if (!allow(loginsByIp, ip, 30, 10 * 60_000)) {
                    send(exchange, AssistantService.error(429, "slow_down", "Too many logins."));
                    return;
                }
                switch (path) {
                    case "/v1/login/start" -> send(exchange, service.loginStart(body));
                    case "/v1/login/finish" -> send(exchange, service.loginFinish(body));
                    default -> send(exchange, AssistantService.error(404, "not_found", "No such page."));
                }
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String ticket = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).strip() : null;
            Optional<Sessions.Session> session = service.session(ticket);
            if (session.isEmpty()) {
                send(exchange, AssistantService.error(401, "login", "Log in first."));
                return;
            }
            Sessions.Session player = session.get();
            AssistantService.Response response = switch (path) {
                case "/v1/me" -> service.me(player);
                case "/v1/ask" -> service.ask(player, body);
                case "/v1/feedback" -> service.feedback(player, body);
                case "/v1/open" -> service.open();
                case "/v1/propose" -> service.propose(player, body);
                case "/v1/admin/pending" -> service.pending(player);
                case "/v1/admin/review" -> service.review(player, body);
                case "/v1/admin/dismiss" -> service.dismiss(player, body);
                default -> AssistantService.error(404, "not_found", "No such page.");
            };
            send(exchange, response);
        } catch (RuntimeException e) {
            Log.warn("Request failed: " + e);
            try {
                send(exchange, AssistantService.error(500, "server", "Something went wrong on the server."));
            } catch (IOException | RuntimeException ignored) {
                // The response had already started; nothing more to say.
            }
        }
    }

    private String clientIp(HttpExchange exchange) {
        if (trustForwardedFor) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].strip();
            }
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private static boolean allow(Map<String, Deque<Long>> window, String ip, int max, long millis) {
        long now = System.currentTimeMillis();
        Deque<Long> times = window.computeIfAbsent(ip, key -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > millis) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                return false;
            }
            times.addLast(now);
        }
        if (window.size() > 10_000) {
            window.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || now - entry.getValue().peekLast() > millis;
                }
            });
        }
        return true;
    }

    private static JsonObject readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new IOException("too large");
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        }
    }

    private static void send(HttpExchange exchange, AssistantService.Response response) throws IOException {
        byte[] bytes = Json.COMPACT.toJson(response.body()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
