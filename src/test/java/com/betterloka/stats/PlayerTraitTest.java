package com.betterloka.stats;

import com.betterloka.api.ArenaApi;
import com.betterloka.api.model.ArenaEntry;
import com.betterloka.api.model.ArenaRank;
import com.betterloka.api.model.EldritchStats;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The chips are a judgement on a person's play, shown next to their name, so the line between "bad
 * at this" and "does not do this" has to hold: an absent chip and a red one say different things and
 * only one of them is an accusation.
 */
class PlayerTraitTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    @Test
    void colorsKillDeathAtTheStatedCuts() {
        assertEquals(PlayerTrait.Level.GOOD, level(traitFor(stats(310, 100)), PlayerTrait.Kind.KILL_DEATH));
        assertEquals(PlayerTrait.Level.MIXED, level(traitFor(stats(300, 100)), PlayerTrait.Kind.KILL_DEATH),
                "exactly 3.00 is not above 3.00");
        assertEquals(PlayerTrait.Level.MIXED, level(traitFor(stats(100, 100)), PlayerTrait.Kind.KILL_DEATH),
                "exactly 1.00 counts as the middle band");
        assertEquals(PlayerTrait.Level.POOR, level(traitFor(stats(99, 100)), PlayerTrait.Kind.KILL_DEATH));
    }

    @Test
    void hasNoKillDeathChipForSomeoneWhoHasNeverFought() {
        assertNull(find(traitFor(stats(0, 0)), PlayerTrait.Kind.KILL_DEATH));
    }

    @Test
    void readsActivityOffTheRecentFightDates() {
        // The list is capped, so a full list of recent fights is the most that can be claimed.
        assertEquals(PlayerTrait.Level.GOOD,
                level(traitFor(withFights(stats(100, 50), "8/19", "8/18", "8/17", "8/16", "8/15",
                        "8/14", "8/13", "8/12", "8/11")), PlayerTrait.Kind.ACTIVITY));

        assertEquals(PlayerTrait.Level.MIXED,
                level(traitFor(withFights(stats(100, 50), "8/19", "8/12", "3/2", "2/1")),
                        PlayerTrait.Kind.ACTIVITY));

        assertEquals(PlayerTrait.Level.POOR,
                level(traitFor(withFights(stats(100, 50), "3/2", "2/1")), PlayerTrait.Kind.ACTIVITY));
    }

    @Test
    void datesWithoutAYearRollBackRatherThanIntoTheFuture() {
        assertEquals(LocalDate.of(2026, 8, 12), PlayerTrait.parseDate("8/12", TODAY));
        assertEquals(LocalDate.of(2025, 12, 30), PlayerTrait.parseDate("12/30", TODAY),
                "December cannot be four months from now");
        assertNull(PlayerTrait.parseDate("nonsense", TODAY));
        assertNull(PlayerTrait.parseDate("13/40", TODAY));
    }

    @Test
    void ratesChargeTakingByHowMuchTheyTakePerFight() {
        // 0.70 and up, the top of the real spread.
        assertEquals(PlayerTrait.Level.GOOD, level(traitFor(charges(100, 40, 30)), PlayerTrait.Kind.CHARGE));
        assertEquals(PlayerTrait.Level.MIXED, level(traitFor(charges(100, 20, 10)), PlayerTrait.Kind.CHARGE));
        assertEquals(PlayerTrait.Level.POOR, level(traitFor(charges(100, 5, 5)), PlayerTrait.Kind.CHARGE));
        assertEquals(PlayerTrait.Level.POOR, level(traitFor(charges(100, 0, 0)), PlayerTrait.Kind.CHARGE),
                "someone with a hundred fights and no charges has chosen not to take them");
    }

    @Test
    void saysNothingAboutChargeWithoutEnoughFightsToJudge() {
        assertNull(find(traitFor(charges(4, 0, 0)), PlayerTrait.Kind.CHARGE),
                "four fights is not a habit either way");
    }

    @Test
    void ratesDuelsAgainstTheLadderTheyStandHighestOn() {
        assertEquals(PlayerTrait.Level.GOOD, duelLevel("Netherite I"));
        assertEquals(PlayerTrait.Level.GOOD, duelLevel("Bedrock"));
        assertEquals(PlayerTrait.Level.MIXED, duelLevel("Diamond III"), "Diamond III is the top of the middle");
        assertEquals(PlayerTrait.Level.MIXED, duelLevel("Emerald I"), "Emerald I is the bottom of the middle");
        assertEquals(PlayerTrait.Level.POOR, duelLevel("Gold III"));
    }

    @Test
    void hasNoDuelChipForSomeoneWhoDoesNotDuel() {
        PlayerProfile profile = profile(stats(100, 50));
        assertNull(find(PlayerTrait.of(profile, List.of(
                new ArenaService.Standing(ArenaApi.Ladder.POTION, null, null),
                new ArenaService.Standing(ArenaApi.Ladder.BAREBONES, null, null)), TODAY),
                PlayerTrait.Kind.DUELS));
        assertNull(find(PlayerTrait.of(profile, null, TODAY), PlayerTrait.Kind.DUELS),
                "the ladders not having loaded yet is not a bad rank either");
    }

    @Test
    void keepsTheChipsInAFixedOrder() {
        List<PlayerTrait> traits = PlayerTrait.of(profile(withFights(charges(100, 40, 30), "8/19")),
                List.of(new ArenaService.Standing(ArenaApi.Ladder.POTION, entry("Bedrock"), null)), TODAY);
        assertEquals(List.of(PlayerTrait.Kind.KILL_DEATH, PlayerTrait.Kind.ACTIVITY,
                        PlayerTrait.Kind.CHARGE, PlayerTrait.Kind.DUELS),
                traits.stream().map(PlayerTrait::kind).toList());
    }

    private PlayerTrait.Level duelLevel(String rank) {
        List<PlayerTrait> traits = PlayerTrait.of(profile(stats(100, 50)),
                List.of(new ArenaService.Standing(ArenaApi.Ladder.BAREBONES, entry("Gold I"), null),
                        new ArenaService.Standing(ArenaApi.Ladder.POTION, entry(rank), null)), TODAY);
        return level(traits, PlayerTrait.Kind.DUELS);
    }

    private static ArenaEntry entry(String rank) {
        return new ArenaEntry("someone", null, 10, 5, ArenaRank.parse(rank), 0, 0, 12);
    }

    private static List<PlayerTrait> traitFor(EldritchStats stats) {
        return PlayerTrait.of(profile(stats), List.of(), TODAY);
    }

    private static PlayerTrait.Level level(List<PlayerTrait> traits, PlayerTrait.Kind kind) {
        PlayerTrait trait = find(traits, kind);
        return trait == null ? null : trait.level();
    }

    private static PlayerTrait find(List<PlayerTrait> traits, PlayerTrait.Kind kind) {
        for (PlayerTrait trait : traits) {
            if (trait.kind() == kind) {
                return trait;
            }
        }
        return null;
    }

    private static PlayerProfile profile(EldritchStats stats) {
        return new PlayerProfile("someone", null, null, null, null, null, stats, null, false,
                List.of(), PlayerProfile.FightsState.READY);
    }

    private static EldritchStats stats(int kills, int deaths) {
        return record(kills, deaths, 0, 0, 0, 0, List.of());
    }

    private static EldritchStats charges(int fights, int golems, int lamps) {
        return record(10, 10, fights, 0, golems, lamps, List.of());
    }

    private static EldritchStats withFights(EldritchStats base, String... dates) {
        List<EldritchStats.RecentFight> fights = new ArrayList<>();
        for (String date : dates) {
            fights.add(new EldritchStats.RecentFight("id" + fights.size(), date, true, "A", "B", "A"));
        }
        return record(base.kills(), base.deaths(), base.wins(), base.losses(), base.golems(),
                base.lamps(), fights);
    }

    private static EldritchStats record(int kills, int deaths, int wins, int losses, int golems,
                                        int lamps, List<EldritchStats.RecentFight> fights) {
        return new EldritchStats("someone", null, "Newgen", "Wed Aug 19 2026", kills, deaths, 0,
                0, 0, 0, 0, wins, losses, golems, lamps, 0, 0, null, 0, fights);
    }
}
