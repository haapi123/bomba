package com.betterloka.lore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMessageTest {
    @Test
    void namedAndHexColoursAndDecorations() {
        List<List<MiniMessage.Span>> lines = MiniMessage.parse("<red>Hot <bold>and</bold></red> <#00ff00>green");
        List<MiniMessage.Span> line = lines.get(0);
        assertEquals("Hot ", line.get(0).text());
        assertEquals(0xFF5555, line.get(0).color());
        assertNull(line.get(0).bold());
        assertEquals("and", line.get(1).text());
        assertEquals(Boolean.TRUE, line.get(1).bold());
        assertEquals(-1, line.get(2).color(), "outside every colour tag the colour is inherited");
        assertEquals(0x00FF00, line.get(3).color());
        assertEquals("Hot and green", MiniMessage.plain(line));
    }

    @Test
    void negationsAndReset() {
        List<MiniMessage.Span> line = MiniMessage.parse("<!i><gold>No slant<reset> plain").get(0);
        assertEquals(Boolean.FALSE, line.get(0).italic());
        assertEquals(0xFFAA00, line.get(0).color());
        assertEquals(-1, line.get(1).color());
        assertNull(line.get(1).italic());
        assertEquals(Boolean.FALSE, MiniMessage.parse("<bold:false>x").get(0).get(0).bold());
    }

    @Test
    void aGradientRunsFromItsFirstColourToItsLast() {
        List<MiniMessage.Span> line = MiniMessage.parse("<gradient:#ff0000:#0000ff>abcde</gradient>").get(0);
        assertEquals(5, line.size(), "each letter its own colour");
        assertEquals(0xFF0000, line.get(0).color());
        assertEquals(0x0000FF, line.get(4).color());
        int middle = line.get(2).color();
        assertTrue(((middle >> 16) & 0xFF) > 0 && (middle & 0xFF) > 0, "half way is between the two");
    }

    @Test
    void aRainbowChangesColourAlongTheText() {
        List<MiniMessage.Span> line = MiniMessage.parse("<rainbow>abc</rainbow>").get(0);
        assertNotEquals(line.get(0).color(), line.get(1).color());
    }

    @Test
    void newlinesSplitLinesAndUnknownTagsStayAsText() {
        List<List<MiniMessage.Span>> lines = MiniMessage.parse("one<newline>two\nthree<br><blink>four");
        assertEquals(4, lines.size());
        assertEquals("<blink>four", MiniMessage.plain(lines.get(3)));
        assertEquals("a <b", MiniMessage.plain(MiniMessage.parse("a \\<b").get(0)), "an escaped bracket is text");
    }

    @Test
    void transparentTagsKeepTheirText() {
        List<MiniMessage.Span> line = MiniMessage.parse("<hover:show_text:'hi'><aqua>Look</aqua></hover>").get(0);
        assertEquals("Look", MiniMessage.plain(line));
        assertEquals(0x55FFFF, line.get(0).color());
    }
}
