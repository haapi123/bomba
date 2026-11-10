package com.betterloka.assistantserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Who is asking. A player proves who they are the way they do joining any Minecraft server: the
 * mod asks Mojang to note that the player is "joining" a one-time id from here, and this server asks
 * Mojang whether they did. The player's Minecraft token goes only to Mojang, never here.
 *
 * <p>After that the player carries a signed ticket of ours for a few days; nothing is stored per
 * session.
 */
final class Sessions {
    static final Duration TICKET_LIFE = Duration.ofDays(3);
    static final Duration CHALLENGE_LIFE = Duration.ofMinutes(2);

    record Session(String uuid, String name) {
    }

    private record Challenge(String name, long expiresAtMillis) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    Sessions(byte[] secret, Clock clock) {
        this.secret = secret.clone();
        this.clock = clock;
    }

    /** The signing key in {@code data/secret.key}, made on first start. */
    static byte[] loadSecret(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                byte[] existing = HexFormat.of().parseHex(Files.readString(file).strip());
                if (existing.length >= 32) {
                    return existing;
                }
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, HexFormat.of().formatHex(fresh));
            return fresh;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read or write " + file + ": " + e.getMessage(), e);
        }
    }

    /** A one-time id for the player's "join"; Mojang ties it to them for a short while. */
    String challenge(String name) {
        long now = clock.millis();
        challenges.values().removeIf(challenge -> challenge.expiresAtMillis() < now);
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String serverId = HexFormat.of().formatHex(bytes);
        challenges.put(serverId, new Challenge(name, now + CHALLENGE_LIFE.toMillis()));
        return serverId;
    }

    /** Spends a challenge: true if it was ours, unexpired and issued for this name. */
    boolean redeem(String serverId, String name) {
        Challenge challenge = serverId == null ? null : challenges.remove(serverId);
        return challenge != null && challenge.expiresAtMillis() >= clock.millis()
                && challenge.name().equalsIgnoreCase(name);
    }

    String ticket(String uuid, String name) {
        long expires = clock.millis() / 1000 + TICKET_LIFE.toSeconds();
        String payload = B64.encodeToString((uuid + "|" + name + "|" + expires).getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(sign(payload));
    }

    long ticketExpiresAtMillis() {
        return clock.millis() + TICKET_LIFE.toMillis();
    }

    Optional<Session> verify(String ticket) {
        if (ticket == null) {
            return Optional.empty();
        }
        int dot = ticket.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String payload = ticket.substring(0, dot);
        try {
            byte[] given = B64D.decode(ticket.substring(dot + 1));
            if (!MessageDigest.isEqual(given, sign(payload))) {
                return Optional.empty();
            }
            String[] parts = new String(B64D.decode(payload), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || Long.parseLong(parts[2]) < clock.millis() / 1000) {
                return Optional.empty();
            }
            return Optional.of(new Session(parts[0], parts[1]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
