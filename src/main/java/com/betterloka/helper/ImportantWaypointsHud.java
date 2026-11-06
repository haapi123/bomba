package com.betterloka.helper;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.grind.ShrineTracker;
import com.betterloka.map.WaypointWorldRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Loka Helper's important waypoints: a picture over each of the places that matter on the continent
 * the player is standing on — and only that one, and only when it is switched on.
 *
 * <p>Every marker shows its picture and how far it is; the name shows on the one nearest the
 * crosshair, and on any within a stone's throw, so a spawn full of portals does not become a wall of
 * words. Drawn like the map's own waypoints, so one behind the player is pinned to the side it lies.
 */
public final class ImportantWaypointsHud {
    private static final int ICON_SIZE = 14;
    /** How close to the crosshair a marker has to be for its name to show. */
    private static final int FOCUS_RADIUS = 48;
    /** Under this many blocks away a place's name always shows: it is right there. */
    private static final double NEAR_BLOCKS = 24;
    /** How far above the place the marker floats. */
    private static final double LIFT = 2.5;

    private final BetterLokaConfig config;

    public ImportantWaypointsHud(BetterLokaConfig config) {
        this.config = config;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "important_places"), this::render);
    }

    /** The continent the player is on right now, or {@code null} when it cannot be told. */
    public static ImportantPlaces.Region currentRegion(Minecraft client) {
        if (client == null || client.level == null) {
            return null;
        }
        String world = client.level.dimension().identifier().getPath();
        LevelData.RespawnData spawn = client.level.getRespawnData();
        boolean hasSpawn = spawn != null && spawn != LevelData.RespawnData.DEFAULT;
        int spawnX = hasSpawn ? spawn.pos().getX() : 0;
        int spawnZ = hasSpawn ? spawn.pos().getZ() : 0;
        return ImportantPlaces.detect(world, ShrineTracker.inNether(client), hasSpawn, spawnX, spawnZ);
    }

    private record Marker(ImportantPlaces.Place place, int x, int y, double distance) {
        Marker below(int dy) {
            return new Marker(place, x, y + dy, distance);
        }
    }

    /** How much room one marker takes, picture and distance together, when keeping them apart. */
    private static final int MARKER_WIDTH = 30;
    private static final int MARKER_HEIGHT = ICON_SIZE + 12;

    private void render(GuiGraphicsExtractor context, net.minecraft.client.DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.gui.hud.isHidden() || client.gui.screen() != null
                || client.debugEntries.isOverlayVisible() || !config.anyImportantRegion()) {
            return;
        }
        ImportantPlaces.Region region = currentRegion(client);
        if (region == null || !config.importantRegionEnabled(region)) {
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        double fov = Math.toRadians(client.options.fov().get());
        int width = context.guiWidth();
        int height = context.guiHeight();

        List<Marker> markers = new ArrayList<>();
        for (ImportantPlaces.Place place : ImportantPlaces.on(region)) {
            int[] at = WaypointWorldRenderer.project(camera, eye, place.x() + 0.5, place.y() + LIFT, place.z() + 0.5,
                    width, height, fov);
            markers.add(new Marker(place, at[0], at[1], place.distanceTo(eye.x, eye.z)));
        }
        if (markers.isEmpty()) {
            return;
        }
        // Nearest first keeps its place; any that would land on one already placed — several behind
        // the player pin to the same edge — steps down out of its way.
        markers.sort(Comparator.comparingDouble(Marker::distance));
        List<Marker> placed = new ArrayList<>();
        for (Marker marker : markers) {
            Marker at = marker;
            for (int tries = 0; tries < 8 && overlaps(placed, at); tries++) {
                at = at.below(MARKER_HEIGHT);
            }
            placed.add(at);
        }
        markers = placed;
        // The one nearest the crosshair is named; so is anything right beside the player.
        Marker focused = null;
        double focusDistance = FOCUS_RADIUS;
        for (Marker marker : markers) {
            double fromCentre = Math.hypot(marker.x() - width / 2.0, marker.y() - height / 2.0);
            if (fromCentre < focusDistance) {
                focusDistance = fromCentre;
                focused = marker;
            }
        }
        // Furthest first, so the nearest is drawn on top.
        markers.sort(Comparator.comparingDouble(Marker::distance).reversed());
        for (Marker marker : markers) {
            boolean named = marker == focused || marker.distance() < NEAR_BLOCKS;
            draw(context, client, marker, named);
        }
    }

    private static boolean overlaps(List<Marker> placed, Marker marker) {
        for (Marker other : placed) {
            if (Math.abs(other.x() - marker.x()) < MARKER_WIDTH && Math.abs(other.y() - marker.y()) < MARKER_HEIGHT) {
                return true;
            }
        }
        return false;
    }

    private static void draw(GuiGraphicsExtractor context, Minecraft client, Marker marker, boolean named) {
        int x = marker.x();
        int y = marker.y();
        int half = ICON_SIZE / 2;
        // A dark disc behind the picture, so it reads against any sky.
        context.fill(x - half - 1, y - half, x + half + 1, y + half, 0x70000000);
        context.fill(x - half, y - half - 1, x + half, y + half + 1, 0x70000000);
        PlaceIcons.draw(context, marker.place().icon(), x - half, y - half, ICON_SIZE);

        String range = WaypointWorldRenderer.range(marker.distance());
        context.text(client.font, range, x - client.font.width(range) / 2, y + half + 2, 0xFFD8D8D8);
        if (named) {
            Component name = Component.translatable(marker.place().nameKey());
            int nameWidth = client.font.width(name);
            context.fill(x - nameWidth / 2 - 2, y - half - 12, x + nameWidth / 2 + 2, y - half - 2, 0x90000000);
            context.text(client.font, name, x - nameWidth / 2, y - half - 11, 0xFFFFD46B);
        }
    }
}
