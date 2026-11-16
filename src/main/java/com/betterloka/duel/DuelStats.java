package com.betterloka.duel;

import java.util.HashMap;
import java.util.Map;

/**
 * One duel, counted from what the server tells every player near a fight.
 *
 * <ul>
 *   <li><b>Swings</b>: your own left clicks with a weapon in hand, and the swing animation the server
 *       shows of everybody else's.</li>
 *   <li><b>Hits</b>: the server's damage event, which names who hit whom; only melee (the attacker
 *       is also what did the damage, so not an arrow or a potion).</li>
 *   <li><b>Crits</b>: the crit animation the server plays on whoever was hit, credited to whoever
 *       just hit them.</li>
 *   <li><b>Sweeps</b>: the sweep sound, which the server plays where the attacker stands.</li>
 * </ul>
 *
 * <p>A crit or a sweep is only ever a kind of hit: each one is tied to a hit counted a moment before
 * it, and to only one. The server plays both even for a blow that lands while the target is still
 * flashing from the last one — which does next to nothing, sends no damage event, and is not counted
 * as a hit — so counted on their own they could come to more than the hits.
 *
 * <p>The opponent is whoever traded the most hits with you. Plain Java: entity ids in, numbers out.
 */
public final class DuelStats {
    /** A duel nobody has struck in this long is over. */
    public static final long IDLE_END_MILLIS = 20_000;
    /** "Match Ready!" with no fight after it for this long was not a duel this player fought. */
    public static final long ARMED_TIMEOUT_MILLIS = 5 * 60_000;
    /** How soon after its hit a crit or a sweep arrives: the same server tick, give or take a little. */
    static final long LINK_MILLIS = 300;

    /** One counted hit, and whether it has been called a crit or a sweep yet. */
    private static final class Blow {
        final int attacker;
        final long at;
        boolean crit;
        boolean sweep;

        Blow(int attacker, long at) {
            this.attacker = attacker;
            this.at = at;
        }
    }

    /** One side's numbers. */
    public static final class Side {
        private int swings;
        private int hits;
        private int crits;
        private int sweeps;
        private int combo;
        private int bestCombo;

        public int swings() {
            return swings;
        }

        public int hits() {
            return hits;
        }

        public int crits() {
            return crits;
        }

        public int sweeps() {
            return sweeps;
        }

        public int bestCombo() {
            return bestCombo;
        }

        /** Hits landed of swings made, 0–100. A swing missed by this client still counts its hit. */
        public int accuracy() {
            return swings == 0 ? (hits == 0 ? 0 : 100) : Math.min(100, Math.round(hits * 100f / swings));
        }

        /** Of the hits landed, the share that were crits, 0–100. */
        public int critShare() {
            return hits == 0 ? 0 : Math.round(crits * 100f / hits);
        }

        public int sweepShare() {
            return hits == 0 ? 0 : Math.round(sweeps * 100f / hits);
        }
    }

    /** How a duel ended, as far as the client can tell. */
    public enum Result {
        WON, LOST, UNKNOWN
    }

    /** What the duel came to. */
    public record Summary(Side you, Side them, int opponentId, long millis, Result result) {
    }

    private final int self;
    private final long armedAt;
    private final Side you = new Side();
    private final Map<Integer, Side> others = new HashMap<>();
    /** Hits traded with each other player, both ways: who the opponent was. */
    private final Map<Integer, Integer> exchanged = new HashMap<>();
    /** The last hit on each victim, for its crit, and by each attacker, for its sweep. */
    private final Map<Integer, Blow> lastBlowOn = new HashMap<>();
    private final Map<Integer, Blow> lastBlowBy = new HashMap<>();
    private long firstHitAt;
    private long lastCombatAt;
    private Integer died;

    public DuelStats(int self, long now) {
        this.self = self;
        this.armedAt = now;
    }

    private Side side(int entityId) {
        return entityId == self ? you : others.computeIfAbsent(entityId, id -> new Side());
    }

    public boolean fought() {
        return firstHitAt != 0;
    }

    public void swing(int entityId) {
        if (died == null) {
            side(entityId).swings++;
        }
    }

    /** A melee hit that did damage; only hits between this player and somebody else count. */
    public void hit(int attacker, int victim, long now) {
        if (died != null || attacker == victim || (attacker != self && victim != self)) {
            return;
        }
        Side hitter = side(attacker);
        Side hurt = side(victim);
        hitter.hits++;
        hitter.combo++;
        hitter.bestCombo = Math.max(hitter.bestCombo, hitter.combo);
        hurt.combo = 0;
        int other = attacker == self ? victim : attacker;
        exchanged.merge(other, 1, Integer::sum);
        Blow blow = new Blow(attacker, now);
        lastBlowOn.put(victim, blow);
        lastBlowBy.put(attacker, blow);
        if (firstHitAt == 0) {
            firstHitAt = now;
        }
        lastCombatAt = now;
    }

    /** The crit animation on {@code victim}: the hit they just took was a crit. */
    public void crit(int victim, long now) {
        Blow blow = lastBlowOn.get(victim);
        if (died == null && blow != null && !blow.crit && now - blow.at <= LINK_MILLIS) {
            blow.crit = true;
            side(blow.attacker).crits++;
        }
    }

    /** The sweep sound where {@code attacker} stands: the hit they just made was a sweep. */
    public void sweep(int attacker, long now) {
        Blow blow = lastBlowBy.get(attacker);
        if (died == null && blow != null && !blow.sweep && now - blow.at <= LINK_MILLIS) {
            blow.sweep = true;
            side(attacker).sweeps++;
        }
    }

    /** Somebody died: the duel is decided if it was this player or the opponent. */
    public void died(int entityId, long now) {
        if (died == null && fought() && (entityId == self || entityId == opponent())) {
            died = entityId;
            lastCombatAt = now;
        }
    }

    /** Whoever traded the most hits with this player, or -1 before anyone has. */
    public int opponent() {
        int best = -1;
        int most = 0;
        for (Map.Entry<Integer, Integer> entry : exchanged.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    public boolean decided() {
        return died != null;
    }

    /** Over by itself: decided, or nobody has struck for a while. */
    public boolean over(long now) {
        return decided() || (fought() && now - lastCombatAt > IDLE_END_MILLIS);
    }

    /** "Match Ready!" long ago and nothing since. */
    public boolean stale(long now) {
        return !fought() && now - armedAt > ARMED_TIMEOUT_MILLIS;
    }

    public Summary summary() {
        int opponent = opponent();
        Side them = opponent == -1 ? new Side() : others.getOrDefault(opponent, new Side());
        Result result = died == null ? Result.UNKNOWN : died == self ? Result.LOST : Result.WON;
        long length = fought() ? Math.max(0, lastCombatAt - firstHitAt) : 0;
        return new Summary(you, them, opponent, length, result);
    }
}
