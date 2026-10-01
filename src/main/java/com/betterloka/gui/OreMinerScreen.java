package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.miner.MinerSites;
import com.betterloka.miner.OreKind;
import com.betterloka.miner.OreMiner;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The Ore Miner's settings, and what a run is doing.
 *
 * <p>The three places it works at are not set here: they are marked in the world with
 * {@code /lokabetter crafting}, {@code output} and {@code input}, because a bench and a chest are
 * picked by pointing at them and no list of coordinates would be easier. What this screen does is
 * show whether they have been marked, and in which world, so a run that will not start says why
 * before it is asked to.
 */
public class OreMinerScreen extends Screen {
    private static final int MAX_CONTENT_WIDTH = 320;
    private static final int BUTTON_HEIGHT = 18;
    private static final int GAP = 6;
    private static final int ROW = 11;
    private static final int CONTENT_TOP = 30;

    /** The steps the two numbers move in when their buttons are pressed. */
    private static final int[] CRAFT_STEPS = {9, 18, 32, 64, 128, 256, 512, 1024, 0};
    private static final int[] DRAW_STEPS = {9, 32, 64, 128, 256, 512, 1024, 2304};

    private final Screen parent;

    private ButtonWidget oreButton;
    private ButtonWidget craftButton;
    private ButtonWidget drawButton;
    private ButtonWidget runButton;

    public OreMinerScreen(Screen parent) {
        super(Text.translatable("betterloka.miner.title"));
        this.parent = parent;
    }

    private static BetterLokaConfig config() {
        return BetterLokaClient.config();
    }

    private static OreMiner miner() {
        return BetterLokaClient.oreMiner();
    }

    private static MinerSites sites() {
        return BetterLokaClient.minerSites();
    }

    private int contentWidth() {
        return Math.min(this.width - 40, MAX_CONTENT_WIDTH);
    }

    private int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int width = contentWidth();
        int y = CONTENT_TOP + ROW + GAP;

        oreButton = ButtonWidget.builder(oreLabel(), button -> {
                    config().setMinerOre(config().minerOre().next());
                    refresh();
                })
                .dimensions(left, y, width, BUTTON_HEIGHT).build();
        addDrawableChild(oreButton);
        y += BUTTON_HEIGHT + GAP;

        int half = (width - 4) / 2;
        craftButton = ButtonWidget.builder(craftLabel(), button -> {
                    config().setMinerCraftEvery(nextStep(CRAFT_STEPS, config().minerCraftEvery()));
                    refresh();
                })
                .dimensions(left, y, half, BUTTON_HEIGHT).build();
        addDrawableChild(craftButton);

        drawButton = ButtonWidget.builder(drawLabel(), button -> {
                    config().setMinerDrawLimit(nextStep(DRAW_STEPS, config().minerDrawLimit()));
                    refresh();
                })
                .dimensions(left + half + 4, y, width - half - 4, BUTTON_HEIGHT).build();
        addDrawableChild(drawButton);
        y += BUTTON_HEIGHT + GAP * 2 + ROW * 4;

        runButton = ButtonWidget.builder(runLabel(), button -> toggleRun())
                .dimensions(left, y, width, BUTTON_HEIGHT).build();
        addDrawableChild(runButton);

        addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, button -> close())
                .dimensions(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    private void toggleRun() {
        if (miner().running()) {
            miner().stop(null);
        } else {
            String problem = miner().start();
            if (problem != null && this.client != null && this.client.player != null) {
                this.client.player.sendMessage(
                        Text.translatable(problem).formatted(Formatting.RED), false);
            }
        }
        refresh();
    }

    private void refresh() {
        oreButton.setMessage(oreLabel());
        craftButton.setMessage(craftLabel());
        drawButton.setMessage(drawLabel());
        runButton.setMessage(runLabel());
    }

    /** The next value in the ring, so one button walks the whole range without a slider. */
    private static int nextStep(int[] steps, int current) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) {
                return steps[(i + 1) % steps.length];
            }
        }
        return steps[0];
    }

    private Text oreLabel() {
        return Text.translatable("betterloka.miner.ore_choice",
                Text.translatable(config().minerOre().translationKey()));
    }

    private Text craftLabel() {
        int every = config().minerCraftEvery();
        return every == 0
                ? Text.translatable("betterloka.miner.craft_every_never")
                : Text.translatable("betterloka.miner.craft_every", every);
    }

    private Text drawLabel() {
        return Text.translatable("betterloka.miner.draw_limit", config().minerDrawLimit());
    }

    private Text runLabel() {
        return Text.translatable(miner().running()
                ? "betterloka.miner.stop" : "betterloka.miner.start");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12,
                GuiTheme.TEXT);

        int left = contentLeft();
        int width = contentWidth();
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("betterloka.miner.settings").formatted(Formatting.BOLD),
                left, CONTENT_TOP, GuiTheme.TEXT);

        int y = CONTENT_TOP + ROW + GAP + BUTTON_HEIGHT * 2 + GAP * 2;
        GuiTheme.panel(context, left - 4, y - 4, width + 8, ROW * 4 + 8);

        drawSite(context, left, y, "betterloka.miner.site.crafting", sites().crafting());
        drawSite(context, left, y + ROW, "betterloka.miner.site.output", sites().output());
        drawSite(context, left, y + ROW * 2, "betterloka.miner.site.input", sites().input());

        Text status = miner().running()
                ? Text.translatable("betterloka.miner.status_running",
                        Text.translatable("betterloka.miner.stage." + miner().stage().name().toLowerCase(java.util.Locale.ROOT)),
                        miner().minedTotal(), miner().blocksMade())
                : Text.translatable("betterloka.miner.status_idle");
        context.drawTextWithShadow(this.textRenderer, status, left, y + ROW * 3,
                miner().running() ? GuiTheme.LIVE : GuiTheme.MUTED);
    }

    /** One marked place: what it is, where it is, and whether it can be used from here. */
    private void drawSite(DrawContext context, int left, int y, String key, MinerSites.Site site) {
        context.drawTextWithShadow(this.textRenderer, Text.translatable(key), left, y,
                GuiTheme.MUTED);
        Text value;
        int colour;
        if (site == null) {
            value = Text.translatable("betterloka.miner.site.unset");
            colour = GuiTheme.BAD;
        } else if (this.client != null && this.client.world != null
                && !site.inWorld(OreMiner.worldKey(this.client))) {
            value = Text.translatable("betterloka.miner.site.elsewhere");
            colour = GuiTheme.BAD;
        } else {
            value = Text.literal(site.x() + ", " + site.y() + ", " + site.z());
            colour = GuiTheme.GOOD;
        }
        int width = contentWidth();
        context.drawTextWithShadow(this.textRenderer, value,
                left + width - this.textRenderer.getWidth(value), y, colour);
    }

    @Override
    public void tick() {
        // The run ends itself when the ore is gone, so the button has to notice without a click.
        if (runButton != null && !runButton.getMessage().equals(runLabel())) {
            refresh();
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
