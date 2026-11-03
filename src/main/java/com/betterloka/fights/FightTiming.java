package com.betterloka.fights;

import com.betterloka.api.model.ObjectIds;
import com.betterloka.api.model.ScheduledFight;

import java.time.Instant;
import java.util.Locale;

/**
 * When a declared fight goes off.
 *
 * <p>Loka publishes no start time, but it does not need to: a fight starts a fixed time after it is
 * declared, and the declaration time is in the record's id, a MongoDB ObjectId. Read off two hundred
 * fights over ten days of {@code /battlezones}, the wait is
 * <ul>
 *   <li>fifteen minutes for ground nobody holds,</li>
 *   <li>thirty minutes for a held territory on Rivina,</li>
 *   <li>an hour for a held territory anywhere else,</li>
 * </ul>
 * give or take the minute the server's scheduler takes to notice.
 *
 * <p>Plain Java, so it is tested outside the game.
 */
public final class FightTiming {
    public static final long NEUTRAL_MILLIS = 15 * 60 * 1000L;
    public static final long RIVINA_MILLIS = 30 * 60 * 1000L;
    public static final long DEFENDED_MILLIS = 60 * 60 * 1000L;

    private FightTiming() {
    }

    /** How long after being declared a fight over this ground starts. */
    public static long delay(String world, boolean defended) {
        if (!defended) {
            return NEUTRAL_MILLIS;
        }
        return "lilboi".equalsIgnoreCase(world) ? RIVINA_MILLIS : DEFENDED_MILLIS;
    }

    /** When it was declared, from its id, or 0 when the id says nothing. */
    public static long declaredAt(ScheduledFight fight) {
        Instant at = ObjectIds.timestamp(fight.id());
        return at == null ? 0 : at.toEpochMilli();
    }

    /**
     * When it starts, or started: the real start once Loka has one, and otherwise the declaration
     * plus the wait for that ground.
     *
     * @return epoch millis, or 0 when there is no telling
     */
    public static long startsAt(ScheduledFight fight) {
        if (fight.started() && fight.timeStarted() > 0) {
            return fight.timeStarted();
        }
        long declared = declaredAt(fight);
        return declared == 0 ? 0 : declared + delay(fight.world(), fight.defended());
    }

    /** {@code 52:10}, or {@code 1:02:03} for an hour or more; a part second rounds up. */
    public static String clock(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        long hours = seconds / 3600;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
