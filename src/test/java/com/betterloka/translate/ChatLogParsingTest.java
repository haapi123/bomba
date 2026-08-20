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
    private static final int GREEN = 0x55FF55;
    private static final int AQUA = 0x55FFFF;

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
        assertNull(ChatLog.parse((String) null));
    }

    @Test
    void ignoresAnEmptyMessageBody() {
        assertNull(ChatLog.parse("Rezorie:   "));
    }

    /**
     * The regression that stopped town and alliance chat being read at all. Loka writes
     * {@code [Alliance] [Town] <icon> Nick: message}, and with a chat timestamp in front that runs
     * past fifty characters before the message starts — longer than the prefix the parser used to
     * allow, so anyone with a long name silently vanished from the Translator.
     */
    @Test
    void readsLokaTeamChatHoweverLongTheDecorationInFrontIs() {
        ChatLog.Line town = ChatLog.parse(ChatMessage.colored(
                "[21:56:36] [Sunspear] [Newgen] ✦ Mindmecraft: im going bed", GREEN));
        assertNotNull(town, "a timestamped town line is still someone speaking");
        assertEquals("Mindmecraft", town.sender());
        assertEquals("im going bed", town.message());
        assertEquals(ChatChannel.TOWN, town.channel());

        ChatLog.Line alliance = ChatLog.parse(ChatMessage.colored(
                "[21:56:54] [The Beasts] [Grimwall] ✦ wr3ck3rwanted: buggati u gona get 20v1", AQUA));
        assertNotNull(alliance, "fifty characters of decoration is still not an announcement");
        assertEquals("wr3ck3rwanted", alliance.sender());
        assertEquals("buggati u gona get 20v1", alliance.message());
        assertEquals(ChatChannel.ALLIANCE, alliance.channel());
    }

    @Test
    void doesNotMistakeATimestampsColonForTheSenderSeparator() {
        ChatLog.Line line = ChatLog.parse("[21:56:36] Rezorie: siema");
        assertNotNull(line);
        assertEquals("Rezorie", line.sender());
        assertEquals("siema", line.message());
    }

    @Test
    void publicChatKeepsItsChannel() {
        ChatLog.Line line = ChatLog.parse("Rezorie: siema");
        assertNotNull(line);
        assertEquals(ChatChannel.PUBLIC, line.channel());
    }

    @Test
    void takesTheChannelFromTheColourOfWhatWasSaid() {
        ChatLog.Line town = ChatLog.parse(ChatMessage.colored("Rezorie: bronimy zamku", GREEN));
        assertNotNull(town);
        assertEquals(ChatChannel.TOWN, town.channel());

        ChatLog.Line alliance = ChatLog.parse(ChatMessage.colored("Ghuraa: kto na fighta", AQUA));
        assertNotNull(alliance);
        assertEquals(ChatChannel.ALLIANCE, alliance.channel());
    }

    /** A coloured rank in an otherwise white line is decoration, not the channel. */
    @Test
    void ignoresAColouredWordInsideOrdinaryChat() {
        String text = "[Newgen] Rezorie: this is quite a long public message about nothing";
        int[] colors = new int[text.length()];
        java.util.Arrays.fill(colors, -1);
        java.util.Arrays.fill(colors, 0, "[Newgen]".length(), GREEN);

        ChatLog.Line line = ChatLog.parse(new ChatMessage(text, colors));
        assertNotNull(line);
        assertEquals(ChatChannel.PUBLIC, line.channel());
    }

    @Test
    void readsTeamChatWrittenWithAnArrowInsteadOfAColon() {
        ChatLog.Line line = ChatLog.parse(ChatMessage.colored("Rezorie » wchodzimy", GREEN));
        assertNotNull(line);
        assertEquals("Rezorie", line.sender());
        assertEquals("wchodzimy", line.message());
        assertEquals(ChatChannel.TOWN, line.channel());
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
