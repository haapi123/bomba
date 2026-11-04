package com.betterloka.helper;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Locale;

/**
 * Loka Helper's coordinates: where the player stands and which way they face, in a small box
 * wherever they put it — press P to move it.
 */
public final class CoordsHud {
    static final int PADDING = 3;
    static final int LINE = 10;
    private static final int BACKGROUND = 0x90000000;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int AXES = 0xFFAAAAAA;
    private static final int FACING = 0xFFFFD46B;

    private final BetterLokaConfig config;

    public CoordsHud(BetterLokaConfig config) {
        this.config = config;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BetterLoka.MOD_ID, "coordinates"), this::render);
    }

    private void render(GuiGraphicsExtractor context, net.minecraft.client.DeltaTracker tick) {
        Minecraft client = Minecraft.getInstance();
        if (!config.coordsHudEnabled() || client.player == null || client.gui.hud.isHidden()
                || client.debugEntries.isOverlayVisible()) {
            return;
        }
        // Drawn by the position screen itself while it is open, where it can be dragged.
        if (client.gui.screen() instanceof com.betterloka.gui.CoordsHudPositionScreen) {
            return;
        }
        List<Line> lines = lines(client.player);
        int[] box = place(context.guiWidth(), context.guiHeight(), client.font, lines);
        draw(context, client.font, lines, box[0], box[1]);
    }

    /** One line of the box: a coloured part and a grey part after it. */
    public record Line(String text, int color, String after) {
    }

    /** Where the player is and which way they face. */
    public static List<Line> lines(Player player) {
        Facing facing = Facing.of(player.getYRot());
        String position = String.format(Locale.ROOT, "XYZ: %d / %d / %d",
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ()));
        return List.of(
                new Line(position, TEXT, ""),
                new Line(Component.translatable(facing.key()).getString(), FACING, "  " + facing.axes()));
    }

    /** A made-up position, for placing the box before there is a world to stand in. */
    public static List<Line> sample() {
        return List.of(new Line("XYZ: 1234 / 64 / -567", TEXT, ""),
                new Line(Component.translatable(Facing.NORTH_EAST.key()).getString(), FACING,
                        "  " + Facing.NORTH_EAST.axes()));
    }

    public static int width(Font font, List<Line> lines) {
        int widest = 0;
        for (Line line : lines) {
            widest = Math.max(widest, font.width(line.text()) + font.width(line.after()));
        }
        return widest + PADDING * 2;
    }

    public static int height(List<Line> lines) {
        return lines.size() * LINE + PADDING * 2 - 1;
    }

    /** Where the box goes on a screen this size, from the fractions the player chose. */
    public int[] place(int screenWidth, int screenHeight, Font font, List<Line> lines) {
        return place(config.coordsHudX(), config.coordsHudY(), screenWidth, screenHeight,
                width(font, lines), height(lines));
    }

    /**
     * Where a box of this size goes: each fraction is how far across the room left for it, so 0 is
     * against the left or top edge and 1 against the right or bottom.
     */
    public static int[] place(double fx, double fy, int screenWidth, int screenHeight, int boxWidth, int boxHeight) {
        int roomX = Math.max(0, screenWidth - boxWidth - 4);
        int roomY = Math.max(0, screenHeight - boxHeight - 4);
        return new int[]{2 + (int) Math.round(fx * roomX), 2 + (int) Math.round(fy * roomY)};
    }

    /** The fractions that put a box at {@code (x, y)}: {@link #place}, the other way round. */
    public static double[] fractions(int x, int y, int screenWidth, int screenHeight, int boxWidth, int boxHeight) {
        int roomX = Math.max(1, screenWidth - boxWidth - 4);
        int roomY = Math.max(1, screenHeight - boxHeight - 4);
        return new double[]{Math.max(0, Math.min(1, (x - 2) / (double) roomX)),
                Math.max(0, Math.min(1, (y - 2) / (double) roomY))};
    }

    public static void draw(GuiGraphicsExtractor context, Font font, List<Line> lines, int x, int y) {
        context.fill(x, y, x + width(font, lines), y + height(lines), BACKGROUND);
        int lineY = y + PADDING;
        for (Line line : lines) {
            context.text(font, line.text(), x + PADDING, lineY, line.color());
            if (!line.after().isEmpty()) {
                context.text(font, line.after(), x + PADDING + font.width(line.text()), lineY, AXES);
            }
            lineY += LINE;
        }
    }
}
