package com.betterloka.map;

/**
 * A zoom that travels to where it was sent rather than arriving instantly, about a fixed point.
 *
 * <p>Pulled out of the map screen so the arithmetic can be tested. What it has to get right is one
 * thing, and it is not obvious: the point being zoomed at must sit still on screen for every frame
 * of the journey, not merely at the two ends. Easing the middle towards its destination alongside
 * the zoom does not do that — the two travel at different rates and the ground slides out from under
 * the cursor and back — so the middle is derived from the zoom each frame instead.
 *
 * <p>The travel itself runs on the logarithm of the zoom. A zoom is a multiplication, so easing the
 * number would crawl at the wide end and lurch at the close one; easing its logarithm makes the
 * ground appear to grow at one steady rate, which is what reads as smooth.
 *
 * <p>Everything here is in blocks and blocks per pixel. It knows nothing about screens.
 */
public final class ZoomAnimation {
    /** Close enough to the destination to sit exactly on it and stop. */
    private static final double SETTLED = 1e-4;

    /** A frame longer than this is a stall, and must not be treated as that much travel. */
    private static final double MAX_FRAME_SECONDS = 0.25;

    /** Where the zoom is now, and where it is going. */
    private double zoom;
    private double targetZoom;

    /** The middle it is going to. Where the middle is now comes from {@link #centerX()}. */
    private double centerX;
    private double centerZ;

    /** The world point held still while the zoom runs. */
    private double anchorX;
    private double anchorZ;

    public ZoomAnimation(double zoom, double centerX, double centerZ) {
        this.zoom = zoom;
        this.targetZoom = zoom;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /** The zoom to draw this frame, part-way along if one is running. */
    public double zoom() {
        return zoom;
    }

    /** The zoom it is heading for — what a view is saved as, and what the buttons step from. */
    public double targetZoom() {
        return targetZoom;
    }

    /** The middle to draw this frame. */
    public double centerX() {
        return centerFor(centerX, anchorX);
    }

    public double centerZ() {
        return centerFor(centerZ, anchorZ);
    }

    /** The middle it is heading for. */
    public double targetCenterX() {
        return centerX;
    }

    public double targetCenterZ() {
        return centerZ;
    }

    /**
     * The middle at the zoom being drawn, worked back from the anchor.
     *
     * <p>At the destination this is the destination; at the zoom it started from it is where it
     * started; and at every zoom between, the anchor is the same number of pixels from the middle,
     * which is what holding it still means.
     */
    private double centerFor(double target, double anchor) {
        if (zoom == targetZoom) {
            return target;
        }
        return anchor + (target - anchor) * (zoom / targetZoom);
    }

    /** Puts the zoom and the middle somewhere outright, with nothing left running. */
    public void jumpTo(double zoom, double centerX, double centerZ) {
        this.zoom = zoom;
        this.targetZoom = zoom;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /** Puts the zoom somewhere outright, keeping the middle. */
    public void jumpToZoom(double zoom) {
        jumpTo(zoom, centerX(), centerZ());
    }

    /** Moves the middle outright, for a drag, which has to follow the hand exactly. */
    public void moveTo(double centerX, double centerZ) {
        settle();
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    /** Ends any running zoom where it stands. */
    public void settle() {
        centerX = centerX();
        centerZ = centerZ();
        targetZoom = zoom;
    }

    /**
     * Sends the zoom to {@code newTargetZoom}, holding the given world point still.
     *
     * <p>The destination middle is worked out from where the map is <em>at this instant</em>, not
     * from where a zoom already running was heading. Measuring from the old destination is what makes
     * a run of quick clicks jerk sideways by the distance between one anchor and the next, because
     * the anchor changes at the same moment the middle it is measured against does.
     */
    public void zoomAbout(double newTargetZoom, double anchorX, double anchorZ) {
        double drawnX = centerX();
        double drawnZ = centerZ();
        double drawnZoom = zoom;

        this.anchorX = anchorX;
        this.anchorZ = anchorZ;
        this.targetZoom = newTargetZoom;

        double factor = newTargetZoom / drawnZoom;
        this.centerX = anchorX + (drawnX - anchorX) * factor;
        this.centerZ = anchorZ + (drawnZ - anchorZ) * factor;
    }

    /**
     * Moves a frame's worth along.
     *
     * @param seconds how long the last frame took
     * @param tau     the time constant: about 95% of the way in three of these
     * @return whether anything moved, so a caller can tell a still map from a travelling one
     */
    public boolean advance(double seconds, double tau) {
        seconds = Math.min(MAX_FRAME_SECONDS, Math.max(0.0, seconds));
        double drawn = Math.log(zoom);
        double target = Math.log(targetZoom);
        if (Math.abs(target - drawn) < SETTLED || seconds <= 0 || tau <= 0) {
            boolean moved = zoom != targetZoom;
            zoom = targetZoom;
            return moved;
        }
        zoom = Math.exp(drawn + (target - drawn) * (1 - Math.exp(-seconds / tau)));
        return true;
    }
}
