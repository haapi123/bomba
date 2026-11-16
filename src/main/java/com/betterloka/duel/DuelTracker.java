package com.betterloka.duel;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loka Helper's duels: {@code /join} sent by itself when a duel is found (off until switched on), and
 * the duel's hit statistics in chat when it ends (on).
 *
 * <p>A duel starts with Loka's {@code Match Ready! [Click to join]} line, whoever answers it, and ends
 * when one of the two dies, when the player is sent to another server or world after fighting, or
 * when nobody has struck for twenty seconds. Loka fights its duels on a server of their own, so the
 * summary is said once the player has arrived where they are sent afterwards — see
 * {@link DuelSession}. The numbers come from {@link DuelStats}; this class only feeds them from the
 * game and says what they came to. Nothing is sent to the server except the one
 * {@code /join}, and only with the option on.
 */
public final class DuelTracker {
    /** Ticks between the line and the command: the server has the match ready by then. */
    private static final int JOIN_DELAY_TICKS = 5;
    /** One automatic join per this long, however many times the line comes. */
    private static final long JOIN_COOLDOWN_MILLIS = 3000;
    /** How far another player's swing may be and still be at this player. */
    private static final double SWING_RANGE = 8;
    /** How far from the sweep sound its attacker can be: the sound is played where they stand. */
    private static final double SWEEP_RANGE = 1.5;
    /** A jump this far in one tick is a teleport, and after a fight, the end of it. Past a pearl's reach. */
    private static final double TELEPORT_BLOCKS = 128;

    /** Development only: count fights against any creature, so a dummy can stand in for a player. */
    public static boolean devAnyTarget;

    private static DuelTracker instance;

    private final BetterLokaConfig config;
    private final DuelSession session = new DuelSession();
    private final Map<Integer, String> names = new HashMap<>();
    /** Where the player was last tick: a different player or world is a move. */
    private net.minecraft.client.player.LocalPlayer seenPlayer;
    private ClientLevel seenLevel;
    private Vec3 lastPosition;
    private int joinIn = -1;
    private String joinCommand;
    private long lastJoinAt;

    public DuelTracker(BetterLokaConfig config) {
        this.config = config;
    }

    public void register() {
        instance = this;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onMessage(message);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ---- the line that starts it ----

    /** Reads one line of chat; only Loka's "Match Ready!" does anything. */
    public void onMessage(Component message) {
        if (!DuelMessages.isMatchReady(message.getString())) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (config.duelStats()) {
            // A new match: whatever came before it is said now, before its names are forgotten.
            say(client, session.arm(client.player.getId(), now));
            names.clear();
        } else {
            session.forget();
        }
        if (config.duelAutoAccept() && now - lastJoinAt > JOIN_COOLDOWN_MILLIS) {
            lastJoinAt = now;
            joinCommand = DuelMessages.joinCommand(clickCommand(message));
            joinIn = JOIN_DELAY_TICKS;
        }
    }

    /** The command the line's own button runs, if it has one. */
    private static String clickCommand(Component message) {
        Optional<String> found = message.visit((style, text) -> style.getClickEvent() instanceof ClickEvent.RunCommand run
                ? Optional.of(run.command()) : Optional.empty(), Style.EMPTY);
        return found.orElse(null);
    }

    // ---- what the server shows (from DuelPacketsMixin and DuelSwingMixin) ----

    public static void onAnimate(int entityId, int action) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.session.duel() == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        if (action == ClientboundAnimatePacket.CRITICAL_HIT) {
            tracker.session.duel().crit(entityId, System.currentTimeMillis());
        } else if (action == 0 && entityId != client.player.getId()) {
            // Main hand. Only with a weapon in it and near this player: throwing a potion or placing
            // a block swings the arm too, and so does somebody fighting somebody else.
            Entity entity = client.level.getEntity(entityId);
            if (entity instanceof LivingEntity living && counts(living) && holdsWeapon(living.getMainHandItem())
                    && living.distanceTo(client.player) <= SWING_RANGE) {
                tracker.session.duel().swing(entityId);
            }
        }
    }

    public static void onDamage(int victimId, int causeId, int directId) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.session.duel() == null || causeId < 0 || causeId != directId) {
            // Not melee: an arrow, a potion, a fall.
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        Entity victim = client.level.getEntity(victimId);
        Entity attacker = client.level.getEntity(causeId);
        if (!(victim instanceof LivingEntity hurt) || !(attacker instanceof LivingEntity hitter)
                || !counts(hurt) || !counts(hitter)) {
            return;
        }
        tracker.remember(hurt);
        tracker.remember(hitter);
        tracker.session.duel().hit(causeId, victimId, System.currentTimeMillis());
    }

    public static void onSound(Identifier sound, double x, double y, double z) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.session.duel() == null || !SoundEvents.PLAYER_ATTACK_SWEEP.location().equals(sound)) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // The sound is played where the attacker stands; the nearest fighter to it is them.
        Vec3 at = new Vec3(x, y, z);
        Entity nearest = null;
        double best = SWEEP_RANGE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity living && counts(living)) {
                double distance = living.position().distanceTo(at);
                if (distance <= best) {
                    best = distance;
                    nearest = living;
                }
            }
        }
        if (nearest != null) {
            tracker.session.duel().sweep(nearest.getId(), System.currentTimeMillis());
        }
    }

    public static void onEntityEvent(ClientboundEntityEventPacket packet, byte event) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.session.duel() == null || event != EntityEvent.DEATH) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Entity entity = client.level == null ? null : packet.getEntity(client.level);
        if (entity != null) {
            tracker.session.duel().died(entity.getId(), System.currentTimeMillis());
        }
    }

    /** A left click that swung the arm: at somebody or at the air, never at a block. */
    public static void onOwnSwing(HitResult hit) {
        DuelTracker tracker = instance;
        Minecraft client = Minecraft.getInstance();
        if (tracker == null || tracker.session.duel() == null || client.player == null
                || (hit != null && hit.getType() == HitResult.Type.BLOCK)
                || !holdsWeapon(client.player.getMainHandItem())) {
            return;
        }
        tracker.session.duel().swing(client.player.getId());
    }

    private static boolean counts(LivingEntity entity) {
        return entity instanceof Player || devAnyTarget;
    }

    /** A sword, an axe, a mace, a trident, a spear: anything the game itself calls a weapon. */
    private static boolean holdsWeapon(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.WEAPON);
    }

    private void remember(Entity entity) {
        names.put(entity.getId(), entity.getName().getString());
    }

    // ---- the clock ----

    private void tick(Minecraft client) {
        if (joinIn >= 0 && joinIn-- == 0) {
            sendJoin(client);
        }
        long now = System.currentTimeMillis();
        var player = client.player;
        if (player != seenPlayer || client.level != seenLevel) {
            // Another server (with no player at all while the proxy switches), another world, or a
            // respawn: on the way to a duel, or the end of one.
            seenPlayer = player;
            seenLevel = client.level;
            session.moved(player == null ? null : player.getId(), now);
            lastPosition = player == null ? null : player.position();
        }
        if (player == null) {
            return;
        }
        Vec3 position = player.position();
        if (lastPosition != null && position.distanceTo(lastPosition) > TELEPORT_BLOCKS) {
            session.moved(player.getId(), now);
        }
        lastPosition = position;
        say(client, session.tick(player.getId(), player.isDeadOrDying(), now));
    }

    private void sendJoin(Minecraft client) {
        if (client.player == null || joinCommand == null) {
            return;
        }
        client.player.connection.sendCommand(joinCommand);
        chat(client, Component.translatable("betterloka.duel.joining", "/" + joinCommand)
                .withStyle(ChatFormatting.GRAY));
        BetterLoka.LOGGER.info("[betterloka] duel found, sent /{}", joinCommand);
        joinCommand = null;
    }

    /** Says how a duel went, if there is one to say and the player still wants to hear it. */
    private void say(Minecraft client, DuelStats.Summary summary) {
        if (summary == null || !config.duelStats() || client.player == null) {
            return;
        }
        String opponent = names.getOrDefault(summary.opponentId(), "?");
        chat(client, summaryText(summary, client.player.getName().getString(), opponent));
        BetterLoka.LOGGER.info("[betterloka] duel vs {} said in chat", opponent);
    }

    /** The duel in three lines: who and how long, then each side's hits, crits and sweeps. */
    static Component summaryText(DuelStats.Summary summary, String you, String opponent) {
        MutableComponent text = Component.translatable("betterloka.duel.summary",
                Component.literal(opponent).withStyle(ChatFormatting.GOLD), clock(summary.millis()));
        if (summary.result() == DuelStats.Result.WON) {
            text.append(Component.literal(" · ")).append(Component.translatable("betterloka.duel.won")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        } else if (summary.result() == DuelStats.Result.LOST) {
            text.append(Component.literal(" · ")).append(Component.translatable("betterloka.duel.lost")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        text.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                Component.translatable("betterloka.duel.hover"))));
        text.append(Component.literal("\n "))
                .append(line(Component.translatable("betterloka.duel.you").withStyle(ChatFormatting.AQUA), summary.you()));
        text.append(Component.literal("\n "))
                .append(line(Component.literal(opponent).withStyle(ChatFormatting.RED), summary.them()));
        return text;
    }

    /**
     * One side, short enough for one line of chat: hits landed as a share of swings, then how much of
     * what landed was crits and sweeps. The counts behind each share are in the line's tooltip.
     */
    private static Component line(Component who, DuelStats.Side side) {
        Component details = Component.translatable("betterloka.duel.line_hover",
                side.hits(), side.swings(), side.crits(), side.sweeps(), side.bestCombo());
        return Component.translatable("betterloka.duel.line", who,
                        Component.literal(side.accuracy() + "%").withStyle(ChatFormatting.YELLOW),
                        Component.literal(side.hits() + "/" + side.swings()).withStyle(ChatFormatting.WHITE),
                        Component.literal(side.critShare() + "%").withStyle(ChatFormatting.YELLOW),
                        Component.literal(side.sweepShare() + "%").withStyle(ChatFormatting.YELLOW),
                        Component.literal(String.valueOf(side.bestCombo())).withStyle(ChatFormatting.WHITE))
                .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent.ShowText(details)));
    }

    private static String clock(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    /** A line in the player's own chat, with the mod's name in front. Nothing goes to the server. */
    private static void chat(Minecraft client, Component line) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[BetterLoka] ")
                    .withStyle(ChatFormatting.DARK_AQUA).append(line));
        }
    }

    // ---- for the dev harness ----

    public static DuelTracker get() {
        return instance;
    }

    public String devState() {
        DuelStats duel = session.duel();
        if (duel == null) {
            return session.waiting() ? "duel over, summary waiting" : "no duel";
        }
        DuelStats.Summary summary = duel.summary();
        return "you " + summary.you().hits() + "/" + summary.you().swings() + " crits " + summary.you().crits()
                + " sweeps " + summary.you().sweeps() + " | them " + summary.them().hits() + "/" + summary.them().swings()
                + " | opponent " + summary.opponentId() + " fought " + duel.fought();
    }

    /** Development only: the duel's numbers, as the summary would give them. */
    public DuelStats.Summary devSummary() {
        return session.duel() == null ? null : session.duel().summary();
    }
}
