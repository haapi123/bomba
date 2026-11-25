package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Watches for the player walking onto a shrine's ground, and for the timers they started running
 * out.
 *
 * <p>A shrine's timer starts only when the player presses its Start button ({@link #start}): the mod
 * does not read the server's purge announcement, or anything else, to start one by itself.
 *
 * <p>Walking onto a shrine that is not counting down activates it: its marker says so and the other
 * shrines' markers step aside until the player starts its timer or leaves. What is in the inventory
 * at that moment is noted, and compared with the inventory a little after the player starts the
 * timer, which is how a run's haul is counted.
 *
 * <p>The notice that a timer has run out is put in the player's own chat on their own client; the
 * server is sent nothing.
 */
public final class ShrineTracker {
    /**
     * How long after stepping off a shrine's ground a run still counts as that shrine's when the
     * player starts its timer: the last kill is often made from just outside it.
     */
    private static final long RECENT_MILLIS = 60_000L;

    /**
     * How long after the timer is started the inventory is read for the run's haul: the last kill's
     * drops may still be on the ground; this is the time to walk over them.
     */
    static final long LOOT_GRACE_MILLIS = 10_000L;

    /** A restart older than this when first noticed happened while logged off: old news. */
    private static final long FRESH_MILLIS = 5_000L;

    /** One visit to a shrine: from walking onto its ground to starting its timer, or to walking away. */
    private static final class Run {
        final Shrine shrine;
        final Map<String, Integer> before;
        long leftAt;
        long doneAt;

        Run(Shrine shrine, Map<String, Integer> before) {
            this.shrine = shrine;
            this.before = before;
        }
    }

    private final ShrineTimers timers;
    private final BetterLokaConfig config;

    private Shrine inside;
    private Run run;

    /** For each shrine, the timer end already announced, so it is said once. */
    private final Map<String, Long> announced = new HashMap<>();

    public ShrineTracker(ShrineTimers timers, BetterLokaConfig config) {
        this.timers = timers;
        this.config = config;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    public ShrineTimers timers() {
        return timers;
    }

    /** The shrine whose ground the player is on, or {@code null}. */
    public Shrine inside() {
        return inside;
    }

    /**
     * The shrine the player has activated: on its ground, not counting down when they arrived, and
     * its timer not started since. {@code null} when there is none.
     */
    public Shrine activated() {
        return run != null && run.leftAt == 0 && run.doneAt == 0 && run.shrine.equals(inside)
                ? run.shrine : null;
    }

    /**
     * Starts a shrine's timer, from the player's click on its Start button. If they have just been
     * on its ground, their run ends here, and its haul is counted once the last drops have been
     * picked up.
     */
    public void start(Shrine shrine) {
        long now = System.currentTimeMillis();
        timers.start(shrine, now);
        if (run != null && run.shrine.equals(shrine) && run.doneAt == 0
                && (run.leftAt == 0 || now - run.leftAt < RECENT_MILLIS)) {
            run.doneAt = now;
        }
    }

    /**
     * Whether the world being stood in is the Nether.
     *
     * <p>Asked several ways because servers name their worlds as they like: the vanilla Nether key,
     * a world using the Nether's dimension type, a world called something with "nether" in it.
     */
    public static boolean inNether(Minecraft client) {
        if (client == null || client.level == null) {
            return false;
        }
        Level level = client.level;
        if (level.dimension().equals(Level.NETHER)
                || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER)) {
            return true;
        }
        String path = level.dimension().identifier().getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("nether");
    }

    private void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (client.player == null) {
            run = null;
            leave(now);
            return;
        }
        // Wherever the player is: a timer running out is news in the overworld too, and a run's haul
        // is counted even if they step through a portal straight after.
        announceRestarts(client, now);
        finishRun(client.player, now, false);

        if (!inNether(client)) {
            leave(now);
            return;
        }
        Shrine here = Shrines.at(client.player.getX(), client.player.getZ());
        if (here != inside) {
            leave(now);
            inside = here;
            if (here != null) {
                arrive(client, here, now);
            }
        }
    }

    private void arrive(Minecraft client, Shrine here, long now) {
        say(client, Component.translatable("betterloka.shrine.entered", here.label(), status(here, now)));
        if (timers.state(here, now) == ShrineTimers.State.COOLDOWN) {
            return;
        }
        // Stepping off and back on again is the same run, counted from when it first began.
        if (run != null && run.shrine.equals(here) && run.doneAt == 0) {
            run.leftAt = 0;
            return;
        }
        // A haul still waiting to be counted is counted now, before a new run takes its place.
        finishRun(client.player, now, true);
        run = new Run(here, inventory(client.player));
    }

    private void leave(long now) {
        inside = null;
        if (run != null && run.leftAt == 0 && run.doneAt == 0) {
            run.leftAt = now;
        }
    }

    /**
     * Counts a finished run's haul once its grace has passed, or now if {@code force}; and forgets a
     * run walked away from without its timer being started.
     */
    private void finishRun(LocalPlayer player, long now, boolean force) {
        if (run == null) {
            return;
        }
        if (run.doneAt > 0 && (force || now - run.doneAt >= LOOT_GRACE_MILLIS)) {
            Map<String, Integer> gained = ShrineLoot.gained(run.before, inventory(player));
            timers.recordRun(run.shrine, gained, now);
            BetterLoka.LOGGER.info("[betterloka] {} run counted: {}", run.shrine.label(), gained);
            run = null;
        } else if (run.doneAt == 0 && run.leftAt > 0 && now - run.leftAt > RECENT_MILLIS) {
            run = null;
        }
    }

    /** Every item the player is carrying, counted by {@link ShrineLoot#key}. */
    static Map<String, Integer> inventory(LocalPlayer player) {
        Map<String, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            count(counts, inventory.getItem(slot));
        }
        // An item held on the cursor in an open screen is still the player's.
        count(counts, player.containerMenu.getCarried());
        return counts;
    }

    private static void count(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        String key = ShrineLoot.key(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                name == null ? null : name.getString());
        counts.merge(key, stack.getCount(), Integer::sum);
    }

    /** Says once, in chat and in any world, when a shrine timer the player started has run out. */
    private void announceRestarts(Minecraft client, long now) {
        for (Shrine shrine : Shrines.ALL) {
            long end = timers.endsAt(shrine);
            if (end == 0 || now < end || Long.valueOf(end).equals(announced.get(shrine.id()))) {
                continue;
            }
            announced.put(shrine.id(), end);
            if (now - end < FRESH_MILLIS && config.shrineNotifications()) {
                chat(client, Component.translatable("betterloka.shrine.restarted",
                        Component.literal(shrine.label()).withColor(shrine.kind().color()))
                        .withStyle(ChatFormatting.GREEN));
            }
        }
    }

    private void say(Minecraft client, Component line) {
        if (client.player != null) {
            client.player.sendOverlayMessage(line);
        }
    }

    /** A line in the player's own chat, with the mod's name in front. Nothing goes to the server. */
    private void chat(Minecraft client, Component line) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[BetterLoka] ")
                    .withStyle(ChatFormatting.DARK_AQUA).append(line));
        }
    }

    /** "12:31" while it counts down, "Ready" otherwise — a shrine not counting is one to go to. */
    public Component status(Shrine shrine, long now) {
        return timers.state(shrine, now) == ShrineTimers.State.COOLDOWN
                ? Component.literal(ShrineTimers.clock(timers.remaining(shrine, now)))
                : Component.translatable("betterloka.shrine.ready");
    }

    /** Whether a shrine is counting down, which is all the colour of its label depends on. */
    public boolean coolingDown(Shrine shrine, long now) {
        return timers.state(shrine, now) == ShrineTimers.State.COOLDOWN;
    }
}
