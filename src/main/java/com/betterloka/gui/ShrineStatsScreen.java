package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.grind.Shrine;
import com.betterloka.grind.ShrineLoot;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What one shrine has given: every item gained over the runs counted, brewing ingredients first.
 *
 * <p>A run is counted from the inventory when the player walked onto the shrine's ground to the
 * inventory ten seconds after its purge, so the list is what the player actually came away with —
 * see {@link ShrineLoot}.
 */
public class ShrineStatsScreen extends Screen implements LokaStyled {
    private static final int MAX_CONTENT_WIDTH = 400;
    private static final int HEADER_HEIGHT = 44;
    private static final int ROW_HEIGHT = 20;
    private static final int SECTION_HEIGHT = 14;
    private static final int PADDING = 6;

    /** A reset has to be pressed twice within this long. */
    private static final long CONFIRM_MILLIS = 3000;

    private final Screen parent;
    private final LokaWindow window;
    private final Shrine shrine;
    private final ScrollPanel scrollPanel = new ScrollPanel();
    private long resetArmedAt;
    private Button resetButton;

    public ShrineStatsScreen(Screen parent, Shrine shrine) {
        super(Component.translatable("betterloka.shrine.stats.title", shrine.label()));
        this.parent = parent;
        this.window = new LokaWindow(this.title, Component.translatable("betterloka.shrine.stats.subtitle"),
                this::onClose).content(MAX_CONTENT_WIDTH, 360);
        this.shrine = shrine;
    }

    private int headerTop() {
        return window.top();
    }

    private int listTop() {
        return headerTop() + HEADER_HEIGHT + 6;
    }

    private int contentWidth() {
        return window.innerWidth();
    }

    private int contentLeft() {
        return window.left();
    }

    private ShrineLoot.Stats stats() {
        return BetterLokaClient.shrines().timers().stats(shrine);
    }

    @Override
    protected void init() {
        window.layout(this.width, this.height);
        int left = contentLeft();
        int width = contentWidth();
        scrollPanel.setViewport(left, listTop(), width, Math.max(20, window.bottom() - 24 - listTop()));
        resetButton = addRenderableWidget(Button.builder(resetLabel(), button -> reset())
                .bounds(left, window.bottom() - 18, width, 18).build());
        resetButton.active = stats().runs() > 0;
    }

    private boolean armed() {
        return System.currentTimeMillis() - resetArmedAt < CONFIRM_MILLIS;
    }

    private Component resetLabel() {
        return Component.translatable(armed() ? "betterloka.waypoints.confirm" : "betterloka.shrine.stats.reset");
    }

    /** First press arms it; a second within a few seconds clears this shrine's statistics. */
    private void reset() {
        if (armed()) {
            BetterLokaClient.shrines().timers().clearStats(shrine);
            resetArmedAt = 0;
            resetButton.active = false;
        } else {
            resetArmedAt = System.currentTimeMillis();
        }
        resetButton.setMessage(resetLabel());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return scrollPanel.mouseScrolled(mouseX, mouseY, verticalAmount)
                || super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (window.mouseClicked(click.x(), click.y())) {
            return true;
        }
        return (click.button() == InputConstants.MOUSE_BUTTON_LEFT && scrollPanel.mouseClicked(click.x(), click.y()))
                || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        return scrollPanel.mouseDragged(click.y()) || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        scrollPanel.mouseReleased();
        return super.mouseReleased(click);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractBackground(context, mouseX, mouseY, delta);
        window.draw(context, this.font, mouseX, mouseY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        // The confirmation runs out by itself; the label has to follow.
        if (resetButton != null && resetArmedAt != 0 && !armed()) {
            resetArmedAt = 0;
            resetButton.setMessage(resetLabel());
        }

        int left = contentLeft();
        int width = contentWidth();
        ShrineLoot.Stats stats = stats();
        renderHeader(context, left, width, stats);

        context.enableScissor(left, scrollPanel.viewportTop(), left + width, scrollPanel.viewportBottom());
        int start = scrollPanel.contentTop();
        int y = start;
        int rowWidth = scrollPanel.contentWidth();
        if (stats.runs() == 0 || stats.totals() == null || stats.totals().isEmpty()) {
            y = renderEmpty(context, left, y, rowWidth, stats.runs() > 0);
        } else {
            List<Map.Entry<String, Long>> ordered = ShrineLoot.ordered(stats.totals());
            List<Map.Entry<String, Long>> brewing = new ArrayList<>();
            List<Map.Entry<String, Long>> other = new ArrayList<>();
            for (Map.Entry<String, Long> entry : ordered) {
                (ShrineLoot.brewing(entry.getKey()) ? brewing : other).add(entry);
            }
            y = renderSection(context, left, y, rowWidth, "betterloka.shrine.stats.brewing", brewing, stats);
            y = renderSection(context, left, y, rowWidth, "betterloka.shrine.stats.other", other, stats);
        }
        context.disableScissor();
        scrollPanel.setContentHeight(y - start);
        scrollPanel.render(context, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphicsExtractor context, int left, int width, ShrineLoot.Stats stats) {
        GuiTheme.panel(context, left, headerTop(), width, HEADER_HEIGHT);
        context.fill(left, headerTop(), left + 2, headerTop() + HEADER_HEIGHT, 0xFF000000 | shrine.kind().color());
        BetterLokaClient.shrineIcons().draw(context, shrine.kind(), left + PADDING + 2, headerTop() + PADDING, 16);
        int textX = left + PADDING + 24;
        context.text(this.font, shrine.label(), textX, headerTop() + PADDING, 0xFF000000 | shrine.kind().color());
        context.text(this.font, Component.translatable("betterloka.shrine.coords", shrine.x(),
                        shrine.hasY() ? String.valueOf(shrine.y()) : "?", shrine.z()),
                textX, headerTop() + PADDING + 11, GuiTheme.MUTED);
        Component runs = Component.translatable("betterloka.shrine.stats.runs", stats.runs());
        context.text(this.font, runs, textX, headerTop() + PADDING + 22, GuiTheme.TEXT);
        if (stats.runs() > 0) {
            String last = Component.translatable("betterloka.shrine.stats.last", TimeFormat.ago(stats.lastRunAt()))
                    .getString();
            context.text(this.font, last, left + width - PADDING - this.font.width(last),
                    headerTop() + PADDING + 22, GuiTheme.MUTED);
        }
    }

    private int renderSection(GuiGraphicsExtractor context, int left, int y, int width, String titleKey,
                              List<Map.Entry<String, Long>> entries, ShrineLoot.Stats stats) {
        if (entries.isEmpty()) {
            return y;
        }
        context.text(this.font, Component.translatable(titleKey), left + 1, y + 3, GuiTheme.ACCENT);
        y += SECTION_HEIGHT;
        Map<String, Integer> last = stats.lastRun() == null ? Map.of() : stats.lastRun();
        for (Map.Entry<String, Long> entry : entries) {
            GuiTheme.panel(context, left, y, width, ROW_HEIGHT);
            ItemStack stack = stackOf(entry.getKey());
            context.item(stack, left + 3, y + 2);
            String customName = ShrineLoot.customName(entry.getKey());
            Component name = customName != null ? Component.literal(customName) : stack.getHoverName();
            context.text(this.font, name, left + 24, y + 6, GuiTheme.TEXT);

            String total = "×" + entry.getValue();
            int lastCount = last.getOrDefault(entry.getKey(), 0);
            String lastText = lastCount > 0
                    ? Component.translatable("betterloka.shrine.stats.last_run", lastCount).getString() : "";
            int right = left + width - PADDING;
            context.text(this.font, lastText, right - this.font.width(lastText), y + 6, GuiTheme.MUTED);
            int totalX = right - this.font.width(lastText) - (lastText.isEmpty() ? 0 : 12) - this.font.width(total);
            context.text(this.font, total, totalX, y + 6, GuiTheme.GOOD);
            y += ROW_HEIGHT + 2;
        }
        return y + 4;
    }

    /** The item a saved key names, for its icon and its name; a barrier if the game has no such item. */
    private static ItemStack stackOf(String key) {
        Identifier id = Identifier.tryParse(ShrineLoot.itemId(key));
        var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return new ItemStack(item == null ? Items.BARRIER : item);
    }

    private int renderEmpty(GuiGraphicsExtractor context, int left, int y, int width, boolean hadRuns) {
        List<String> lines = wrap(Component.translatable(hadRuns
                ? "betterloka.shrine.stats.nothing" : "betterloka.shrine.stats.empty").getString(), width - PADDING * 2);
        int height = PADDING * 2 + 10 * lines.size();
        GuiTheme.panel(context, left, y, width, height);
        int row = y + PADDING;
        for (String line : lines) {
            context.text(this.font, line, left + PADDING, row, GuiTheme.MUTED);
            row += 10;
        }
        return y + height + 4;
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.font.width(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
