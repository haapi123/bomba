package com.betterloka.translate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Loka does not label its channels — town chat is simply green and alliance chat light blue — so the
 * colour is the only thing that tells them apart, and misreading it files a message under the wrong
 * conversation.
 */
class ChatChannelTest {

    @Test
    void readsTheVanillaFormattingColours() {
        assertEquals(ChatChannel.TOWN, ChatChannel.ofColor(0x55FF55), "§a — town");
        assertEquals(ChatChannel.ALLIANCE, ChatChannel.ofColor(0x55FFFF), "§b — alliance");
    }

    @Test
    void readsTheDarkerShadesOfTheSameTwoColours() {
        assertEquals(ChatChannel.TOWN, ChatChannel.ofColor(0x00AA00));
        assertEquals(ChatChannel.ALLIANCE, ChatChannel.ofColor(0x00AAAA));
        assertEquals(ChatChannel.ALLIANCE, ChatChannel.ofColor(0x5555FF),
                "servers differ over whether alliance chat is aqua or blue");
    }

    @Test
    void toleratesHandPickedShadesNearTheVanillaOnes() {
        assertEquals(ChatChannel.TOWN, ChatChannel.ofColor(0x4CE04C));
        assertEquals(ChatChannel.ALLIANCE, ChatChannel.ofColor(0x62D8E8));
    }

    @Test
    void leavesOrdinaryChatAlone() {
        assertEquals(ChatChannel.PUBLIC, ChatChannel.ofColor(0xFFFFFF), "white");
        assertEquals(ChatChannel.PUBLIC, ChatChannel.ofColor(0xAAAAAA), "grey");
        assertEquals(ChatChannel.PUBLIC, ChatChannel.ofColor(0xFFFF55), "yellow");
        assertEquals(ChatChannel.PUBLIC, ChatChannel.ofColor(0xFF5555), "red");
        assertEquals(ChatChannel.PUBLIC, ChatChannel.ofColor(0xFFAA00), "gold");
        assertEquals(ChatChannel.PUBLIC, ChatChannel.ofColor(0x000000), "black");
    }
}
