package com.betterloka.lore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoreGeneratorTest {
    private static LoreGenerator.Request request(String description, boolean polish, boolean ownText, long seed) {
        return new LoreGenerator.Request(description, "", null, LoreScheme.FIRE, polish, 3, ownText,
                true, true, false, LoreGenerator.Rarity.LEGENDARY, seed);
    }

    @Test
    void motifsAreFoundInEitherLanguage() {
        assertEquals(LoreMotif.FIRE, LoreMotif.in("A sword forged in dragon fire").get(0));
        assertEquals(LoreMotif.FIRE, LoreMotif.in("Miecz wykuty w smoczym ogniu").get(0));
        assertEquals(LoreMotif.ICE, LoreMotif.in("Lodowy łuk z północy, pełen mrozu").get(0));
        assertTrue(LoreMotif.in("zwykły patyk").isEmpty());
        assertTrue(LoreMotif.in("a tlen tank").isEmpty(), "words are matched from their start");
    }

    @Test
    void theLoreIsTitleRuleTextRuleRarity() {
        String lore = LoreGenerator.write(request("Miecz wykuty w smoczym ogniu", true, false, 1));
        List<List<MiniMessage.Span>> lines = MiniMessage.parse(lore);
        assertEquals("Ostrze Żaru", MiniMessage.plain(lines.get(0)));
        assertTrue(lore.lines().allMatch(line -> line.startsWith("<!i>")), "no italics was asked for");
        String last = MiniMessage.plain(lines.get(lines.size() - 1));
        assertEquals("« LEGENDARY »", last);
        for (List<MiniMessage.Span> line : lines) {
            assertTrue(MiniMessage.plain(line).length() <= 34, "every line fits a tooltip: " + MiniMessage.plain(line));
        }
    }

    @Test
    void anotherSeedIsAnotherLoreAndTheSameSeedTheSame() {
        String a = LoreGenerator.write(request("dragon fire", false, false, 1));
        String b = LoreGenerator.write(request("dragon fire", false, false, 1));
        assertEquals(a, b);
        boolean differs = false;
        for (long seed = 2; seed < 8 && !differs; seed++) {
            differs = !a.equals(LoreGenerator.write(request("dragon fire", false, false, seed)));
        }
        assertTrue(differs);
    }

    @Test
    void ownTextIsKeptAndEscaped() {
        String lore = LoreGenerator.write(request("Made by <Haapi> for the war of 2026", false, true, 1));
        String all = String.join(" ", MiniMessage.parse(lore).stream().map(MiniMessage::plain).toList());
        assertTrue(all.contains("Made by <Haapi> for the war of 2026"), all);
    }

    @Test
    void wrapBreaksAtSpaces() {
        assertEquals(List.of("one two", "three"), LoreGenerator.wrap("one two three", 8));
    }
}
