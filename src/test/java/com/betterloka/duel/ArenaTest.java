package com.betterloka.duel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** At the arena: who the opponent is, and when the fight begins. */
class ArenaTest {
    @Test
    void theSidebarNamesThePlayerOnThisServer() {
        ArenaOpponent.Found found = ArenaOpponent.find("Me",
                List.of("§6§lRANKED POTION", "Me §7vs §cKnucior", "Map: Valley"),
                List.of(), List.of("Me", "Knucior", "Spectator1"));
        assertEquals("Knucior", found.name());
        assertEquals(ArenaOpponent.Source.SIDEBAR_PLAYER, found.source());
    }

    @Test
    void aLabelOnTheSidebarWhenTheyAreNotListedYet() {
        ArenaOpponent.Found found = ArenaOpponent.find("Me", List.of("Duel", "Opponent: Rival_1"), List.of(), List.of());
        assertEquals("Rival_1", found.name());
        assertEquals(ArenaOpponent.Source.SIDEBAR_LABEL, found.source());
    }

    @Test
    void theOnlyOtherPlayerNearbyOrOnTheServer() {
        assertEquals(ArenaOpponent.Source.NEARBY,
                ArenaOpponent.find("Me", List.of("Loka"), List.of("Me", "Rival"), List.of("Me", "Rival", "Other")).source());
        assertEquals("Rival", ArenaOpponent.find("me", List.of(), List.of(), List.of("Me", "Rival")).name());
    }

    @Test
    void moreThanOneCandidateIsNoAnswer() {
        assertNull(ArenaOpponent.find("Me", List.of("Top: A", "B"), List.of("A", "B"), List.of("Me", "A", "B")));
        assertNull(ArenaOpponent.find("Me", List.of(), List.of(), List.of("Me")), "alone: still waiting");
        assertNull(ArenaOpponent.find("Me", List.of("Me vs Me"), List.of(), List.of("Me")), "never themself");
    }

    @Test
    void aGoTitleStartsTheFight() {
        FightStart start = new FightStart();
        assertFalse(start.title("DUEL", 0), "the arrival is titled too");
        assertFalse(start.title("Round 1", 0), "a round number is no countdown");
        assertTrue(start.title("§cFIGHT!", 100));
        assertTrue(new FightStart().title("GO", 0));
    }

    @Test
    void aCountdownStartsItASecondAfterOne() {
        FightStart start = new FightStart();
        assertFalse(start.title("3", 0));
        assertFalse(start.title("2", 1000));
        assertFalse(start.title("1", 2000));
        assertFalse(start.poll(2900));
        assertTrue(start.poll(3000));
        FightStart words = new FightStart();
        assertFalse(words.chat("Match starting in 5 seconds", 0));
        assertTrue(words.chat("Starting in 0", 4000));
    }

    @Test
    void aChatLineSayingSo() {
        assertTrue(new FightStart().chat("The match has started!", 0));
        assertTrue(new FightStart().chat("Fight!", 0));
        assertFalse(new FightStart().chat("Knucior: wanna fight at spawn later?", 0), "somebody talking");
        assertFalse(new FightStart().chat("Waiting for your opponent...", 0));
    }
}
