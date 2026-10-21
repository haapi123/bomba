package com.betterloka.map;

import com.betterloka.BetterLoka;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * Marks each waypoint on screen, over the place it actually is.
 *
 * <p>Projected here and drawn as part of the HUD, rather than submitted into the world. The world
 * route was tried first and instrumented: the render event fired every frame, found the waypoint and
 * ran the draw with correct coordinates — and nothing ever appeared, because a see-through label
 * submitted from a mod's event goes into a buffer the world renderer never empties. Flushing it
 * explicitly did not help either.
 *
 * <p>Projecting instead makes "always visible" true by construction rather than by fighting the
 * depth buffer, and it does something world-space cannot: a marker behind you pins to the edge of
 * the screen on the side it lies, instead of silently not existing.
 */
public final class WaypointWorldRenderer {
    /** Height above the beacon, so the marker sits over the place rather than in the dirt. */
    private static final double LIFT = 2.0;

    /** Keeps a pinned marker off the very edge, where it would be half cut off. */
    private static final int EDGE_MARGIN = 16;

    private static final int ICON_SIZE = 10;

    private final MapService map;
    private final MapIcons icons;

    public WaypointWorldRenderer(MapService map, MapIcons icons) {
        this.map = map;
        this.icons = icons;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "waypoint_markers"), this::render);
    }

    private void render(GuiGraphicsExtractor context, net.minecraft.client.DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui.hud.isHidden()
                || client.gui.screen() != null) {
            return;
        }
        var waypoints = map.waypoints();
        if (waypoints.isEmpty()) {
            return;
        }

        Camera camera = client.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        String here = client.level == null
                ? null
                : client.level.dimension().identifier().getPath();

        int screenWidth = context.guiWidth();
        int screenHeight = context.guiHeight();
        double fov = Math.toRadians(client.options.fov().get());

        for (Waypoint waypoint : waypoints) {
            if (!onThisContinent(here, waypoint)) {
                continue;
            }
            draw(context, client, camera, eye, waypoint, screenWidth, screenHeight, fov);
        }
    }

    /**
     * Whether a waypoint belongs to the world being stood in.
     *
     * <p>When the world's name is not one Loka uses, everything is drawn: an unexpected server name
     * should leave a marker visible in the wrong place rather than hide every marker there is.
     */
    static boolean onThisContinent(String here, Waypoint waypoint) {
        if (here == null) {
            return true;
        }
        for (Continent continent : Continent.values()) {
            if (here.equalsIgnoreCase(continent.world())) {
                return waypoint.world().equalsIgnoreCase(here);
            }
        }
        return true;
    }

    private void draw(GuiGraphicsExtractor context, Minecraft client, Camera camera, Vec3 eye,
                      Waypoint waypoint, int screenWidth, int screenHeight, double fov) {
        Vector3f view = toViewSpace(camera, eye, waypoint);

        // Camera space looks down -Z, so anything at or past zero is behind the player.
        boolean behind = view.z >= -0.05f;
        double focal = (screenHeight / 2.0) / Math.tan(fov / 2);

        int x;
        int y;
        if (behind) {
            // Pinned low on the side it lies, which is the honest thing to show for a place that is
            // not in front of you — the alternative is a marker that vanishes when you turn round.
            x = view.x > 0 ? screenWidth - EDGE_MARGIN : EDGE_MARGIN;
            y = screenHeight * 2 / 3;
        } else {
            x = (int) (screenWidth / 2.0 + view.x / -view.z * focal);
            y = (int) (screenHeight / 2.0 - view.y / -view.z * focal);
            x = Math.max(EDGE_MARGIN, Math.min(screenWidth - EDGE_MARGIN, x));
            y = Math.max(EDGE_MARGIN, Math.min(screenHeight - EDGE_MARGIN, y));
        }

        double distance = waypoint.distanceTo(eye.x, eye.z);
        Component label = Component.literal(waypoint.label());
        Component below = Component.literal(range(distance) + " / " + waypoint.chunksTo(eye.x, eye.z) + "ch");

        Identifier icon = waypoint.icon() == null ? null : icons.get(waypoint.icon(), Continent.KALROS);
        if (icon != null) {
            context.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon,
                    x - ICON_SIZE / 2, y - ICON_SIZE / 2, 0, 0,
                    ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);
        } else {
            // A plain diamond until the keep has downloaded, so the place is still marked.
            context.fill(x - 4, y - 4, x + 4, y + 4, 0xFF000000);
            context.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000 | waypoint.color());
        }

        int color = 0xFF000000 | waypoint.color();
        context.text(client.font, label,
                x - client.font.width(label) / 2, y - ICON_SIZE / 2 - 11, color);
        context.text(client.font, below,
                x - client.font.width(below) / 2, y + ICON_SIZE / 2 + 2, 0xFFC8C8C8);
    }

    /** The waypoint's offset from the eye, turned into the camera's own frame. */
    private static Vector3f toViewSpace(Camera camera, Vec3 eye, Waypoint waypoint) {
        Vector3f delta = new Vector3f(
                (float) (waypoint.x() - eye.x),
                (float) (waypoint.y() + LIFT - eye.y),
                (float) (waypoint.z() - eye.z));
        // getRotation turns camera-local into world, so its conjugate does the reverse.
        Quaternionf toCamera = new Quaternionf(camera.rotation()).conjugate();
        return toCamera.transform(delta);
    }

    /** Metres up close, kilometres once that stops being a useful number. */
    static String range(double blocks) {
        if (blocks >= 1000) {
            return String.format(Locale.ROOT, "%.1fkm", blocks / 1000);
        }
        return String.format(Locale.ROOT, "%.0fm", blocks);
    }
}
