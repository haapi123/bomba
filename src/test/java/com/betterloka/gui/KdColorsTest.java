package com.betterloka.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The K/D bands, and the rules the table has to keep. */
class KdColorsTest {
    @Test
    void theRampRunsRedToGreenAcrossZeroToFive() {
        assertEquals(0xC93030, KdColors.getKdColor(0.0));
        assertEquals(0xC93030, KdColors.getKdColor(0.49));
        assertEquals(0xDD5A2B, KdColors.getKdColor(0.50));
        assertEquals(0xE2702C, KdColors.getKdColor(1.00));
        assertEquals(0xF0BE3A, KdColors.getKdColor(2.00));
        assertEquals(0x86C951, KdColors.getKdColor(3.50));
        assertEquals(0x3FB562, KdColors.getKdColor(5.00));
    }

    /** The thresholds the brief names: above five, above ten, above twenty. */
    @Test
    void pastFiveItLeavesTheRamp() {
        assertEquals(0x3FB562, KdColors.getKdColor(5.00), "five is still the top of the green");
        assertEquals(0x4FB0FF, KdColors.getKdColor(5.01), "past five is blue");
        assertEquals(0x2E8BFF, KdColors.getKdColor(10.00));
        assertEquals(0xB478FF, KdColors.getKdColor(10.01), "past ten is purple");
        assertEquals(0x9B5CFF, KdColors.getKdColor(20.00));
        assertEquals(0xFFD24A, KdColors.getKdColor(20.01), "past twenty is gold");
        assertEquals(0xFFD24A, KdColors.getKdColor(500.0));
    }

    /**
     * A band is matched on the number as it is drawn, so what is on screen and the colour it is in
     * can never disagree.
     */
    @Test
    void theBandFollowsTheDisplayedValue() {
        // 0.495 renders as "0.50" at two decimals, so it takes the 0.50 band.
        assertEquals(KdColors.getKdColor(0.50), KdColors.getKdColor(0.495));
        assertEquals(KdColors.getKdColor(0.49), KdColors.getKdColor(0.4949));
    }

    @Test
    void everyBandIsDistinctAndTheTableClimbs() {
        KdColors.Band[] bands = KdColors.bands();
        for (int i = 1; i < bands.length; i++) {
            assertTrue(bands[i].upTo() > bands[i - 1].upTo(), "bands must be ordered");
            assertNotEquals(bands[i].hex(), bands[i - 1].hex(), "neighbours must differ");
        }
    }

    /** Every colour has to carry on Minecraft's dark background. */
    @Test
    void everyColourIsLightEnoughToReadOnBlack() {
        for (KdColors.Band band : KdColors.bands()) {
            // Vanilla's own DARK_RED measures 0.055 and is legible with a text shadow, so this is
            // already stricter than the game holds itself to.
            assertTrue(luminance(band.hex()) > 0.12,
                    () -> String.format("%s is too dark on black", KdColors.hexOf(band.upTo())));
        }
    }

    @Test
    void theScreenColourIsTheSameOneWithAlpha() {
        assertEquals(0xFF000000 | KdColors.getKdColor(2.0), KdColors.getKdColorArgb(2.0));
        assertEquals(0xFF3FB562, KdColors.getKdColorArgb(5.0));
    }

    @Test
    void guiThemeUsesTheSameTable() {
        assertEquals(KdColors.getKdColor(7.0), GuiTheme.nameplateRatioColor(7.0));
        assertEquals(KdColors.getKdColorArgb(7.0), GuiTheme.ratioColor(7.0));
    }

    @Test
    void hexReadsBackTheWayTheTableIsWritten() {
        assertEquals("#C93030", KdColors.hexOf(0.1));
        assertEquals("#FFD24A", KdColors.hexOf(25.0));
    }

    /** A ratio of zero deaths and zero kills arrives as NaN, and must not throw. */
    @Test
    void aRatioThatIsNotANumberFallsToTheBottomBand() {
        assertEquals(0xC93030, KdColors.getKdColor(Double.NaN));
    }

    /** Relative luminance, the sRGB one, for the readability check above. */
    private static double luminance(int hex) {
        double r = channel((hex >> 16) & 0xFF);
        double g = channel((hex >> 8) & 0xFF);
        double b = channel(hex & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
