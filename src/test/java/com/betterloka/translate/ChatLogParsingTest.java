package com.betterloka.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Translator only shows what people actually said. Servers push announcements, join notices and
 * command output down the same channel, and translating those buries the conversation, so the parser
 * has to tell one from the other.
 */
class ChatLogParsingTest {

    @Test
    void readsLokaChatWithRankAndTownDecoration() {
        ChatLog.Line line = ChatLog.parse("Duelist Rezorie: idziemy na fighta o 20?");
        assertNotNull(line);
        assertEquals("Rezorie", line.sender(), "the rank prefix is decoration, not part of the name");
        assertEquals("idziemy na fighta o 20?", line.message());
    }

    @Test
    void readsBracketedTownTags() {
        ChatLog.Line line = ChatLog.parse("[New Silverhand] Ghuraa: anyone up for a fight");
        assertNotNull(line);
        assertEquals("Ghuraa", line.sender());
        assertEquals("anyone up for a fight", line.message());
    }

    @Test
    void readsVanillaAngleBrackets() {
        ChatLog.Line line = ChatLog.parse("<Cryptite> welcome back");
        assertNotNull(line);
        assertEquals("Cryptite", line.sender());
        assertEquals("welcome back", line.message());
    }

    @Test
    void keepsColonsInsideTheMessage() {
        ChatLog.Line line = ChatLog.parse("Rezorie: meet at 20:30 by the gate");
        assertNotNull(line);
        assertEquals("Rezorie", line.sender());
        assertEquals("meet at 20:30 by the gate", line.message(),
                "only the first colon separates the sender");
    }

    @Test
    void ignoresServerNoise() {
        assertNull(ChatLog.parse("Rezorie joined the game"));
        assertNull(ChatLog.parse("[Server] Conquest starts in 5 minutes"),
                "no name-shaped token before a colon means this is not someone speaking");
        assertNull(ChatLog.parse("You have been given 3 Ancient Ingots"));
        assertNull(ChatLog.parse(""));
        assertNull(ChatLog.parse(null));
    }

    @Test
    void ignoresAnEmptyMessageBody() {
        assertNull(ChatLog.parse("Rezorie:   "));
    }

    @Test
    void publicChatKeepsItsChannel() {
        ChatLog.Line line = ChatLog.parse("Rezorie: siema");
        assertNotNull(line);
        assertEquals(ChatChannel.PUBLIC, line.channel());
    }

    @Test
    void remembersWhichChannelTheColourIdentified() {
        ChatLog.Line town = ChatLog.parse("Rezorie: bronimy zamku", ChatChannel.TOWN);
        assertNotNull(town);
        assertEquals(ChatChannel.TOWN, town.channel());
        assertEquals("Rezorie", town.sender());
        assertEquals("bronimy zamku", town.message());

        ChatLog.Line alliance = ChatLog.parse("Ghuraa: kto na fighta", ChatChannel.ALLIANCE);
        assertNotNull(alliance);
        assertEquals(ChatChannel.ALLIANCE, alliance.channel());
    }

    @Test
    void readsTeamChatWrittenWithAnArrowInsteadOfAColon() {
        ChatLog.Line line = ChatLog.parse("Rezorie » wchodzimy", ChatChannel.TOWN);
        assertNotNull(line);
        assertEquals("Rezorie", line.sender());
        assertEquals("wchodzimy", line.message());
    }

    @Test
    void doesNotTreatAnArrowInPublicChatAsASeparator() {
        assertNull(ChatLog.parse("Rezorie » wchodzimy"),
                "in public chat an arrow is far more likely to be part of what was said");
    }

    @Test
    void readsAChannelTagAndTakesItOffTheMessage() {
        ChatLog.Line town = ChatLog.parse("[TC] Rezorie: idziemy");
        assertNotNull(town);
        assertEquals(ChatChannel.TOWN, town.channel());
        assertEquals("Rezorie", town.sender());
        assertEquals("idziemy", town.message());

        ChatLog.Line alliance = ChatLog.parse("[Alliance] Ghuraa: idziemy");
        assertNotNull(alliance);
        assertEquals(ChatChannel.ALLIANCE, alliance.channel());
        assertEquals("Ghuraa", alliance.sender());
    }

    @Test
    void doesNotMistakeATownTagForAChannelTag() {
        ChatLog.Line line = ChatLog.parse("[New Silverhand] Ghuraa: anyone up for a fight");
        assertNotNull(line);
        assertEquals(ChatChannel.PUBLIC, line.channel());
        assertEquals("Ghuraa", line.sender());
    }
}
