package com.betterloka.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.UUID;

/** Null-tolerant accessors for the Loka API payloads, which leave many fields as JSON null. */
public final class Json {
    private Json() {
    }

    public static String string(JsonObject object, String key) {
        JsonElement element = get(object, key);
        return element == null ? null : element.getAsString();
    }

    public static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = get(object, key);
        try {
            return element == null ? fallback : element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    public static long longValue(JsonObject object, String key, long fallback) {
        JsonElement element = get(object, key);
        try {
            return element == null ? fallback : element.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    public static double doubleValue(JsonObject object, String key, double fallback) {
        JsonElement element = get(object, key);
        try {
            return element == null ? fallback : element.getAsDouble();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = get(object, key);
        try {
            return element == null ? fallback : element.getAsBoolean();
        } catch (UnsupportedOperationException e) {
            return fallback;
        }
    }

    public static UUID uuid(JsonObject object, String key) {
        return parseUuid(string(object, key));
    }

    public static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static JsonObject object(JsonObject object, String key) {
        JsonElement element = get(object, key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonElement get(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element;
    }
}
