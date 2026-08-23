package com.betterloka.towns;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownInfoReaderTest {
    /** Duskfall's panel, line for line as Loka draws it. */
    private static final List<String> DUSKFALL = List.of(
            "§7lokaa",
            "§8----------------",
            "§aOwner: §fbleood123abc",
            "§aLevel: §f25",
            "§aMembers: §f69 §7(0 active)",
            "§aTerritories: §f0",
            "§aLocation: §fMesa of §6Garama",
            "§aFounded: §fMar 12, 2026",
            "",
            "§dVuln Window: §f9pm - 5am §7(in 2 hours 49 minutes)");

    @Test
    void readsTheActiveCountOffTheRealPanel() {
        TownInfoReading reading = TownInfoReader.parse(DUSKFALL, "Duskfall");

        assertNotNull(reading);
        assertEquals("Duskfall", reading.townName());
        assertEquals(69, reading.members());
        assertEquals(0, reading.active());
        assertTrue(reading.atZero());
        assertTrue(reading.hasActive());
    }

    @Test
    void aTownWithActiveMembersIsNotAtZero() {
        TownInfoReading reading = TownInfoReader.parse(
                List.of("§aOwner: §fsomebody", "§aMembers: §f120 §7(14 active)"), "Somewhere");

        assertNotNull(reading);
        assertEquals(120, reading.members());
        assertEquals(14, reading.active());
        assertFalse(reading.atZero());
    }

    @Test
    void aPanelWithoutAnOwnerIsNotATown() {
        // Loka has other panels that list members — an alliance's, for one. Recording one of those
        // under a town's name would put a number in the history Loka's timer has nothing to do with.
        assertNull(TownInfoReader.parse(List.of("§aMembers: §f40 §7(3 active)"), "Some Alliance"));
    }

    @Test
    void aMembersLineWithoutAnActiveCountIsStillRecorded() {
        TownInfoReading reading =
                TownInfoReader.parse(List.of("Owner: x", "Members: 40"), "Nameless");

        assertNotNull(reading);
        assertEquals(40, reading.members());
        assertEquals(-1, reading.active());
        assertFalse(reading.hasActive());
        assertFalse(reading.atZero());
    }

    @Test
    void loreThatIsNotATownPanelIsIgnored() {
        assertNull(TownInfoReader.parse(List.of("§7A shiny sword", "§7Sharpness V"), "Sword"));
        assertNull(TownInfoReader.parse(List.of(), "Nothing"));
        assertNull(TownInfoReader.parse(DUSKFALL, null));
        assertNull(TownInfoReader.parse(DUSKFALL, "  "));
    }
}
