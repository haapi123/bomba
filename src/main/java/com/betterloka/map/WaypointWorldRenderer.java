package com.betterloka.map;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Locale;

/**
 * Draws each waypoint where it actually is, out in the world.
 *
 * <p>Through walls and through terrain, deliberately: a marker you can only see with nothing in the
 * way is no use for finding a territory two kilometres off, which is the whole reason for setting
 * one. It scales with distance so it stays the same size on screen however far away it is.
 */
public final class WaypointWorldRenderer {
    /** Height above the beacon, so the label clears the ground rather than sinking into it. */
    private static final double LIFT = 2.5;

    /** Screen size of the label, in the units the world text renderer counts in. */
    private static final float BASE_SCALE = 0.025f;

    /** Past this the label stops growing, or a far marker would fill the screen. */
    private static final double MAX_SCALE_DISTANCE = 400;

    private final MapService map;

    public WaypointWorldRenderer(MapService map) {
        this.map = map;
    }

    public void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(this::render);
    }

    private void render(net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options.hudHidden) {
            return;
        }
        var waypoints = map.waypoints();
        if (waypoints.isEmpty()) {
            return;
        }

        Camera camera = client.gameRenderer.getCamera();
        Vec3d eye = camera.getCameraPos();
        String here = client.world == null
                ? null
                : client.world.getRegistryKey().getValue().getPath();

        MatrixStack matrices = context.matrices();
        for (Waypoint waypoint : waypoints) {
            if (!onThisContinent(here, waypoint)) {
                continue;
            }
            draw(client, context, matrices, camera, eye, waypoint);
        }
    }

    /**
     * Whether a waypoint belongs to the world being stood in.
     *
     * <p>When the world's name is not one Loka uses, everything is drawn: an unexpected server name
     * should leave a marker visible in the wrong place rather than hide every marker there is.
     */
    private static boolean onThisContinent(String here, Waypoint waypoint) {
        if (here == null) {
            return true;
        }
        boolean known = false;
        for (Continent continent : Continent.values()) {
            if (here.equalsIgnoreCase(continent.world())) {
                known = true;
                break;
            }
        }
        return !known || waypoint.world().equalsIgnoreCase(here);
    }

    private void draw(MinecraftClient client,
                      net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext context,
                      MatrixStack matrices, Camera camera, Vec3d eye, Waypoint waypoint) {
        double distance = Math.sqrt(Math.pow(waypoint.x() - eye.x, 2)
                + Math.pow(waypoint.z() - eye.z, 2));

        matrices.push();
        // Vertices go in camera-relative coordinates, which is what the world renderer expects.
        matrices.translate(waypoint.x() - eye.x, waypoint.y() + LIFT - eye.y, waypoint.z() - eye.z);
        matrices.multiply(camera.getRotation());

        // Grows with distance so the label holds its apparent size, then stops.
        float scale = (float) (BASE_SCALE * Math.max(1, Math.min(distance, MAX_SCALE_DISTANCE) / 12));
        // Negative Y: world space counts upwards and text counts downwards.
        matrices.scale(-scale, -scale, scale);

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        TextRenderer text = client.textRenderer;

        Text label = Text.literal(waypoint.label());
        Text below = Text.literal(range(distance));

        int color = 0xFF000000 | waypoint.color();
        // A quarter-opaque black plate behind it, the way a nameplate is drawn, so a pale marker is
        // still readable against snow or sky.
        int backdrop = (int) (client.options.getTextBackgroundOpacity(0.25f) * 255) << 24;

        text.draw(label, -text.getWidth(label) / 2f, -10, color, false, matrix,
                context.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, backdrop, 0xF000F0);
        text.draw(below, -text.getWidth(below) / 2f, 1, 0xFFC8C8C8, false, matrix,
                context.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, backdrop, 0xF000F0);

        matrices.pop();
    }

    /** Metres up close, kilometres once that stops being a useful number. */
    static String range(double blocks) {
        if (blocks >= 1000) {
            return String.format(Locale.ROOT, "%.1fkm", blocks / 1000);
        }
        return String.format(Locale.ROOT, "%.0fm", blocks);
    }
}
