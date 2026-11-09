package com.betterloka.helper;

import com.betterloka.BetterLoka;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Map;

/**
 * The pictures on the important places' markers.
 *
 * <p>Where the game already has the right picture it is used — a boat for the docks, a diamond
 * pickaxe for a resource island, the witch's, the warden's and the iron golem's eggs for their bosses, an eye of ender
 * for the End portal, a diamond sword for Zyre, a compass for the continent portals, crossed swords
 * for the big fights and one for the small. The four it has nothing for — a house, a market stall,
 * a roulette wheel and a Nether portal — are drawn here, sixteen pixels square in the game's own
 * style, and made into textures once.
 */
public final class PlaceIcons {
    private static final Map<ImportantPlaces.Icon, ItemStack> ITEMS = new EnumMap<>(ImportantPlaces.Icon.class);
    private static final Map<ImportantPlaces.Icon, Identifier> DRAWN = new EnumMap<>(ImportantPlaces.Icon.class);

    private PlaceIcons() {
    }

    private static ItemStack item(ImportantPlaces.Icon icon) {
        return ITEMS.computeIfAbsent(icon, key -> switch (key) {
            case DOCKS -> new ItemStack(Items.OAK_BOAT);
            case RESOURCE_ISLAND -> new ItemStack(Items.DIAMOND_PICKAXE);
            case WITCH -> new ItemStack(Items.WITCH_SPAWN_EGG);
            case WARDEN -> new ItemStack(Items.WARDEN_SPAWN_EGG);
            case GOLEM -> new ItemStack(Items.IRON_GOLEM_SPAWN_EGG);
            case END_PORTAL -> new ItemStack(Items.ENDER_EYE);
            case ZYRE -> new ItemStack(Items.DIAMOND_SWORD);
            case BIG_FIGHTS, SMALL_FIGHTS -> new ItemStack(Items.IRON_SWORD);
            case COMPASS -> new ItemStack(Items.COMPASS);
            default -> ItemStack.EMPTY;
        });
    }

    /** Draws a place's picture {@code size} pixels square with its top-left corner at {@code (x, y)}. */
    public static void draw(GuiGraphicsExtractor context, ImportantPlaces.Icon icon, int x, int y, int size) {
        Identifier drawn = drawn(icon);
        if (drawn != null) {
            context.blit(RenderPipelines.GUI_TEXTURED, drawn, x, y, 0f, 0f, size, size, 16, 16, 16, 16, 0xFFFFFFFF);
            return;
        }
        float scale = size / 16f;
        var pose = context.pose();
        if (icon == ImportantPlaces.Icon.BIG_FIGHTS) {
            // Two swords crossed: the second is the first turned a quarter round its middle. (A
            // mirror image is what crossed swords really are, but a flipped item draws nothing.)
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(scale, scale);
            context.item(item(icon), 0, 0);
            pose.popMatrix();
            pose.pushMatrix();
            pose.translate(x + size / 2f, y + size / 2f);
            pose.rotate((float) (Math.PI / 2));
            pose.translate(-size / 2f, -size / 2f);
            pose.scale(scale, scale);
            context.item(item(icon), 0, 0);
            pose.popMatrix();
            return;
        }
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        context.item(item(icon), 0, 0);
        pose.popMatrix();
    }

    // --- the four drawn here ---

    /** The drawn picture for an icon, made the first time it is asked for; {@code null} for an item one. */
    private static Identifier drawn(ImportantPlaces.Icon icon) {
        if (DRAWN.containsKey(icon)) {
            return DRAWN.get(icon);
        }
        int[][] pixels = switch (icon) {
            case HOUSE -> fromArt(HOUSE, Map.of('R', 0xFFB8392B, 'r', 0xFF8E2A20, 'W', 0xFFD2A86E, 'w', 0xFFAF8550,
                    'B', 0xFF8FD3F5, 'D', 0xFF6B4423));
            case MARKET -> fromArt(STALL, Map.of('R', 0xFFD8402F, 'W', 0xFFF4F1E8, 'P', 0xFF8A6A44, 'C', 0xFFB98A50,
                    'c', 0xFF8E6538, 'G', 0xFF4FD15A, 'Y', 0xFFF2C94C, 'O', 0xFFE07A2F));
            case ROULETTE -> roulette();
            case NETHER_PORTAL -> netherPortal();
            default -> null;
        };
        Identifier id = pixels == null ? null : upload(icon, outlined(pixels));
        DRAWN.put(icon, id);
        return id;
    }

    private static final String[] HOUSE = {
            "................",
            ".......rr.......",
            "......rRRr......",
            ".....rRRRRr.....",
            "....rRRRRRRr....",
            "...rRRRRRRRRr...",
            "..rRRRRRRRRRRr..",
            ".rRRRRRRRRRRRRr.",
            "..WWWWWWWWWWWW..",
            "..WBBWWWWWDDDW..",
            "..WBBWWWWWDDDW..",
            "..WWWWWWWWDDDW..",
            "..WwwwwwwwDDDW..",
            "..WWWWWWWWDDDW..",
            "..wwwwwwwwwwww..",
            "................"};

    private static final String[] STALL = {
            "................",
            ".RRWWRRWWRRWWRR.",
            ".RRWWRRWWRRWWRR.",
            ".RRWWRRWWRRWWRR.",
            "..R.W.R.W.R.W.R.",
            "..P..........P..",
            "..P..........P..",
            "..P.GY..O.GO.P..",
            "..PCCCCCCCCCCP..",
            "..PccccccccccP..",
            "..PCCCCCCCCCCP..",
            "..PCCCCCCCCCCP..",
            "..PccccccccccP..",
            "..PCCCCCCCCCCP..",
            "..P..........P..",
            "................"};

    private static int[][] fromArt(String[] art, Map<Character, Integer> palette) {
        int[][] pixels = new int[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                char c = x < art[y].length() ? art[y].charAt(x) : '.';
                pixels[y][x] = palette.getOrDefault(c, 0);
            }
        }
        return pixels;
    }

    /** A wheel in red and black, a gold rim, a green middle. */
    private static int[][] roulette() {
        int[][] pixels = new int[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double dx = x - 7.5;
                double dy = y - 7.5;
                double r = Math.hypot(dx, dy);
                if (r > 7.6) {
                    continue;
                }
                if (r > 6.3) {
                    pixels[y][x] = 0xFFD9A93A;
                } else if (r < 2.0) {
                    pixels[y][x] = r < 1.0 ? 0xFFF2D27A : 0xFF2E9E4F;
                } else {
                    double angle = Math.atan2(dy, dx) + Math.PI;
                    int sector = (int) (angle / (2 * Math.PI / 12));
                    pixels[y][x] = sector % 2 == 0 ? 0xFFC8322A : 0xFF1C1C22;
                }
            }
        }
        return pixels;
    }

    /** An obsidian frame with the portal's purple swirling inside. */
    private static int[][] netherPortal() {
        int[][] pixels = new int[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 2; x < 14; x++) {
                boolean frame = x < 4 || x > 11 || y < 2 || y > 13;
                if (frame) {
                    pixels[y][x] = (x + y) % 3 == 0 ? 0xFF3B2A5A : 0xFF17101F;
                } else {
                    double swirl = Math.sin(x * 1.3 + y * 0.7) + Math.cos(y * 1.1 - x * 0.5);
                    pixels[y][x] = swirl > 0.6 ? 0xFFD08CFF : swirl > -0.4 ? 0xFF9A3DEB : 0xFF6A1FB8;
                }
            }
        }
        return pixels;
    }

    /** A dark outline round the shape, so it reads on sky, sand and snow alike. */
    private static int[][] outlined(int[][] pixels) {
        int[][] out = new int[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (pixels[y][x] != 0) {
                    out[y][x] = pixels[y][x];
                    continue;
                }
                boolean edge = false;
                for (int dy = -1; dy <= 1 && !edge; dy++) {
                    for (int dx = -1; dx <= 1 && !edge; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        edge = nx >= 0 && ny >= 0 && nx < 16 && ny < 16 && pixels[ny][nx] != 0
                                && Math.abs(dx) + Math.abs(dy) == 1;
                    }
                }
                out[y][x] = edge ? 0xB0101014 : 0;
            }
        }
        return out;
    }

    private static Identifier upload(ImportantPlaces.Icon icon, int[][] pixels) {
        try {
            // Deliberately not closed: the texture takes ownership of the image.
            NativeImage image = new NativeImage(16, 16, false);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    image.setPixel(x, y, pixels[y][x]);
                }
            }
            String name = "place_icon_" + icon.name().toLowerCase(java.util.Locale.ROOT);
            Identifier id = Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, name);
            Minecraft.getInstance().getTextureManager()
                    .register(id, new DynamicTexture(() -> "betterloka/" + name, image));
            return id;
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("[betterloka] could not make the {} place icon", icon, e);
            return null;
        }
    }
}
