package com.betterloka.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a travelling zoom has to do, frame by frame rather than only at its two ends.
 *
 * <p>Every check here is written against the one thing a person actually sees: where a fixed piece
 * of ground sits on screen. A zoom that arrives at the right number while sliding the ground
 * sideways on the way is the failure this is for, and it is invisible to any test that only looks at
 * the start and the finish.
 */
class ZoomAnimationTest {
    private static final double TAU = 0.055;

    /** Where a world point sits on screen, in pixels right of the middle. */
    private static double screenOffset(ZoomAnimation view, double worldX) {
        return (worldX - view.centerX()) / view.zoom();
    }

    /** Runs a zoom to a stop, at a steady sixty frames a second. */
    private static int runToRest(ZoomAnimation view, Runnable eachFrame) {
        int frames = 0;
        while (view.advance(1 / 60.0, TAU) && frames < 600) {
            frames++;
            eachFrame.run();
        }
        return frames;
    }

    @Test
    void theGroundUnderTheCursorDoesNotMoveWhileTheZoomRuns() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        double anchorX = 1240;
        double anchorZ = 2160;
        view.zoomAbout(8 / 1.25, anchorX, anchorZ);

        double wanted = screenOffset(view, anchorX);
        runToRest(view, () -> assertEquals(wanted, screenOffset(view, anchorX), 1e-6,
                "the point being zoomed at slid on screen part-way through"));
        assertEquals(wanted, screenOffset(view, anchorX), 1e-6);
    }

    /**
     * The case a click-and-click-again produces, and the one the first version of this got wrong.
     *
     * <p>Aiming the second zoom from where the first was heading rather than from where the map has
     * actually reached moves the middle by the distance between the two anchors, all at once, on the
     * frame of the click.
     */
    @Test
    void aSecondClickMidZoomDoesNotJumpTheView() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(8 / 1.25, 1240, 2160);
        for (int frame = 0; frame < 3; frame++) {
            view.advance(1 / 60.0, TAU);
        }

        double beforeX = view.centerX();
        double beforeZ = view.centerZ();
        // A second click somewhere else entirely, which is what a moved cursor gives.
        view.zoomAbout(view.targetZoom() / 1.25, 700, 2500);

        assertEquals(beforeX, view.centerX(), 1e-9, "the view jumped east or west on the click");
        assertEquals(beforeZ, view.centerZ(), 1e-9, "the view jumped north or south on the click");

        // And from there the new anchor is the one held still.
        double wanted = screenOffset(view, 700);
        runToRest(view, () -> assertEquals(wanted, screenOffset(view, 700), 1e-6));
    }

    @Test
    void stepsAccumulateSoQuickClicksTravelTheWholeWay() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        for (int click = 0; click < 5; click++) {
            view.zoomAbout(view.targetZoom() / 1.25, 1240, 2160);
        }
        assertEquals(8 / Math.pow(1.25, 5), view.targetZoom(), 1e-9);
        runToRest(view, () -> { });
        assertEquals(view.targetZoom(), view.zoom(), 1e-12, "it never actually arrived");
    }

    /** It arrives, exactly, and then stops reporting movement so nothing redraws forever. */
    @Test
    void itSettlesRatherThanCreepingTowardsTheTarget() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(2, 1000, 2000);
        int frames = runToRest(view, () -> { });

        assertEquals(2, view.zoom(), 1e-12);
        assertEquals(2, view.targetZoom(), 1e-12);
        assertTrue(frames > 1 && frames < 60,
                "a zoom took " + frames + " frames; it should be a fraction of a second");
        // Once at rest it reports nothing moving, however long the frame.
        assertTrue(!view.advance(1 / 60.0, TAU) && !view.advance(0.25, TAU));
    }

    /** Most of the journey is done quickly, so a click reads as a click and not as a glide. */
    @Test
    void mostOfTheTravelHappensInTheFirstFewFrames() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(4, 1000, 2000);
        for (int frame = 0; frame < 9; frame++) {
            view.advance(1 / 60.0, TAU);
        }
        // Nine frames is about 150 ms, which is roughly three time constants.
        double travelled = (Math.log(8) - Math.log(view.zoom())) / (Math.log(8) - Math.log(4));
        assertTrue(travelled > 0.9, "only " + travelled + " of the way after 150 ms");
    }

    /** A stalled frame must not be spent as travel, or the map lurches when the game hitches. */
    @Test
    void aLongFrameDoesNotLurchTheWholeWay() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(0.5, 1000, 2000);
        view.advance(30, TAU);
        // It may finish — thirty seconds is long — but it must not overshoot or go backwards.
        assertTrue(view.zoom() >= 0.5 && view.zoom() <= 8, "zoom left the range at " + view.zoom());
    }

    /** A drag has to track the hand, so it takes the middle outright and ends any zoom running. */
    @Test
    void aDragEndsTheZoomWhereItStandsAndTakesTheMiddle() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(4, 1240, 2160);
        view.advance(1 / 60.0, TAU);
        double mid = view.zoom();
        assertNotEquals(4, mid, "the test needs a zoom still in flight");

        view.moveTo(1500, 2500);
        assertEquals(mid, view.zoom(), 1e-12, "the drag changed the zoom");
        assertEquals(mid, view.targetZoom(), 1e-12, "a zoom was left running under the drag");
        assertEquals(1500, view.centerX(), 1e-12);
        assertEquals(2500, view.centerZ(), 1e-12);
    }

    /** A view put somewhere outright — reopening a continent — has nothing left running. */
    @Test
    void jumpingLeavesNothingInFlight() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(4, 1240, 2160);
        view.jumpTo(16, 3000, 4000);

        assertEquals(16, view.zoom(), 1e-12);
        assertEquals(16, view.targetZoom(), 1e-12);
        assertEquals(3000, view.centerX(), 1e-12);
        assertEquals(4000, view.centerZ(), 1e-12);
        assertTrue(!view.advance(1 / 60.0, TAU));
    }

    /** Saving a view mid-zoom stores where it was going, not the frame it was caught on. */
    @Test
    void theTargetIsWhatAViewIsRememberedAs() {
        ZoomAnimation view = new ZoomAnimation(8, 1000, 2000);
        view.zoomAbout(4, 1240, 2160);
        view.advance(1 / 60.0, TAU);

        assertEquals(4, view.targetZoom(), 1e-12);
        assertNotEquals(view.centerX(), view.targetCenterX(),
                "the test needs the drawn middle to differ from the one saved");
        // And reopening at what was saved lands where the zoom was headed.
        ZoomAnimation reopened = new ZoomAnimation(view.targetZoom(),
                view.targetCenterX(), view.targetCenterZ());
        runToRest(view, () -> { });
        assertEquals(view.centerX(), reopened.centerX(), 1e-9);
        assertEquals(view.zoom(), reopened.zoom(), 1e-12);
    }
}
