package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.map.WaypointWorldRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A marker over every Nether shrine, with its mob's face, its name and how long until it is back.
 *
 * <p>Drawn only in the Nether — the coordinates mean nothing anywhere else — and only for the kinds
 * switched on in the Shrines Grinder tab. Projected and drawn as part of the HUD like the map's own
 * waypoints, so a shrine behind the player is pinned to the side it lies on rather than lost.
 */
public final class ShrineWaypoints {
    /** How far above the shrine the marker floats, so it marks the place rather than sits in it. */
    private static final double LIFT = 3.0;

    private static final int ICON_SIZE = 12;

    private final ShrineTracker tracker;
    private final ShrineIcons icons;
    private final BetterLokaConfig config;

    public ShrineWaypoints(ShrineTracker tracker, ShrineIcons icons, BetterLokaConfig config) {
        this.tracker = tracker;
        this.icons = icons;
        this.config = config;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "shrine_markers"), this::render);
    }

    /** One marker, projected for this frame, with where its text line ended up. */
    private static final class Marker {
        final Shrine shrine;
        final int x;
        final int y;
        final double distance;
        final Component line;
        final int colour;
        final int width;
        int lineX;
        int lineY;
        /** Whether the line had to move away from its own icon, and so carries a small face itself. */
        boolean moved;

        Marker(Shrine shrine, int x, int y, double distance, Component line, int colour, int width) {
            this.shrine = shrine;
            this.x = x;
            this.y = y;
            this.distance = distance;
            this.line = line;
            this.colour = colour;
            this.width = width;
        }
    }

    /** How close to the crosshair a marker has to be for its full name to show. */
    private static final int FOCUS_RADIUS = 40;

    private void render(GuiGraphicsExtractor context, net.minecraft.client.DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui.hud.isHidden()
                || client.gui.screen() != null || !config.shrineWaypointsEnabled()
                || !ShrineTracker.inNether(client)) {
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        double fov = Math.toRadians(client.options.fov().get());
        long now = System.currentTimeMillis();
        int width = context.guiWidth();
        int height = context.guiHeight();

        List<Marker> markers = new ArrayList<>();
        for (Shrine shrine : Shrines.ALL) {
            if (config.shrineShown(shrine.kind())) {
                markers.add(marker(client, camera, eye, fov, width, height, shrine, now));
            }
        }
        // Nearest first gets its line where it belongs; each further one moves down out of the way
        // of the lines already placed and of every icon.
        markers.sort(Comparator.comparingDouble(m -> m.distance));
        List<int[]> taken = new ArrayList<>();
        for (Marker marker : markers) {
            int half = ICON_SIZE / 2 + 1;
            taken.add(new int[] {marker.x - half, marker.y - half, marker.x + half, marker.y + half});
        }
        for (Marker marker : markers) {
            int natural = marker.y + ICON_SIZE / 2 + 3;
            marker.lineY = natural;
            marker.lineX = clampLine(marker, marker.x - marker.width / 2, width);
            for (int tries = 0; tries < 8 && overlaps(taken, marker); tries++) {
                marker.lineY += 10;
            }
            marker.moved = marker.lineY != natural;
            if (marker.moved) {
                // Room for the small face in front of the text.
                marker.lineX = clampLine(marker, marker.x - marker.width / 2 - MINI_ICON / 2 - 1, width);
            }
            taken.add(box(marker));
        }
        // Drawn furthest first, so the nearest, which matters most, is never painted over.
        for (int i = markers.size() - 1; i >= 0; i--) {
            draw(context, client, markers.get(i), width, height);
        }
    }

    private Marker marker(Minecraft client, Camera camera, Vec3 eye, double fov, int width, int height,
                          Shrine shrine, long now) {
        // Without a known height the marker rides at eye level, which still points the right way.
        double y = shrine.hasY() ? shrine.y() + LIFT : eye.y;
        int[] at = WaypointWorldRenderer.project(camera, eye, shrine.x() + 0.5, y, shrine.z() + 0.5,
                width, height, fov);
        double distance = shrine.distanceTo(eye.x, eye.z);

        // The number, then how far — or that the player is standing on it — then whether it is back.
        boolean here = shrine.equals(tracker.inside());
        MutableComponent line = Component.literal("#" + shrine.number() + " · ")
                .append(here ? Component.translatable("betterloka.shrine.marker_here")
                        : Component.literal(WaypointWorldRenderer.range(distance)));
        ShrineTimers.State state = tracker.timers().state(shrine, now);
        if (state != ShrineTimers.State.UNKNOWN) {
            line.append(" · ").append(tracker.status(shrine, now));
        }
        int colour = switch (state) {
            case READY -> 0xFF7BD88F;
            case COOLDOWN -> 0xFFFFB454;
            case UNKNOWN -> 0xFFC8C8C8;
        };
        return new Marker(shrine, at[0], at[1], distance, line, colour, client.font.width(line));
    }

    private static final int MINI_ICON = 8;

    /** Keeps a line wholly on screen, which a marker pinned to the edge would otherwise push off. */
    private static int clampLine(Marker marker, int left, int screenWidth) {
        int full = marker.width + MINI_ICON + 2;
        return Math.max(2, Math.min(screenWidth - full - 2, left));
    }

    private static int[] box(Marker marker) {
        int extra = marker.moved ? MINI_ICON + 2 : 0;
        return new int[] {marker.lineX - 1, marker.lineY - 1,
                marker.lineX + extra + marker.width + 1, marker.lineY + 9};
    }

    private static boolean overlaps(List<int[]> taken, Marker marker) {
        // Tested with room for a small face, so a line that moves never lands on another once it has one.
        boolean was = marker.moved;
        marker.moved = true;
        int[] mine = box(marker);
        marker.moved = was;
        for (int[] other : taken) {
            if (mine[0] < other[2] && other[0] < mine[2] && mine[1] < other[3] && other[1] < mine[3]) {
                return true;
            }
        }
        return false;
    }

    private void draw(GuiGraphicsExtractor context, Minecraft client, Marker marker, int width, int height) {
        Shrine shrine = marker.shrine;
        int top = marker.y - ICON_SIZE / 2;
        icons.draw(context, shrine.kind(), marker.x - ICON_SIZE / 2, top, ICON_SIZE);

        // The full name only for the one being looked at, or stood on: eleven names at once is a
        // wall of text, and the face and the number already say which shrine it is.
        boolean focused = Math.abs(marker.x - width / 2) < FOCUS_RADIUS
                && Math.abs(marker.y - height / 2) < FOCUS_RADIUS;
        if (focused || shrine.equals(tracker.inside())) {
            Component label = Component.literal(shrine.label());
            context.text(client.font, label, marker.x - client.font.width(label) / 2, top - 11,
                    0xFF000000 | shrine.kind().color());
        }
        int textX = marker.lineX;
        if (marker.moved) {
            icons.draw(context, shrine.kind(), textX, marker.lineY, MINI_ICON);
            textX += MINI_ICON + 2;
        }
        context.text(client.font, marker.line, textX, marker.lineY, marker.colour);
    }
}
