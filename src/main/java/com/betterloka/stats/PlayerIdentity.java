package com.betterloka.stats;

import java.util.List;
import java.util.UUID;

/**
 * The other accounts a player is known by.
 *
 * <p>What Loka's own {@code /find} shows, from the same fact underneath it: every account belongs to
 * an <em>identity</em>, and accounts sharing one identity are the same person. That grouping is
 * published, so the mod reads it rather than sending a command as the player.
 */
public record PlayerIdentity(List<Account> alts, List<String> previousNames) {

    public static final PlayerIdentity NONE = new PlayerIdentity(List.of(), List.of());

    /** One account sharing the searched player's identity. */
    public record Account(String name, UUID uuid, String rank) {
    }

    public boolean isEmpty() {
        return alts.isEmpty() && previousNames.isEmpty();
    }
}
