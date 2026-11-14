package com.betterloka.brewing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a potion takes to brew, step by step, as vanilla brews it.
 *
 * <p>A brewing stand turns up to {@value #BOTTLES_PER_BREW} bottles per ingredient, one ingredient per
 * step: water bottles, then nether wart for awkward potions, then what gives the effect, then
 * glowstone or redstone, then gunpowder for a splash potion and dragon's breath for a lingering one.
 * One blaze powder in the fuel slot lasts {@value #BREWS_PER_BLAZE_POWDER} of those steps, and each
 * step takes {@value #BREW_SECONDS} seconds.
 *
 * <p>Plain Java, so the arithmetic is tested outside the game; the screen turns the materials into
 * items and names.
 */
public final class Brewing {
    public static final int BOTTLES_PER_BREW = 3;
    public static final int STACK = 64;
    public static final int BREWS_PER_BLAZE_POWDER = 20;
    public static final int BREW_SECONDS = 20;
    /** Slots in a shulker box: finished potions do not stack, so this is how they are carried. */
    public static final int SHULKER_SLOTS = 27;

    private Brewing() {
    }

    /** Everything that goes into a stand, and what those are made from. */
    public enum Material {
        // Ingredients.
        NETHER_WART, BLAZE_POWDER, SUGAR, MAGMA_CREAM, GLISTERING_MELON_SLICE, GHAST_TEAR, GOLDEN_CARROT,
        PUFFERFISH, RABBIT_FOOT, SPIDER_EYE, PHANTOM_MEMBRANE, TURTLE_HELMET, FERMENTED_SPIDER_EYE,
        BREEZE_ROD, COBWEB, SLIME_BLOCK, STONE, GLOWSTONE_DUST, REDSTONE, GUNPOWDER, DRAGON_BREATH,
        // What goes in the bottle slots.
        GLASS_BOTTLE,
        // What the crafted ones are made from.
        GLASS, BLAZE_ROD, GOLD_INGOT, MELON_SLICE, CARROT, BROWN_MUSHROOM, SUGAR_CANE, SLIME_BALL
    }

    /** One potion's effect, and the ingredients that make it from water, in order. */
    public enum Effect {
        HEALING(true, false, Material.NETHER_WART, Material.GLISTERING_MELON_SLICE),
        STRENGTH(true, true, Material.NETHER_WART, Material.BLAZE_POWDER),
        SWIFTNESS(true, true, Material.NETHER_WART, Material.SUGAR),
        FIRE_RESISTANCE(false, true, Material.NETHER_WART, Material.MAGMA_CREAM),
        REGENERATION(true, true, Material.NETHER_WART, Material.GHAST_TEAR),
        HARMING(true, false, Material.NETHER_WART, Material.SPIDER_EYE, Material.FERMENTED_SPIDER_EYE),
        POISON(true, true, Material.NETHER_WART, Material.SPIDER_EYE),
        SLOWNESS(true, true, Material.NETHER_WART, Material.SUGAR, Material.FERMENTED_SPIDER_EYE),
        WEAKNESS(false, true, Material.FERMENTED_SPIDER_EYE),
        INVISIBILITY(false, true, Material.NETHER_WART, Material.GOLDEN_CARROT, Material.FERMENTED_SPIDER_EYE),
        NIGHT_VISION(false, true, Material.NETHER_WART, Material.GOLDEN_CARROT),
        WATER_BREATHING(false, true, Material.NETHER_WART, Material.PUFFERFISH),
        LEAPING(true, true, Material.NETHER_WART, Material.RABBIT_FOOT),
        SLOW_FALLING(false, true, Material.NETHER_WART, Material.PHANTOM_MEMBRANE),
        TURTLE_MASTER(true, true, Material.NETHER_WART, Material.TURTLE_HELMET),
        WIND_CHARGED(false, false, Material.NETHER_WART, Material.BREEZE_ROD),
        WEAVING(false, false, Material.NETHER_WART, Material.COBWEB),
        OOZING(false, false, Material.NETHER_WART, Material.SLIME_BLOCK),
        INFESTED(false, false, Material.NETHER_WART, Material.STONE);

        private final boolean strong;
        private final boolean extended;
        private final List<Material> chain;

        Effect(boolean strong, boolean extended, Material... chain) {
            this.strong = strong;
            this.extended = extended;
            this.chain = List.of(chain);
        }

        /** Whether glowstone makes a level II of it. */
        public boolean hasStrong() {
            return strong;
        }

        /** Whether redstone makes it last longer. */
        public boolean hasLong() {
            return extended;
        }

        public boolean allows(Level level) {
            return level == Level.NORMAL || (level == Level.STRONG && strong) || (level == Level.LONG && extended);
        }
    }

    public enum Form {
        DRINK, SPLASH, LINGERING
    }

    public enum Level {
        NORMAL, STRONG, LONG
    }

    /** A potion to brew. A level the effect does not have is taken as the plain one. */
    public record Recipe(Effect effect, Form form, Level level) {
        public Recipe {
            if (!effect.allows(level)) {
                level = Level.NORMAL;
            }
        }

        /** The ingredients in the order they go in, one brewing step each. */
        public List<Material> steps() {
            List<Material> steps = new ArrayList<>(effect.chain);
            if (level == Level.STRONG) {
                steps.add(Material.GLOWSTONE_DUST);
            } else if (level == Level.LONG) {
                steps.add(Material.REDSTONE);
            }
            if (form != Form.DRINK) {
                steps.add(Material.GUNPOWDER);
            }
            if (form == Form.LINGERING) {
                steps.add(Material.DRAGON_BREATH);
            }
            return steps;
        }
    }

    /**
     * What a machine of {@code stands} brewing stands makes with {@code perStand} of every ingredient
     * in each — a "set" of mats per stand being one of each step's ingredient, which brews three bottles.
     */
    public record Plan(Recipe recipe, int perStand, int stands) {
        public Plan {
            perStand = Math.max(0, perStand);
            stands = Math.max(0, stands);
        }

        public int steps() {
            return recipe.steps().size();
        }

        public long potionsPerStand() {
            return (long) BOTTLES_PER_BREW * perStand;
        }

        public long potions() {
            return potionsPerStand() * stands;
        }

        /** Empty bottles to fill with water: one per potion. */
        public long bottles() {
            return potions();
        }

        /** Of each ingredient, across every stand. */
        public long perIngredient() {
            return (long) perStand * stands;
        }

        public long brewsPerStand() {
            return (long) perStand * steps();
        }

        /** Blaze powder each stand burns: a stand cannot share the rest of a powder with the next. */
        public long fuelPerStand() {
            return ceilDiv(brewsPerStand(), BREWS_PER_BLAZE_POWDER);
        }

        public long fuel() {
            return fuelPerStand() * stands;
        }

        /** How long the stands take, all brewing side by side. */
        public long seconds() {
            return brewsPerStand() * BREW_SECONDS;
        }

        /** Shulker boxes for the finished potions, one potion a slot. */
        public long shulkers() {
            return ceilDiv(potions(), SHULKER_SLOTS);
        }

        /** Whether the empty bottles come to whole stacks. */
        public boolean fullStacks() {
            return bottles() % STACK == 0;
        }

        /**
         * Everything that goes into the stands, in the order it goes in: bottles, each step's
         * ingredient, then the blaze powder burnt as fuel (counted on its own line even when the
         * potion also takes blaze powder as an ingredient, as Strength does).
         */
        public List<Line> materials() {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(Material.GLASS_BOTTLE, bottles(), potionsPerStand(), false));
            for (Material step : recipe.steps()) {
                lines.add(new Line(step, perIngredient(), perStand, false));
            }
            lines.add(new Line(Material.BLAZE_POWDER, fuel(), fuelPerStand(), true));
            return lines;
        }

        /**
         * The same, worked back to what is farmed or bought: glass for the bottles, blaze rods for the
         * powder, gold ingots and melon slices for glistering melon, and so on. In first-seen order.
         */
        public List<Line> raw() {
            Map<Material, Long> totals = new LinkedHashMap<>();
            long goldNuggets = 0;
            long blazePowder = 0;
            for (Line line : materials()) {
                long count = line.total();
                switch (line.material()) {
                    case GLASS_BOTTLE -> add(totals, Material.GLASS, count);
                    case BLAZE_POWDER -> blazePowder += count;
                    case GLISTERING_MELON_SLICE -> {
                        add(totals, Material.MELON_SLICE, count);
                        goldNuggets += 8 * count;
                    }
                    case GOLDEN_CARROT -> {
                        add(totals, Material.CARROT, count);
                        goldNuggets += 8 * count;
                    }
                    case FERMENTED_SPIDER_EYE -> {
                        add(totals, Material.SPIDER_EYE, count);
                        add(totals, Material.BROWN_MUSHROOM, count);
                        add(totals, Material.SUGAR_CANE, count);
                    }
                    case MAGMA_CREAM -> {
                        add(totals, Material.SLIME_BALL, count);
                        blazePowder += count;
                    }
                    case SUGAR -> add(totals, Material.SUGAR_CANE, count);
                    case SLIME_BLOCK -> add(totals, Material.SLIME_BALL, 9 * count);
                    default -> add(totals, line.material(), count);
                }
            }
            if (blazePowder > 0) {
                add(totals, Material.BLAZE_ROD, ceilDiv(blazePowder, 2));
            }
            if (goldNuggets > 0) {
                add(totals, Material.GOLD_INGOT, ceilDiv(goldNuggets, 9));
            }
            List<Line> lines = new ArrayList<>();
            for (Map.Entry<Material, Long> entry : totals.entrySet()) {
                lines.add(new Line(entry.getKey(), entry.getValue(), 0, false));
            }
            return lines;
        }

        /** The nearest mats per stand, down and up, that bring the empty bottles to whole stacks. */
        public int[] fullStackNeighbours() {
            int step = stackStep(stands);
            int down = perStand / step * step;
            int up = down == perStand ? perStand : down + step;
            return new int[]{down, up};
        }
    }

    /**
     * One material.
     *
     * @param perStand how many go into each stand, or 0 where that means nothing
     * @param fuel     whether this is the blaze powder burnt as fuel rather than an ingredient
     */
    public record Line(Material material, long total, long perStand, boolean fuel) {
        public long stacks() {
            return total / STACK;
        }

        public long rest() {
            return total % STACK;
        }
    }

    /** What to fill each stand with for a number of potions. */
    public enum Fit {
        /** The fewest mats that make at least as many, in whole stacks of bottles. */
        ABOVE,
        /** The most that stay under, in whole stacks of bottles. */
        BELOW,
        /** Whole stacks of every ingredient in every stand, and so of bottles too. */
        FULL_STACKS
    }

    /** One way to fill the stands: mats per stand, the potions it makes, and how far off the aim. */
    public record Suggestion(Fit fit, int perStand, long potions, long difference) {
    }

    /**
     * Ways to fill {@code stands} stands to make about {@code target} potions, always in whole
     * stacks of bottles: the nearest above, the nearest below, and the nearest above in whole stacks
     * of ingredients — each left out where it would say the same as one before it, or nothing.
     */
    public static List<Suggestion> suggest(long target, int stands) {
        List<Suggestion> out = new ArrayList<>();
        if (target <= 0 || stands <= 0) {
            return out;
        }
        long perUnit = (long) BOTTLES_PER_BREW * stands;
        int step = stackStep(stands);
        long need = ceilDiv(target, perUnit);
        long above = ceilDiv(need, step) * step;
        long below = target / perUnit / step * step;
        long full = ceilDiv(target, perUnit * STACK) * STACK;
        add(out, Fit.ABOVE, above, perUnit, target);
        if (below > 0 && below != above) {
            add(out, Fit.BELOW, below, perUnit, target);
        }
        if (full != above) {
            add(out, Fit.FULL_STACKS, full, perUnit, target);
        }
        return out;
    }

    private static void add(List<Suggestion> out, Fit fit, long perStand, long perUnit, long target) {
        if (perStand > Integer.MAX_VALUE) {
            return;
        }
        long potions = perStand * perUnit;
        out.add(new Suggestion(fit, (int) perStand, potions, potions - target));
    }

    /**
     * The step mats per stand go up in for the bottles to come to whole stacks: three bottles a mat
     * and nothing in common with 64, so it is whatever of 64 the stands do not already make up.
     */
    public static int stackStep(int stands) {
        if (stands <= 0) {
            return STACK;
        }
        return STACK / gcd(stands, STACK);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static void add(Map<Material, Long> totals, Material material, long count) {
        totals.merge(material, count, Long::sum);
    }

    public static long ceilDiv(long a, long b) {
        return b <= 0 ? 0 : (a + b - 1) / b;
    }
}
