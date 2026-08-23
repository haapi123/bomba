package com.betterloka.towns;

/**
 * One reading of a town's {@code /town info} panel.
 *
 * <p>The active count is the number Loka deletes towns on — a town with none for a month is removed
 * — and it is the one number the public API does not publish anywhere. It exists only on this panel,
 * so it is read from the panel, and every reading is stamped with when it was taken: the whole value
 * of the number is in watching it fall.
 *
 * @param townName  the town as the panel spelled it
 * @param members   total members
 * @param active    members Loka counts as active, or {@code -1} if the panel did not say
 * @param readAt    epoch millis when this was read
 */
public record TownInfoReading(String townName, int members, int active, long readAt) {

    public boolean hasActive() {
        return active >= 0;
    }

    /** True when this reading is the state Loka's deletion timer counts. */
    public boolean atZero() {
        return active == 0;
    }
}
