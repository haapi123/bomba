package com.betterloka.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The sanitiser every string out of Loka's map goes through. */
class HtmlTextTest {
    @Test
    void tagsAreRemovedAndBreaksBecomeSpaces() {
        assertEquals("Owner: Corvus", HtmlText.plain("<small>Owner: Corvus</small>"));
        assertEquals("Cherry Grove 129", HtmlText.plain("<br/>Cherry Grove<br/>129<br/>"));
        assertEquals("Lone Wolf", HtmlText.plain("<h3><b>Lone Wolf</b></h3>"));
        assertEquals("Moor 56", HtmlText.plain("<BR>Moor<br />56<Br/>"));
    }

    @Test
    void entitiesAreDecoded() {
        assertEquals("Aqronso's Town", HtmlText.plain("Aqronso&#39;s Town"));
        assertEquals("Bell & Sons", HtmlText.plain("Bell &amp; Sons"));
        assertEquals("\"Quoted\"", HtmlText.plain("&quot;Quoted&quot;"));
        assertEquals("A–B", HtmlText.plain("A&ndash;B"));
        assertEquals("é", HtmlText.plain("&#xe9;"));
    }

    /**
     * Escaped brackets are text somebody meant to be read.
     *
     * <p>Decoding before stripping would turn this into a tag and then delete it, so a town called
     * {@code <Void>} would come out empty.
     */
    @Test
    void escapedBracketsSurviveAsText() {
        assertEquals("<Void>", HtmlText.plain("&lt;Void&gt;"));
    }

    @Test
    void anEntityNobodyKnowsIsLeftAlone() {
        assertEquals("&frobnicate; x", HtmlText.plain("&frobnicate; x"));
    }

    @Test
    void nothingLeftMeansAbsent() {
        assertNull(HtmlText.plain(null));
        assertNull(HtmlText.plain("   "));
        assertNull(HtmlText.plain("<small><br/></small>"));
    }

    @Test
    void runsOfWhitespaceCollapse() {
        assertEquals("Grand Daselia", HtmlText.plain("  Grand   \n Daselia  "));
    }
}
