package com.betterloka.gui;

import com.betterloka.market.MarketCategory;
import com.betterloka.market.MarketIndex;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

import java.util.List;

/**
 * The market square: a stall for each category, drawn in three quarters view — a planked counter
 * with its top and side showing, posts, and a striped awning in the stall's colour — with the goods
 * it sells most of standing on the counter.
 *
 * <p>Drawn out of the game's own block textures and plain fills, slanted with the GUI's transform,
 * so nothing is bundled and it scales with the screen. The items are the game's own models, so a
 * block on the counter is a real little cube.
 */
final class MarketSquare {
    private static final Identifier PLANKS = Identifier.withDefaultNamespace("textures/block/oak_planks.png");
    private static final Identifier COUNTER_TOP = Identifier.withDefaultNamespace("textures/block/stripped_oak_log.png");
    private static final Identifier FLOOR = Identifier.withDefaultNamespace("textures/block/stone_bricks.png");
    private static final Identifier SIGN = Identifier.withDefaultNamespace("textures/block/dark_oak_planks.png");

    private static final int ROWS = 3;
    private static final int LABEL_HEIGHT = 22;

    private static final int POST = 0xFF4A3220;
    private static final int POST_LIGHT = 0xFF6B4A2E;
    private static final int STRIPE_LIGHT = 0xFFF2EEE4;

    private int left;
    private int top;
    private int width;
    private int height;

    void place(int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
    }

    private int cellWidth() {
        return width / MarketCategory.PER_ROW;
    }

    private int cellHeight() {
        return height / ROWS;
    }

    /** The stall under the pointer, or {@code null}. */
    MarketCategory stallAt(double mouseX, double mouseY) {
        if (mouseX < left || mouseY < top || mouseX >= left + cellWidth() * MarketCategory.PER_ROW
                || mouseY >= top + cellHeight() * ROWS) {
            return null;
        }
        int column = (int) ((mouseX - left) / cellWidth());
        int row = (int) ((mouseY - top) / cellHeight());
        int index = row * MarketCategory.PER_ROW + column;
        MarketCategory[] all = MarketCategory.values();
        return index >= 0 && index < all.length ? all[index] : null;
    }

    /** The middle of a stall's cell, for the dev harness to click. */
    int[] centreOf(MarketCategory category) {
        int index = category.ordinal();
        int column = index % MarketCategory.PER_ROW;
        int row = index / MarketCategory.PER_ROW;
        return new int[]{left + column * cellWidth() + cellWidth() / 2, top + row * cellHeight() + cellHeight() / 2};
    }

    /**
     * @param loading true while the market is still being fetched, so the counts are not final
     */
    void render(GuiGraphicsExtractor context, Font font, MarketIndex index, boolean loading,
                MarketCategory hovered) {
        drawFloor(context);
        MarketCategory[] all = MarketCategory.values();
        for (int i = 0; i < all.length; i++) {
            int column = i % MarketCategory.PER_ROW;
            int row = i / MarketCategory.PER_ROW;
            int cellX = left + column * cellWidth();
            int cellY = top + row * cellHeight();
            drawStall(context, font, all[i], index.stall(all[i]), loading, all[i] == hovered,
                    cellX, cellY, cellWidth(), cellHeight());
        }
    }

    /** Paving under the whole square, dimmed so the stalls stand out from it. */
    private void drawFloor(GuiGraphicsExtractor context) {
        int tile = Math.max(8, Math.min(16, cellWidth() / 6));
        context.enableScissor(left, top, left + width, top + height);
        for (int y = top; y < top + height; y += tile) {
            for (int x = left; x < left + width; x += tile) {
                context.blit(RenderPipelines.GUI_TEXTURED, FLOOR, x, y, 0, 0, tile, tile, 16, 16, 16, 16,
                        0xFF6E6E74);
            }
        }
        context.disableScissor();
        // Darker towards the back, which is all the depth a paved floor needs.
        context.fillGradient(left, top, left + width, top + height, 0xB0101018, 0x30101018);
        FilterMenu.outline(context, left - 1, top - 1, width + 2, height + 2, 0xFF2C2C38);
    }

    private void drawStall(GuiGraphicsExtractor context, Font font, MarketCategory category,
                           MarketIndex.Stall stall, boolean loading, boolean hovered,
                           int cellX, int cellY, int cellW, int cellH) {
        boolean empty = !loading && stall.offers() == 0;
        int graphicH = cellH - LABEL_HEIGHT;
        float unit = Math.max(4.5f, Math.min(12f, Math.min(cellW / 6.8f, graphicH / 5.6f)));

        int counterW = Math.round(unit * 4.4f);
        int counterH = Math.round(unit * 1.5f);
        int depthX = Math.round(unit * 1.0f);
        int depthY = Math.round(unit * 0.7f);
        int postH = Math.round(unit * 2.0f);
        int overhang = Math.round(unit * 0.4f);
        int awningDX = Math.round(unit * 1.3f);
        int awningDY = Math.round(unit * 1.5f);
        int valance = Math.max(3, Math.round(unit * 0.55f));

        int lift = hovered ? 2 : 0;
        int totalW = counterW + Math.max(depthX, awningDX) + overhang * 2;
        int counterX = cellX + (cellW - totalW) / 2 + overhang;
        int base = cellY + graphicH - 2 - lift;
        int counterTop = base - counterH;
        int awningY = counterTop - postH;

        int color = empty ? 0x6A6A70 : category.color();
        float light = hovered ? 1.25f : 1f;
        int awning = MarketLook.shade(color, light);
        int awningDark = MarketLook.shade(color, hovered ? 1.0f : 0.78f);
        int wood = hovered ? 0xFFFFFFFF : 0xFFE0E0E0;

        // A shadow on the paving.
        context.fill(counterX - 2, base, counterX + counterW + depthX + 2, base + 2, 0x60000000);

        // Back posts, behind everything else.
        int backY = counterTop - depthY;
        context.fill(counterX + depthX, awningY - awningDY + valance, counterX + depthX + 2, backY, POST);
        context.fill(counterX + counterW + depthX - 2, awningY - awningDY + valance,
                counterX + counterW + depthX, backY, POST);

        // The counter: side, top, front.
        texturedQuad(context, PLANKS, counterX + counterW, counterTop, depthX, -depthY, 0, counterH,
                1, Math.max(1, counterH / unit), MarketLook.shade(wood & 0xFFFFFF, 0.55f));
        texturedQuad(context, COUNTER_TOP, counterX + depthX, counterTop - depthY, counterW, 0, -depthX, depthY,
                Math.max(1, counterW / unit), 1, MarketLook.shade(wood & 0xFFFFFF, 1.0f));
        tile(context, PLANKS, counterX, counterTop, counterW, counterH, unit, MarketLook.shade(wood & 0xFFFFFF, 0.8f));
        context.fill(counterX, counterTop, counterX + counterW, counterTop + 1, 0xFF8A6A44);
        context.fill(counterX, base - 1, counterX + counterW, base, 0xFF2A1C10);

        // What is on the counter: the goods the stall has most of.
        List<String> goods = stall.showcase().isEmpty() ? List.of(category.icon()) : stall.showcase();
        float itemSize = Math.max(10f, Math.min(16f, unit * 1.6f));
        int fit = Math.max(1, Math.min(goods.size(), (int) (counterW / (itemSize * 0.85f))));
        for (int i = 0; i < fit; i++) {
            float slot = counterW / (float) fit;
            float itemX = counterX + depthX * 0.5f + slot * i + (slot - itemSize) / 2;
            float itemY = counterTop - depthY * 0.5f - itemSize + 2;
            context.pose().pushMatrix();
            context.pose().translate(itemX, itemY);
            context.pose().scale(itemSize / 16f, itemSize / 16f);
            context.item(MarketLook.icon(goods.get(i)), 0, 0);
            context.pose().popMatrix();
        }

        // Front posts, from the counter up to the awning.
        context.fill(counterX, awningY + valance, counterX + 2, counterTop, POST_LIGHT);
        context.fill(counterX + counterW - 2, awningY + valance, counterX + counterW, counterTop, POST_LIGHT);

        // The awning: a striped canopy sloping up to the back, and a scalloped flap at the front.
        int awningX = counterX - overhang;
        int awningW = counterW + overhang * 2;
        int stripes = Math.max(4, (awningW / Math.max(3, Math.round(unit * 0.75f))) & ~1);
        float stripeW = awningW / (float) stripes;
        for (int s = 0; s < stripes; s++) {
            int stripeColor = s % 2 == 0 ? awning : STRIPE_LIGHT;
            float x0 = awningX + s * stripeW;
            quad(context, x0 + awningDX, awningY - awningDY, stripeW, 0, -awningDX, awningDY,
                    s % 2 == 0 ? awningDark : 0xFFC9C4B8);
            int flapX0 = Math.round(x0);
            int flapX1 = Math.round(x0 + stripeW);
            context.fill(flapX0, awningY, flapX1, awningY + valance - 1, stripeColor);
            // Scallops: each stripe's flap rounds off at the bottom.
            context.fill(flapX0 + 1, awningY + valance - 1, flapX1 - 1, awningY + valance, stripeColor);
        }
        context.fill(awningX, awningY - 1, awningX + awningW, awningY, MarketLook.shade(color, 0.5f));

        // The sign: the stall's name, and how much it has.
        int labelY = cellY + graphicH + 1;
        Component name = Component.translatable(category.key());
        int nameW = font.width(name);
        int signW = Math.min(cellW - 4, Math.max(nameW + 10, counterW + 8));
        int signX = cellX + (cellW - signW) / 2;
        tile(context, SIGN, signX, labelY, signW, 11, 11, 0xFF9A9A9A);
        FilterMenu.outline(context, signX, labelY, signW, 11, 0xFF241810);
        int nameColor = hovered ? 0xFFFFE070 : empty ? 0xFF9A9AA0 : 0xFFF4F0E6;
        context.centeredText(font, name, cellX + cellW / 2, labelY + 2, nameColor);
        String count = loading && stall.offers() == 0
                ? "..."
                : Component.translatable("betterloka.market.offers_count", stall.offers()).getString();
        context.centeredText(font, count, cellX + cellW / 2, labelY + 12,
                empty ? 0xFF6A6A72 : 0xFFB8B8C4);
    }

    /** Tiles a block texture over a rectangle, a block per {@code unit} pixels. */
    private static void tile(GuiGraphicsExtractor context, Identifier texture, int x, int y, int w, int h,
                             float unit, int color) {
        int step = Math.max(2, Math.round(unit));
        for (int ty = y; ty < y + h; ty += step) {
            int th = Math.min(step, y + h - ty);
            for (int tx = x; tx < x + w; tx += step) {
                int tw = Math.min(step, x + w - tx);
                int regionW = Math.max(1, Math.round(16f * tw / step));
                int regionH = Math.max(1, Math.round(16f * th / step));
                context.blit(RenderPipelines.GUI_TEXTURED, texture, tx, ty, 0, 0, tw, th,
                        regionW, regionH, 16, 16, color);
            }
        }
    }

    /**
     * A block texture over a slanted face: the parallelogram from {@code (ox, oy)} along
     * {@code (ax, ay)} and {@code (bx, by)}, with the texture repeated {@code repeatA} and
     * {@code repeatB} times along them.
     */
    private static void texturedQuad(GuiGraphicsExtractor context, Identifier texture, float ox, float oy,
                                     float ax, float ay, float bx, float by, float repeatA, float repeatB,
                                     int color) {
        int countA = Math.max(1, Math.round(repeatA));
        int countB = Math.max(1, Math.round(repeatB));
        for (int i = 0; i < countA; i++) {
            for (int j = 0; j < countB; j++) {
                float px = ox + ax * i / countA + bx * j / countB;
                float py = oy + ay * i / countA + by * j / countB;
                transform(context, px, py, ax / countA, ay / countA, bx / countB, by / countB);
                context.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0, 1, 1, 16, 16, 16, 16, color);
                context.pose().popMatrix();
            }
        }
    }

    /** A flat colour over the parallelogram from {@code (ox, oy)} along {@code (ax, ay)} and {@code (bx, by)}. */
    private static void quad(GuiGraphicsExtractor context, float ox, float oy, float ax, float ay, float bx, float by,
                             int color) {
        transform(context, ox, oy, ax, ay, bx, by);
        context.fill(0, 0, 1, 1, color);
        context.pose().popMatrix();
    }

    /**
     * Pushes a transform that takes the unit square onto the parallelogram. The two edges are taken
     * in whichever order keeps the square's winding, so a face is never turned over and dropped.
     */
    private static void transform(GuiGraphicsExtractor context, float ox, float oy, float ax, float ay,
                                  float bx, float by) {
        context.pose().pushMatrix();
        if (ax * by - ay * bx < 0) {
            context.pose().mul(new Matrix3x2f(bx, by, ax, ay, ox, oy));
        } else {
            context.pose().mul(new Matrix3x2f(ax, ay, bx, by, ox, oy));
        }
    }
}
