package com.betterloka.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A minimal Discord gateway connection — enough to receive slash commands and nothing more.
 *
 * <p>Slash commands cannot be done over a webhook: Discord has to be able to reach the bot, either
 * at a public HTTPS endpoint or over this socket. A socket is the one that works on a game panel
 * with no domain and no inbound ports, which is where this bot actually runs.
 *
 * <p>Written against {@link WebSocket} rather than a Discord library: what is needed here is
 * identify, heartbeat, reconnect and one dispatch, and a library for that would be several megabytes
 * added to a jar that is currently under two.
 */
public final class DiscordGateway {
    private static final Logger LOG = LoggerFactory.getLogger("BetterLokaBot");

    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    /**
     * No intents at all.
     *
     * <p>Interactions are delivered to every bot regardless of intents, and asking for none means
     * the bot needs no privileged toggles in the developer portal and can read nothing it is not
     * explicitly handed.
     */
    private static final int INTENTS = 0;

    private final String token;
    private final Consumer<JsonObject> onInteraction;
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "betterloka-gateway");
                thread.setDaemon(true);
                return thread;
            });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final AtomicBoolean stopped = new AtomicBoolean();

    /** The application's own ID, learned from READY — needed to answer an interaction. */
    private volatile String applicationId;
    private volatile ScheduledFuture<?> heartbeat;
    private volatile Integer sequence;

    public DiscordGateway(String token, Consumer<JsonObject> onInteraction) {
        this.token = token;
        this.onInteraction = onInteraction;
    }

    public String applicationId() {
        return applicationId;
    }

    /** Connects, and keeps reconnecting until {@link #stop()}. Returns as soon as it is running. */
    public void start() {
        connect(1);
    }

    public void stop() {
        stopped.set(true);
        stopHeartbeat();
        timers.shutdownNow();
    }

    private void connect(int attempt) {
        if (stopped.get()) {
            return;
        }
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(GATEWAY_URL), new Listener())
                .whenComplete((socket, error) -> {
                    if (error == null) {
                        LOG.info("Connected to the Discord gateway");
                        return;
                    }
                    // Backed off rather than hammered: a wrong token or a Discord outage both look
                    // like this, and a tight retry loop helps neither.
                    long waitSeconds = Math.min(60, 1L << Math.min(6, attempt));
                    LOG.warn("Gateway connection failed ({}), retrying in {}s",
                            error.getMessage(), waitSeconds);
                    schedule(() -> connect(attempt + 1), waitSeconds * 1000);
                });
    }

    private void schedule(Runnable task, long delayMillis) {
        if (stopped.get()) {
            return;
        }
        try {
            timers.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // The pool is shut down, which only happens on stop().
        }
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> running = heartbeat;
        if (running != null) {
            running.cancel(false);
            heartbeat = null;
        }
    }

    private void send(WebSocket socket, JsonObject payload) {
        socket.sendText(payload.toString(), true);
    }

    /** Accumulates fragmented frames and handles the four opcodes this bot cares about. */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data);
            socket.request(1);
            if (!last) {
                return null;
            }
            String message = buffer.toString();
            buffer.setLength(0);
            try {
                handle(socket, JsonParser.parseString(message).getAsJsonObject());
            } catch (RuntimeException e) {
                LOG.warn("Could not handle a gateway message", e);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            stopHeartbeat();
            if (stopped.get()) {
                return null;
            }
            // 4004 is a bad token and 4014 a privileged intent that was not granted; both are
            // configuration, and reconnecting forever would just bury the reason in the log.
            if (status == 4004 || status == 4014) {
                LOG.error("Discord closed the gateway: {} {} — check botToken in the config",
                        status, reason);
                return null;
            }
            LOG.warn("Gateway closed ({} {}), reconnecting", status, reason);
            schedule(() -> connect(1), 3000);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            stopHeartbeat();
            if (stopped.get()) {
                return;
            }
            LOG.warn("Gateway error: {}", error.getMessage());
            schedule(() -> connect(1), 5000);
        }

        private void handle(WebSocket socket, JsonObject message) {
            if (message.has("s") && !message.get("s").isJsonNull()) {
                sequence = message.get("s").getAsInt();
            }
            int op = message.get("op").getAsInt();
            switch (op) {
                case 10 -> {
                    long interval = message.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                    startHeartbeat(socket, interval);
                    identify(socket);
                }
                case 0 -> dispatch(message);
                // Discord asking for a beat right now, or telling us to reconnect.
                case 1 -> beat(socket);
                case 7 -> socket.sendClose(1000, "reconnect requested");
                case 9 -> {
                    LOG.warn("Discord invalidated the session, identifying again");
                    schedule(() -> identify(socket), 2000);
                }
                default -> {
                    // 11 is a heartbeat ack; anything else is not this bot's business.
                }
            }
        }

        private void dispatch(JsonObject message) {
            String type = message.has("t") && !message.get("t").isJsonNull()
                    ? message.get("t").getAsString()
                    : "";
            JsonObject data = message.getAsJsonObject("d");
            if ("READY".equals(type)) {
                applicationId = data.getAsJsonObject("application").get("id").getAsString();
                LOG.info("Gateway ready as application {}", applicationId);
                return;
            }
            if ("INTERACTION_CREATE".equals(type)) {
                onInteraction.accept(data);
            }
        }

        private void startHeartbeat(WebSocket socket, long intervalMillis) {
            stopHeartbeat();
            heartbeat = timers.scheduleAtFixedRate(() -> beat(socket),
                    (long) (intervalMillis * Math.random()), intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void beat(WebSocket socket) {
            JsonObject payload = new JsonObject();
            payload.addProperty("op", 1);
            if (sequence == null) {
                payload.add("d", com.google.gson.JsonNull.INSTANCE);
            } else {
                payload.addProperty("d", sequence);
            }
            try {
                send(socket, payload);
            } catch (RuntimeException e) {
                LOG.warn("Could not send a heartbeat: {}", e.getMessage());
            }
        }

        private void identify(WebSocket socket) {
            JsonObject properties = new JsonObject();
            properties.addProperty("os", System.getProperty("os.name", "linux"));
            properties.addProperty("browser", "BetterLoka");
            properties.addProperty("device", "BetterLoka");

            JsonObject data = new JsonObject();
            data.addProperty("token", token);
            data.addProperty("intents", INTENTS);
            data.add("properties", properties);

            JsonObject payload = new JsonObject();
            payload.addProperty("op", 2);
            payload.add("d", data);
            send(socket, payload);
        }
    }
}
