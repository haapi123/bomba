package com.betterloka.assistantserver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** Asks Mojang whether a player really "joined" the one-time id we gave them. */
interface MojangCheck {
    record Profile(String uuid, String name) {
    }

    /** The player's profile if they joined, empty if they did not; throws if Mojang cannot be asked. */
    Optional<Profile> hasJoined(String name, String serverId) throws IOException;

    /** The real check, against Mojang's session server. */
    static MojangCheck mojang() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (name, serverId) -> {
            URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined?username="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&serverId="
                    + URLEncoder.encode(serverId, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 204 || response.body().isBlank()) {
                    return Optional.empty();
                }
                if (response.statusCode() != 200) {
                    throw new IOException("Mojang answered " + response.statusCode());
                }
                JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
                return Optional.of(new Profile(dashed(profile.get("id").getAsString()),
                        profile.get("name").getAsString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (RuntimeException e) {
                throw new IOException("unexpected answer from Mojang: " + e.getMessage(), e);
            }
        };
    }

    /**
     * Testing only: believes the name. The UUID is the one an offline-mode server would give it, so
     * the same name always gets the same limits.
     */
    static MojangCheck trusting() {
        return (name, serverId) -> Optional.of(new Profile(
                java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString(),
                name));
    }

    static String dashed(String uuid) {
        String plain = uuid.replace("-", "");
        if (plain.length() != 32) {
            return uuid;
        }
        return plain.substring(0, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12, 16) + "-"
                + plain.substring(16, 20) + "-" + plain.substring(20);
    }
}
