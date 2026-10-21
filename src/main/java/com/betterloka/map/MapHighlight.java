package com.betterloka.map;

import com.betterloka.BetterLoka;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The wash a search result is lit up with, as one texture laid over the map.
 *
 * <p>Painted once on the CPU by {@link TerritoryOverlay#highlight} and drawn each frame as a single
 * quad, so lighting up a town of a dozen hexes costs the frame no more than lighting up one. It is
 * painted for a zoom, at about one texel a real pixel there, and painted again only when the claims
 * change or the zoom has moved far enough that its edge would look soft or thin.
 */
public final class MapHighlight {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "map_highlight");

    /** The most texels along either side, so a town spread across a continent stays a small upload. */
    private static final int MAX_TEXELS = 1024;

    /** How opaque the inside is at full strength. The screen fades the whole texture below this. */
    private static final double FILL_ALPHA = 0.42;

    /** How wide the edge is, in interface pixels at the zoom it was painted for. */
    private static final double OUTLINE_GUI_PIXELS = 2;

    /** Repainted once the zoom is more than this many times off what it was painted for. */
    private static final double REPAINT_RATIO = 2.0;

    private boolean uploaded;
    private double left;
    private double top;
    private double blocksPerTexel;
    private int width;
    private int height;
    private double paintedFor;
    private long claims;

    public Identifier id() {
        return ID;
    }

    public boolean ready() {
        return uploaded;
    }

    /** World X of the texture's western edge. */
    public double left() {
        return left;
    }

    /** World Z of the texture's northern edge. */
    public double top() {
        return top;
    }

    public double blocksPerTexel() {
        return blocksPerTexel;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Makes sure what is uploaded shows these territories and suits this zoom, painting again only
     * when it does not.
     *
     * @param blocksPerPixel the zoom it will be looked at, in blocks per interface pixel
     */
    public void ensure(List<MapTerritory> territories, double blocksPerPixel) {
        if (territories.isEmpty()) {
            clear();
            return;
        }
        long fingerprint = TerritoryOverlay.fingerprint(territories);
        boolean zoomFits = paintedFor > 0
                && Math.max(blocksPerPixel / paintedFor, paintedFor / blocksPerPixel) <= REPAINT_RATIO;
        if (uploaded && fingerprint == claims && zoomFits) {
            return;
        }
        paint(territories, blocksPerPixel, fingerprint);
    }

    private void paint(List<MapTerritory> territories, double blocksPerPixel, long fingerprint) {
        TerrainDownload.Bounds bounds = TerrainDownload.Bounds.of(territories);
        double perRealPixel = MapTerrain.perRealPixel(blocksPerPixel);
        double outlineBlocks = OUTLINE_GUI_PIXELS * blocksPerPixel;
        // A margin of the outline's width all round, so the half of it outside the shape is kept.
        double spanX = bounds.maxX() - bounds.minX() + outlineBlocks * 2;
        double spanZ = bounds.maxZ() - bounds.minZ() + outlineBlocks * 2;
        double texel = Math.max(perRealPixel, Math.max(spanX, spanZ) / MAX_TEXELS);
        int texelsX = Math.max(1, (int) Math.ceil(spanX / texel));
        int texelsZ = Math.max(1, (int) Math.ceil(spanZ / texel));
        double originX = bounds.minX() - outlineBlocks;
        double originZ = bounds.minZ() - outlineBlocks;

        int[] argb = TerritoryOverlay.highlight(texelsX, texelsZ, originX, originZ, texel,
                territories, FILL_ALPHA, Math.max(1.0, outlineBlocks / texel));
        try {
            // Deliberately not closed: the texture takes ownership of the image.
            NativeImage image = new NativeImage(texelsX, texelsZ, false);
            for (int y = 0; y < texelsZ; y++) {
                int row = y * texelsX;
                for (int x = 0; x < texelsX; x++) {
                    image.setPixel(x, y, argb[row + x]);
                }
            }
            // Registering under the id in use replaces and closes the old one.
            Minecraft.getInstance().getTextureManager()
                    .register(ID, new MapTerrain.SmoothTexture("betterloka/map_highlight", image));
            uploaded = true;
            left = originX;
            top = originZ;
            blocksPerTexel = texel;
            width = texelsX;
            height = texelsZ;
            paintedFor = blocksPerPixel;
            claims = fingerprint;
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not upload the map highlight", e);
            clear();
        }
    }

    /** Drops the texture, for a cleared search or a closed map. */
    public void clear() {
        if (uploaded) {
            Minecraft.getInstance().getTextureManager().release(ID);
        }
        uploaded = false;
        paintedFor = 0;
        claims = 0;
    }
}
