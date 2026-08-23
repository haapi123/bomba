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

    /**
     * World size per unit of text, per block of distance.
     *
     * <p>Text has to grow with range to hold its size on screen. A first guess at this was small
     * enough that a marker two hundred blocks out was a smudge on the horizon — this is worked from
     * the geometry instead: with a 70 degree field of view a screen is about 1.4 times as tall as
     * it is distant, so a label a thirtieth of that is {@code 0.0047} per block per text unit.
     */
    private static final double SCALE_PER_BLOCK = 0.0047;

    /** Close up the label would shrink to nothing, so it stops here. */
    private static final double MIN_SCALE_DISTANCE = 8;

    /** Past this it stops growing, or a marker across the map would fill the screen. */
    private static final double MAX_SCALE_DISTANCE = 600;

    /** Turned on by the screenshot driver to work out why a marker is not appearing. */
    private static final boolean DIAGNOSE = "1".equals(System.getenv("BETTERLOKA_SHOTS"));
    private int diagnosed;

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
        boolean drewAny = false;
        if (DIAGNOSE && diagnosed++ < 3) {
            com.betterloka.BetterLoka.LOGGER.info(
                    "WAYPOINT-RENDER fired: {} waypoint(s), world={}, matrices={}, consumers={}",
                    waypoints.size(), here, matrices, context.consumers());
        }
        for (Waypoint waypoint : waypoints) {
            if (!onThisContinent(here, waypoint)) {
                continue;
            }
            draw(client, context, matrices, camera, eye, waypoint);
            drewAny = true;
        }

        // Text goes into a buffer keyed by render layer, and the world renderer only empties the
        // layers it knows it used. A see-through label submitted here would otherwise sit in that
        // buffer until something else happened to flush it — which is why nothing appeared.
        if (drewAny && context.consumers() instanceof net.minecraft.client.render.VertexConsumerProvider.Immediate immediate) {
            immediate.draw();
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
        double ranged = Math.max(MIN_SCALE_DISTANCE, Math.min(distance, MAX_SCALE_DISTANCE));
        float scale = (float) (SCALE_PER_BLOCK * ranged);
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

        if (DIAGNOSE && diagnosed < 6) {
            diagnosed++;
            com.betterloka.BetterLoka.LOGGER.info(
                    "WAYPOINT-DRAW {} at {},{},{} eye {},{},{} distance {} scale {}",
                    waypoint.label(), waypoint.x(), waypoint.y(), waypoint.z(),
                    eye.x, eye.y, eye.z, distance, scale);
        }
    }

    /** Metres up close, kilometres once that stops being a useful number. */
    static String range(double blocks) {
        if (blocks >= 1000) {
            return String.format(Locale.ROOT, "%.1fkm", blocks / 1000);
        }
        return String.format(Locale.ROOT, "%.0fm", blocks);
    }
}
