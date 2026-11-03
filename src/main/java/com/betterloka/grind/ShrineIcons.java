package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * A small face for each kind of shrine's mob, so a marker says at a glance what is waiting there.
 *
 * <p>Cut at runtime from the game's own mob textures — whatever resource pack is loaded — rather
 * than shipped with the mod: the faces are the game's art, and taking them from the game keeps them
 * matching what the player actually fights.
 *
 * <p>Where each face sits is given as fractions of the texture, so a pack at another resolution
 * still lines up. The Magma Cube has no single front face in its texture: its body is eight slices
 * one block high, and the face is put together from the front row of each.
 */
public final class ShrineIcons {
    /** One face as uploaded: its texture and its size in texels. */
    private record Icon(Identifier id, int width, int height) {
    }

    private final Map<ShrineKind, Icon> made = new EnumMap<>(ShrineKind.class);
    private final Map<ShrineKind, Boolean> failed = new EnumMap<>(ShrineKind.class);

    /**
     * Draws a kind's face as a square, with a thin dark frame so the pale Ghast still reads on
     * pale ground. Draws nothing if the texture could not be read.
     */
    public void draw(GuiGraphicsExtractor context, ShrineKind kind, int x, int y, int size) {
        Icon icon = get(kind);
        context.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xC0000000);
        if (icon == null) {
            context.fill(x, y, x + size, y + size, 0xFF000000 | kind.color());
            return;
        }
        context.blit(RenderPipelines.GUI_TEXTURED, icon.id(), x, y, 0f, 0f, size, size,
                icon.width(), icon.height(), icon.width(), icon.height(), 0xFFFFFFFF);
    }

    /** The face for a kind, or {@code null} if the texture could not be read. */
    private Icon get(ShrineKind kind) {
        Icon icon = made.get(kind);
        if (icon != null || failed.containsKey(kind)) {
            return icon;
        }
        try {
            int[][] face = switch (kind) {
                // The front of the ghast's one big cube.
                case GHAST -> crop(read("textures/entity/ghast/ghast.png"), 0.25, 0.5, 0.25, 0.5);
                // The front of the head.
                case BLAZE -> crop(read("textures/entity/blaze/blaze.png"), 0.125, 0.25, 0.125, 0.25);
                case MAGMA_CUBE -> magmaFace(read("textures/entity/slime/magmacube.png"));
            };
            icon = new Icon(upload(kind, face), face[0].length, face.length);
            made.put(kind, icon);
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.warn("[betterloka] could not make the {} shrine icon", kind.id(), e);
            failed.put(kind, true);
        }
        return icon;
    }

    private static BufferedImage read(String path) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager()
                .getResource(Identifier.withDefaultNamespace(path))
                .orElseThrow(() -> new IOException("missing " + path));
        try (InputStream in = resource.open()) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("unreadable " + path);
            }
            return image;
        }
    }

    /** A rectangle of the texture, given as fractions of its size. */
    private static int[][] crop(BufferedImage image, double u, double v, double w, double h) {
        int x0 = (int) Math.round(u * image.getWidth());
        int y0 = (int) Math.round(v * image.getHeight());
        int width = Math.max(1, (int) Math.round(w * image.getWidth()));
        int height = Math.max(1, (int) Math.round(h * image.getHeight()));
        int[][] pixels = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y][x] = image.getRGB(x0 + x, y0 + y);
            }
        }
        return pixels;
    }

    /**
     * The magma cube's face, one row per slice, top slice first.
     *
     * <p>In the 64-square texture the slices sit nine texels apart, the first four down the left and
     * the last four down a second column at 32; each slice's front row is eight in and eight down
     * from its corner.
     */
    private static int[][] magmaFace(BufferedImage image) {
        double scale = image.getWidth() / 64.0;
        int side = Math.max(1, (int) Math.round(8 * scale));
        int[][] pixels = new int[8][side];
        for (int slice = 0; slice < 8; slice++) {
            int u = (int) Math.round(((slice < 4 ? 0 : 32) + 8) * scale);
            int v = (int) Math.round((9 * (slice % 4) + 8) * scale);
            for (int x = 0; x < side; x++) {
                pixels[slice][x] = image.getRGB(u + x, v);
            }
        }
        return pixels;
    }

    private static Identifier upload(ShrineKind kind, int[][] face) {
        int height = face.length;
        int width = face[0].length;
        // Deliberately not closed: the texture takes ownership of the image.
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixel(x, y, face[y][x]);
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "shrine_icon_" + kind.id());
        Minecraft.getInstance().getTextureManager()
                .register(id, new DynamicTexture(() -> "betterloka/shrine_icon_" + kind.id(), image));
        return id;
    }
}
