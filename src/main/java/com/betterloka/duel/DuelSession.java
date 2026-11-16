package com.betterloka.duel;

/**
 * When a duel starts and ends, and when to say how it went — across Loka's servers.
 *
 * <p>Loka runs its duels on a server of their own. "Match Ready!" comes on the server the player is on,
 * {@code /join} sends them to the duel server, and when the duel is over they are sent on again. In
 * between, while the proxy switches servers, the game has no world and no player at all. So:
 *
 * <ul>
 *   <li>a move <em>before</em> the fight is the way to it: the duel carries on, with the player's new
 *       entity id;</li>
 *   <li>a move <em>after</em> the fight is its end, and the summary waits until the player has
 *       arrived — {@value #POST_AFTER_ARRIVAL_MILLIS} ms in, after the server's own welcome — so it is
 *       there to read rather than lost in the switch;</li>
 *   <li>a duel that ends where it was fought (a death, twenty quiet seconds) waits
 *       {@value #WAIT_FOR_MOVE_MILLIS} ms for that move, and is said where it is if none comes.</li>
 * </ul>
 *
 * <p>Plain Java, so all of that is tested outside the game; {@link DuelTracker} feeds it.
 */
public final class DuelSession {
    static final long POST_AFTER_ARRIVAL_MILLIS = 1500;
    static final long WAIT_FOR_MOVE_MILLIS = 8000;

    private DuelStats duel;
    private DuelStats.Summary pending;
    private long pendingSince;
    private boolean pendingMoved;
    private boolean inWorld = true;
    private long arrivedAt;

    /** The duel being counted, or {@code null}. */
    public DuelStats duel() {
        return duel;
    }

    /** Whether a finished duel's summary is waiting to be said. */
    public boolean waiting() {
        return pending != null;
    }

    /**
     * "Match Ready!": a new duel for the player with entity id {@code self}.
     *
     * @return a summary to say right away — one still waiting, or a fought duel this one replaces
     */
    public DuelStats.Summary arm(int self, long now) {
        DuelStats.Summary say = pending;
        pending = null;
        if (duel != null && duel.fought()) {
            say = duel.summary();
        }
        duel = new DuelStats(self, now);
        return say;
    }

    /**
     * The player left a world or arrived in one: another server, another world, a respawn, a long
     * teleport.
     *
     * @param self the player's entity id where they are now, or {@code null} while between servers
     */
    public void moved(Integer self, long now) {
        if (duel != null) {
            if (duel.fought()) {
                end(now, true);
            } else if (self != null) {
                duel.rebind(self);
            }
        } else if (pending != null) {
            pendingMoved = true;
        }
        inWorld = self != null;
        arrivedAt = self != null ? now : 0;
    }

    /**
     * Once a tick, with the player in a world.
     *
     * @return a summary to say now, or {@code null}
     */
    public DuelStats.Summary tick(int self, boolean selfDead, long now) {
        if (duel != null) {
            if (selfDead) {
                duel.died(self, now);
            }
            if (duel.over(now)) {
                end(now, false);
            } else if (duel.stale(now)) {
                duel = null;
            }
        }
        if (pending == null || !inWorld) {
            return null;
        }
        boolean settled = pendingMoved
                ? arrivedAt != 0 && now - arrivedAt >= POST_AFTER_ARRIVAL_MILLIS
                : now - pendingSince >= WAIT_FOR_MOVE_MILLIS;
        if (!settled) {
            return null;
        }
        DuelStats.Summary out = pending;
        pending = null;
        return out;
    }

    /** Statistics switched off, or the player left Loka: nothing is counted or said. */
    public void forget() {
        duel = null;
        pending = null;
    }

    private void end(long now, boolean moved) {
        if (duel.fought()) {
            pending = duel.summary();
            pendingSince = now;
            pendingMoved = moved;
        }
        duel = null;
    }
}
