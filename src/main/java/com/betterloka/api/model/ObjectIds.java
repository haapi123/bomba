package com.betterloka.api.model;

import java.time.Instant;

/**
 * Loka stores everything in MongoDB, and every MongoDB ObjectID begins with a four byte big-endian
 * unix timestamp of the moment the document was created. The API never exposes a "first joined"
 * field, but it does expose the ObjectIDs, so the creation date of a player document is recoverable
 * from the ID itself.
 */
public final class ObjectIds {
    private ObjectIds() {
    }

    /**
     * @return the creation time encoded in the ObjectID, or {@code null} if the string is not one.
     */
    public static Instant timestamp(String objectId) {
        if (objectId == null || objectId.length() != 24) {
            return null;
        }
        try {
            long seconds = Long.parseLong(objectId.substring(0, 8), 16);
            return Instant.ofEpochSecond(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
