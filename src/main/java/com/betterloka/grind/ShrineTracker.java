package com.betterloka.grind;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import java.util.HashMap;
import java.util.Map;

/**
 * Watches for the player walking onto a shrine's ground and for the server saying one was purged.
 *
 * <p>Both only count in the Nether, which is the only place the shrines are. The purge line is read
 * from chat, never sent: the mod reacts to what the server says and does nothing on the server's
 * side.
 *
 * <p>Which shrine a purge belongs to is worked out by {@link Shrines#resolve}: from the name if it
 * says, otherwise from where the player is standing — the reason a shrine has an area at all.
 */
public final class ShrineTracker {
    /**
     * How long after stepping off a shrine's ground a purge still counts as that shrine's.
     *
     * <p>The kill that purges one is often made from just outside it, and the line can arrive a
     * moment after.
     */
    private static final long RECENT_MILLIS = 60_000L;

    private final ShrineTimers timers;
    private final BetterLokaConfig config;

    private Shrine inside;
    private Shrine lastInside;
    private long leftAt;

    /** For each shrine, the cooldown end already announced as "back", so it is said once. */
    private final Map<String, Long> announced = new HashMap<>();

    /**
     * Set while the mod puts a line of its own on screen. The game hands overlay lines to the same
     * message hook the server's lines arrive through, so without this a notice mentioning a purge
     * would be read back as one — and the notice about that as another, until the stack ran out.
     */
    private boolean speaking;

    public ShrineTracker(ShrineTimers timers, BetterLokaConfig config) {
        this.timers = timers;
        this.config = config;
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // Overlay lines too: a server may put an announcement in the action bar instead of chat.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message.getString()));
    }

    public ShrineTimers timers() {
        return timers;
    }

    /** The shrine whose ground the player is on, or {@code null}. */
    public Shrine inside() {
        return inside;
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
        if (client.player == null || !inNether(client)) {
            leave(System.currentTimeMillis());
            return;
        }
        long now = System.currentTimeMillis();
        Shrine here = Shrines.at(client.player.getX(), client.player.getZ());
        if (here != inside) {
            leave(now);
            inside = here;
            if (here != null) {
                say(client, Component.translatable("betterloka.shrine.entered", here.label(), status(here, now)));
            }
        }
        announceReturns(client, now);
    }

    private void leave(long now) {
        if (inside != null) {
            lastInside = inside;
            leftAt = now;
            inside = null;
        }
    }

    /** Says once, in the Nether, when a shrine whose waypoint is shown has come back. */
    private void announceReturns(Minecraft client, long now) {
        for (Shrine shrine : Shrines.ALL) {
            long end = timers.endsAt(shrine);
            if (end == 0 || now < end || Long.valueOf(end).equals(announced.get(shrine.id()))) {
                continue;
            }
            announced.put(shrine.id(), end);
            // A cooldown that ran out long ago — while logged off — is old news, not an event.
            if (now - end < 5_000 && config.shrineShown(shrine.kind())) {
                say(client, Component.translatable("betterloka.shrine.back", shrine.label()));
            }
        }
    }

    /** The shrine the player is at, or has just stepped off. */
    private Shrine here(long now) {
        if (inside != null) {
            return inside;
        }
        return lastInside != null && now - leftAt < RECENT_MILLIS ? lastInside : null;
    }

    private void say(Minecraft client, Component line) {
        if (client.player == null) {
            return;
        }
        speaking = true;
        try {
            client.player.sendOverlayMessage(line);
        } finally {
            speaking = false;
        }
    }

    /** Reads one line of chat; only a purge announcement does anything. */
    void onMessage(String line) {
        if (speaking) {
            return;
        }
        String name = Shrines.purgedName(line);
        if (name == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Shrine here = here(now);
        Shrine shrine = Shrines.resolve(name, here, timers.learned());
        Minecraft client = Minecraft.getInstance();
        if (shrine == null) {
            BetterLoka.LOGGER.info("[betterloka] no shrine could be tied to the purge of '{}'", name);
            say(client, Component.translatable("betterloka.shrine.unresolved", name));
            return;
        }
        timers.purged(shrine, now);
        // A name of its own, tied to a shrine by where the player stood, is remembered so that the
        // next time it is announced the player can be anywhere. See Shrines.learnable.
        if (shrine.equals(here) && Shrines.learnable(name)) {
            timers.learn(name, shrine);
        }
        say(client, Component.translatable("betterloka.shrine.purged",
                shrine.label(), ShrineTimers.clock(Shrines.COOLDOWN_MILLIS)));
    }

    /** "ready", "12:31", or that nothing is known, for a line about a shrine. */
    public Component status(Shrine shrine, long now) {
        return switch (timers.state(shrine, now)) {
            case READY -> Component.translatable("betterloka.shrine.ready");
            case COOLDOWN -> Component.literal(ShrineTimers.clock(timers.remaining(shrine, now)));
            case UNKNOWN -> Component.translatable("betterloka.shrine.no_timer");
        };
    }
}
