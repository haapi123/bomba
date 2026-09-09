package com.betterloka.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The scale values the option offers, and how the button steps through them. */
class GuiScaleTest {
    @Test
    void theCycleRunsAutoThroughFourAndBack() {
        assertEquals(1, GuiScaleController.next(GuiScaleController.AUTO));
        assertEquals(2, GuiScaleController.next(1));
        assertEquals(3, GuiScaleController.next(2));
        assertEquals(4, GuiScaleController.next(3));
        assertEquals(GuiScaleController.AUTO, GuiScaleController.next(4),
                "past the highest it wraps to Auto, as the vanilla option does");
    }

    @Test
    void autoIsNamedRatherThanNumbered() {
        assertEquals("Auto", GuiScaleController.label(GuiScaleController.AUTO));
        assertEquals("3", GuiScaleController.label(3));
        assertEquals("4", GuiScaleController.label(GuiScaleController.MAX_SCALE));
    }

    @Test
    void theHighestIsTheOneMinecraftOffers() {
        assertEquals(4, GuiScaleController.MAX_SCALE);
        assertEquals(0, GuiScaleController.AUTO, "Auto is zero, the way the game stores it");
    }
}
