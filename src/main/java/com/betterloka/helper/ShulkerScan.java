package com.betterloka.helper;

/**
 * Shulker Scanner's verdict on one shulker box: enough splash Instant Health II for a fight, some, or
 * none. Plain Java, so the cut-offs are tested on their own; {@link ShulkerScanner} counts the potions
 * in the game and draws the colour.
 */
public final class ShulkerScan {
    /** More than this many is a stocked shulker. */
    public static final int STOCKED_ABOVE = 15;

    public enum Level {
        /** More than {@value #STOCKED_ABOVE}: green. */
        STOCKED(0x4CD964),
        /** One to {@value #STOCKED_ABOVE}: yellow. */
        LOW(0xFFD23F),
        /** None — empty, or nothing but other things: red. */
        NONE(0xFF4B4B);

        private final int rgb;

        Level(int rgb) {
            this.rgb = rgb;
        }

        public int rgb() {
            return rgb;
        }
    }

    private ShulkerScan() {
    }

    public static Level level(int heals) {
        if (heals > STOCKED_ABOVE) {
            return Level.STOCKED;
        }
        return heals > 0 ? Level.LOW : Level.NONE;
    }

    /** One stack in a shulker, as far as the count cares. */
    public record Stack(boolean splash, int instantHealthLevel, int count) {
    }

    /**
     * How many splash potions of Instant Health II or stronger the stacks hold. Read by effect, not by
     * name, so a renamed potion or one with the effect set by hand counts as well.
     */
    public static int heals(Iterable<Stack> stacks) {
        int total = 0;
        for (Stack stack : stacks) {
            if (stack.splash() && stack.instantHealthLevel() >= 2) {
                total += stack.count();
            }
        }
        return total;
    }
}
