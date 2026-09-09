package com.betterloka.helper;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.SimpleOption;

/**
 * Two GUI scales: one for the inventory, one for everything else.
 *
 * <p>An inventory at scale 4 gives a click target twice the size, which is the difference between
 * catching a shulker box and dropping it. The same scale on the hotbar would take a third of the
 * screen, so the two are set apart: the inventory scale goes on when an inventory-shaped screen
 * opens and comes off when it closes.
 *
 * <p>The game's own setting is borrowed rather than replaced. Whatever the player had is remembered
 * the first time this changes it and put back on the way out — on closing the screen, on leaving the
 * world, and on turning the feature off — so nothing survives into a session where the feature is
 * not running.
 */
public final class GuiScaleController {
    /** What Minecraft calls "Auto": let the window decide. */
    public static final int AUTO = 0;

    /** The highest the option offers. The window clamps below this on a small display. */
    public static final int MAX_SCALE = 4;

    private final BetterLokaConfig config;

    /**
     * The player's own scale, held from the first time this changed it.
     *
     * <p>{@code -1} means nothing has been changed and there is nothing to put back — which is also
     * what makes turning the feature off mid-session safe.
     */
    private int borrowedFrom = -1;

    /** What was last applied, so a scale that has not moved does not redraw the world. */
    private int applied = -1;

    public GuiScaleController(BetterLokaConfig config) {
        this.config = config;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }
        // Out of a world, or switched off: hand the player their own setting back.
        if (!config.guiScaleEnabled() || client.world == null) {
            restore(client);
            return;
        }
        apply(client, wanted(client.currentScreen));
    }

    /** Which of the two scales a screen calls for. */
    private int wanted(Screen screen) {
        return isInventory(screen) ? config.inventoryGuiScale() : config.hotbarGuiScale();
    }

    /**
     * Whether a screen is one the inventory scale is for.
     *
     * <p>Every container screen, not only the player's own inventory: a chest, a shulker box and an
     * anvil are all the same problem, which is hitting the right slot.
     */
    public static boolean isInventory(Screen screen) {
        return screen instanceof HandledScreen<?>;
    }

    /**
     * Sets the scale, if it is not already there.
     *
     * <p>The guard is what keeps this from flickering: {@code onResolutionChanged} rebuilds every
     * framebuffer and re-inits the open screen, so calling it on a tick where nothing changed would
     * be a visible stutter sixty times a second.
     */
    private void apply(MinecraftClient client, int scale) {
        int target = clamp(client, scale);
        SimpleOption<Integer> option = client.options.getGuiScale();
        if (borrowedFrom < 0) {
            borrowedFrom = option.getValue();
        }
        if (applied == target && option.getValue() == target) {
            return;
        }
        applied = target;
        option.setValue(target);
        client.onResolutionChanged();
    }

    /** Puts the player's own scale back, once. */
    private void restore(MinecraftClient client) {
        if (borrowedFrom < 0) {
            return;
        }
        SimpleOption<Integer> option = client.options.getGuiScale();
        int own = borrowedFrom;
        borrowedFrom = -1;
        applied = -1;
        if (option.getValue() != own) {
            option.setValue(own);
            client.onResolutionChanged();
            BetterLoka.LOGGER.debug("[betterloka] gui scale handed back to {}", own);
        }
    }

    /**
     * Keeps a scale to what the window can actually show.
     *
     * <p>Minecraft's own option does the same: on a small display scale 4 is not offered, and
     * setting it anyway leaves the GUI larger than the screen with no way back to the options menu.
     */
    private static int clamp(MinecraftClient client, int scale) {
        if (scale <= AUTO) {
            return AUTO;
        }
        int highest = client.getWindow().calculateScaleFactor(MAX_SCALE, client.forcesUnicodeFont());
        return Math.max(1, Math.min(scale, Math.max(1, highest)));
    }

    /** {@code Auto}, or the number. */
    public static String label(int scale) {
        return scale <= AUTO ? "Auto" : String.valueOf(scale);
    }

    /** The next value in the cycle: Auto, 1, 2, 3, 4, back to Auto. */
    public static int next(int scale) {
        return scale >= MAX_SCALE ? AUTO : scale + 1;
    }
}
