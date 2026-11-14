package com.betterloka.brewing;

import com.betterloka.brewing.Brewing.Effect;
import com.betterloka.brewing.Brewing.Fit;
import com.betterloka.brewing.Brewing.Form;
import com.betterloka.brewing.Brewing.Level;
import com.betterloka.brewing.Brewing.Line;
import com.betterloka.brewing.Brewing.Material;
import com.betterloka.brewing.Brewing.Plan;
import com.betterloka.brewing.Brewing.Recipe;
import com.betterloka.brewing.Brewing.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrewingTest {
    private static final Recipe SPLASH_HEALING_II = new Recipe(Effect.HEALING, Form.SPLASH, Level.STRONG);

    @Test
    void sixtyFourMatsInEachOfEightStandsMakeTwentyFourStacks() {
        Plan plan = new Plan(SPLASH_HEALING_II, 64, 8);
        assertEquals(192, plan.potionsPerStand(), "three bottles a mat");
        assertEquals(1536, plan.potions());
        assertEquals(1536, plan.bottles());
        assertTrue(plan.fullStacks());
        Line bottles = plan.materials().get(0);
        assertEquals(Material.GLASS_BOTTLE, bottles.material());
        assertEquals(24, bottles.stacks());
        assertEquals(0, bottles.rest());
        assertEquals(192, bottles.perStand());
    }

    @Test
    void aSplashHealingTwoIsFourStepsFromWater() {
        assertEquals(List.of(Material.NETHER_WART, Material.GLISTERING_MELON_SLICE, Material.GLOWSTONE_DUST,
                Material.GUNPOWDER), SPLASH_HEALING_II.steps());
        assertEquals(List.of(Material.NETHER_WART, Material.BLAZE_POWDER, Material.REDSTONE,
                        Material.GUNPOWDER, Material.DRAGON_BREATH),
                new Recipe(Effect.STRENGTH, Form.LINGERING, Level.LONG).steps());
        assertEquals(List.of(Material.FERMENTED_SPIDER_EYE),
                new Recipe(Effect.WEAKNESS, Form.DRINK, Level.NORMAL).steps(), "weakness is brewed from water");
        assertEquals(List.of(Material.NETHER_WART, Material.SPIDER_EYE, Material.FERMENTED_SPIDER_EYE,
                Material.GLOWSTONE_DUST), new Recipe(Effect.HARMING, Form.DRINK, Level.STRONG).steps());
    }

    @Test
    void aLevelTheEffectDoesNotHaveIsThePlainOne() {
        assertEquals(Level.NORMAL, new Recipe(Effect.FIRE_RESISTANCE, Form.DRINK, Level.STRONG).level());
        assertEquals(Level.NORMAL, new Recipe(Effect.HEALING, Form.DRINK, Level.LONG).level());
        assertEquals(Level.NORMAL, new Recipe(Effect.WIND_CHARGED, Form.SPLASH, Level.LONG).level());
        assertEquals(Level.LONG, new Recipe(Effect.FIRE_RESISTANCE, Form.DRINK, Level.LONG).level());
        assertFalse(Effect.HEALING.hasLong());
        assertTrue(Effect.HEALING.hasStrong());
    }

    @Test
    void everyStepBurnsFuelAndEachStandItsOwn() {
        Plan plan = new Plan(SPLASH_HEALING_II, 64, 8);
        assertEquals(4, plan.steps());
        assertEquals(256, plan.brewsPerStand());
        assertEquals(13, plan.fuelPerStand(), "256 brews at 20 a powder: 12.8, and a stand burns whole ones");
        assertEquals(104, plan.fuel());
        Line fuel = plan.materials().get(plan.materials().size() - 1);
        assertTrue(fuel.fuel());
        assertEquals(Material.BLAZE_POWDER, fuel.material());
        assertEquals(104, fuel.total());
        assertEquals(13, fuel.perStand());

        Plan oneStep = new Plan(new Recipe(Effect.WEAKNESS, Form.DRINK, Level.NORMAL), 128, 1);
        assertEquals(7, oneStep.fuel(), "128 brews: 6.4 powder");
        assertEquals(1, new Plan(new Recipe(Effect.WEAKNESS, Form.DRINK, Level.NORMAL), 20, 1).fuel());
        assertEquals(2, new Plan(new Recipe(Effect.WEAKNESS, Form.DRINK, Level.NORMAL), 21, 1).fuel());
    }

    @Test
    void timeIsEveryStepOfOneStandAsTheyAllBrewAtOnce() {
        Plan plan = new Plan(SPLASH_HEALING_II, 64, 8);
        assertEquals(256 * 20, plan.seconds());
        assertEquals(57, plan.shulkers(), "1536 potions, one a slot, 27 slots a box");
    }

    @Test
    void ingredientsAreCountedPerStandAndAltogether() {
        Plan plan = new Plan(SPLASH_HEALING_II, 64, 8);
        List<Line> lines = plan.materials();
        assertEquals(6, lines.size(), "bottles, four ingredients, fuel");
        for (Line line : lines.subList(1, 5)) {
            assertEquals(512, line.total());
            assertEquals(64, line.perStand());
            assertEquals(8, line.stacks());
        }
    }

    @Test
    void rawMaterialsWorkTheCraftedOnesBack() {
        List<Line> raw = new Plan(SPLASH_HEALING_II, 64, 8).raw();
        assertEquals(List.of(Material.GLASS, Material.NETHER_WART, Material.MELON_SLICE, Material.GLOWSTONE_DUST,
                Material.GUNPOWDER, Material.BLAZE_ROD, Material.GOLD_INGOT), raw.stream().map(Line::material).toList());
        assertEquals(1536, total(raw, Material.GLASS));
        assertEquals(512, total(raw, Material.MELON_SLICE));
        assertEquals(456, total(raw, Material.GOLD_INGOT), "8 nuggets a slice: 4096 nuggets, 455.1 ingots");
        assertEquals(52, total(raw, Material.BLAZE_ROD), "104 powder, two to a rod");

        // Strength takes blaze powder twice over: as its ingredient and as the fuel.
        List<Line> strength = new Plan(new Recipe(Effect.STRENGTH, Form.DRINK, Level.NORMAL), 64, 8).raw();
        assertEquals((512 + 56) / 2, total(strength, Material.BLAZE_ROD));

        List<Line> invisibility = new Plan(new Recipe(Effect.INVISIBILITY, Form.DRINK, Level.NORMAL), 10, 1).raw();
        assertEquals(10, total(invisibility, Material.SPIDER_EYE));
        assertEquals(10, total(invisibility, Material.BROWN_MUSHROOM));
        assertEquals(10, total(invisibility, Material.SUGAR_CANE));
        assertEquals(10, total(invisibility, Material.CARROT));
        assertEquals(9, total(invisibility, Material.GOLD_INGOT), "80 nuggets");

        List<Line> fire = new Plan(new Recipe(Effect.FIRE_RESISTANCE, Form.DRINK, Level.NORMAL), 10, 1).raw();
        assertEquals(10, total(fire, Material.SLIME_BALL));
        assertEquals(6, total(fire, Material.BLAZE_ROD), "10 powder in the cream and 1 burnt: 11, so 6 rods");
    }

    private static long total(List<Line> lines, Material material) {
        for (Line line : lines) {
            if (line.material() == material) {
                return line.total();
            }
        }
        return 0;
    }

    @Test
    void twentyThousandOnEightStandsComesOutInWholeStacks() {
        List<Suggestion> suggestions = Brewing.suggest(20_000, 8);
        assertEquals(3, suggestions.size());

        Suggestion above = suggestions.get(0);
        assertEquals(Fit.ABOVE, above.fit());
        assertEquals(840, above.perStand());
        assertEquals(20_160, above.potions());
        assertEquals(160, above.difference());

        Suggestion below = suggestions.get(1);
        assertEquals(Fit.BELOW, below.fit());
        assertEquals(832, below.perStand());
        assertEquals(19_968, below.potions());
        assertEquals(-32, below.difference());

        Suggestion full = suggestions.get(2);
        assertEquals(Fit.FULL_STACKS, full.fit());
        assertEquals(896, full.perStand(), "14 stacks of every ingredient in every stand");
        assertEquals(21_504, full.potions());

        for (Suggestion suggestion : suggestions) {
            assertEquals(0, suggestion.potions() % 64, suggestion.fit() + " comes to whole stacks of bottles");
        }
    }

    @Test
    void oneStandMovesInWholeStacksOfMats() {
        List<Suggestion> suggestions = Brewing.suggest(1000, 1);
        assertEquals(Fit.ABOVE, suggestions.get(0).fit());
        assertEquals(384, suggestions.get(0).perStand(), "64 at a time: 333.3 needed");
        assertEquals(1152, suggestions.get(0).potions());
        assertEquals(320, suggestions.get(1).perStand());
        assertEquals(2, suggestions.size(), "whole stacks of mats is the nearest above already");
    }

    @Test
    void anExactFitIsSaidOnce() {
        List<Suggestion> suggestions = Brewing.suggest(1536, 8);
        assertEquals(1, suggestions.size());
        assertEquals(64, suggestions.get(0).perStand());
        assertEquals(0, suggestions.get(0).difference());
        assertTrue(Brewing.suggest(0, 8).isEmpty());
        assertTrue(Brewing.suggest(100, 0).isEmpty());
        assertEquals(Fit.ABOVE, Brewing.suggest(10, 8).get(0).fit());
        List<Suggestion> few = Brewing.suggest(10, 8);
        assertEquals(List.of(Fit.ABOVE, Fit.FULL_STACKS), few.stream().map(Suggestion::fit).toList(),
                "nothing below 10 in whole stacks");
        assertEquals(8, few.get(0).perStand());
        assertEquals(64, few.get(1).perStand());
    }

    @Test
    void theStepMatsGoUpInForWholeStacks() {
        assertEquals(64, Brewing.stackStep(1));
        assertEquals(64, Brewing.stackStep(3));
        assertEquals(8, Brewing.stackStep(8));
        assertEquals(4, Brewing.stackStep(16));
        assertEquals(1, Brewing.stackStep(64));
        assertEquals(2, Brewing.stackStep(96));
        assertArrayEquals(new int[]{56, 64}, new Plan(SPLASH_HEALING_II, 63, 8).fullStackNeighbours());
        assertArrayEquals(new int[]{64, 64}, new Plan(SPLASH_HEALING_II, 64, 8).fullStackNeighbours());
        assertArrayEquals(new int[]{0, 64}, new Plan(SPLASH_HEALING_II, 10, 1).fullStackNeighbours());
        assertFalse(new Plan(SPLASH_HEALING_II, 63, 8).fullStacks());
    }
}
