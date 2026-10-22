package com.betterloka.map;

import com.betterloka.BetterLoka;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * The waypoints set on the map, drawn on the screen with the way to them.
 *
 * <p>A distance alone tells you how far but not where, so each line carries an arrow that turns with
 * you: the point of marking a territory is walking to it.
 *
 * <p>Only the current world's waypoints are shown. A marker set on Kalros means nothing while
 * standing on Garama, and drawing it there would send somebody the wrong way.
 */
public final class WaypointHud {
    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 11;

    /** Below the grind timers, which own the top right. */
    private static final int TOP_OFFSET = 26;

    /**
     * Only listed once there is more than one.
     *
     * <p>A single waypoint is already drawn over the place itself, with its name and range, so a
     * corner line repeating it is clutter. Several at once is the case the list earns its space in:
     * the marker for one behind you is pinned to an edge, and the list is what says how far each of
     * them is without turning round.
     */
    private static final int LIST_FROM = 2;

    private final MapService map;

    public WaypointHud(MapService map) {
        this.map = map;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "waypoints"), this::render);
    }

    private void render(GuiGraphicsExtractor context, net.minecraft.client.DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui.hud.isHidden()
                || client.gui.screen() != null) {
            return;
        }

        List<Waypoint> waypoints = map.waypoints();
        if (waypoints.size() < LIST_FROM) {
            return;
        }

        double playerX = client.player.getX();
        double playerZ = client.player.getZ();
        float yaw = client.player.getYRot();

        int y = TOP_OFFSET;
        for (Waypoint waypoint : waypoints) {
            if (!sameWorld(client, waypoint)) {
                continue;
            }
            Component line = Component.literal(String.format(Locale.ROOT, "%s %s  %.0fm / %dch",
                    arrow(waypoint, playerX, playerZ, yaw), waypoint.label(),
                    waypoint.distanceTo(playerX, playerZ), waypoint.chunksTo(playerX, playerZ)));

            int width = client.font.width(line);
            int x = context.guiWidth() - width - MARGIN;
            context.text(client.font, line, x, y, 0xFF000000 | waypoint.color());
            y += LINE_HEIGHT;
        }
    }

    /**
     * Whether the player is standing on the continent this waypoint belongs to.
     *
     * <p>Loka runs each continent as its own world, and the client knows the world's registry key,
     * so the check is the key's path against Loka's world name. On a server that names them
     * something else the waypoint is shown rather than hidden: a marker in the wrong place is a
     * smaller failure than one that never appears.
     */
    public static boolean sameWorld(Minecraft client, Waypoint waypoint) {
        if (client.level == null) {
            return true;
        }
        String here = client.level.dimension().identifier().getPath();
        for (Continent continent : Continent.values()) {
            if (here.equalsIgnoreCase(continent.world())) {
                return waypoint.world().equalsIgnoreCase(here);
            }
        }
        return true;
    }

    /** Eight-point arrow: which way to turn to be facing it. */
    private static String arrow(Waypoint waypoint, double playerX, double playerZ, float yaw) {
        double bearing = Math.toDegrees(Math.atan2(waypoint.z() - playerZ, waypoint.x() - playerX));
        // Minecraft's yaw is 0 facing south and grows clockwise; atan2 here is measured from +X.
        double relative = ((bearing - 90 - yaw) % 360 + 540) % 360 - 180;
        String[] arrows = {"^", "/", ">", "\\", "v", "\\", "<", "/"};
        int index = (int) Math.round((relative + 180) / 45.0) % 8;
        return arrows[index];
    }
}
