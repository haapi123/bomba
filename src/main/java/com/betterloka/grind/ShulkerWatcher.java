package com.betterloka.grind;

import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.util.ActionResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Starts the shulker timer by itself when a shulker the player hit dies.
 *
 * <p>The client is never told who landed a killing blow, so this works from what it can see: which
 * shulkers this player swung at, and which of those then died. That is deliberately narrow. A
 * shulker somebody else finishes off after you have also hit it will start the timer, and one killed
 * with a bow will not start it at all — the box on the card stays the reliable way, and this only
 * saves a click in the ordinary case.
 */
public final class ShulkerWatcher {
    /**
     * How long a shulker stays "mine" after the last swing.
     *
     * <p>Long enough to cover finishing one off, short enough that walking away and coming back to
     * find it gone does not count as a kill.
     */
    private static final long CLAIM_MILLIS = 30_000L;

    private final GrindTimers timers;
    private final BetterLokaConfig config;

    /** Entity id to when it was last hit, for the shulkers this player has swung at. */
    private final Map<Integer, Long> hit = new HashMap<>();

    public ShulkerWatcher(GrindTimers timers, BetterLokaConfig config) {
        this.timers = timers;
        this.config = config;
    }

    public void register() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() && entity instanceof ShulkerEntity) {
                synchronized (hit) {
                    hit.put(entity.getId(), System.currentTimeMillis());
                }
            }
            // Purely an observer: the swing itself is none of this mod's business.
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (hit.isEmpty() || client.world == null) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean died = false;

            synchronized (hit) {
                Iterator<Map.Entry<Integer, Long>> entries = hit.entrySet().iterator();
                while (entries.hasNext()) {
                    Map.Entry<Integer, Long> entry = entries.next();
                    if (now - entry.getValue() > CLAIM_MILLIS) {
                        entries.remove();
                        continue;
                    }
                    Entity entity = client.world.getEntityById(entry.getKey());
                    // Gone from the world, or playing its death animation: either way it is down.
                    if (entity == null || !entity.isAlive()) {
                        entries.remove();
                        died = true;
                    }
                }
            }

            if (died && config.shulkerAutoStart()) {
                timers.shulker().start();
            }
        });
    }
}
