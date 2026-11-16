package com.betterloka.duel;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.gui.DuelVersusScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Loka Helper's duels: {@code /join} sent by itself when a duel is found (off until switched on), or
 * else the VS screen with both duelists and a Join button (on); and when it ends, one line in chat
 * that opens the duel's statistics (on), the duel kept in the {@link DuelLog}.
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
    private final Supplier<Duelists> duelists;
    private final Supplier<DuelLog> log;
    private final DuelSession session = new DuelSession();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, UUID> uuids = new HashMap<>();
    /** Everybody's ping in the player list while a duel is on, by UUID: ids change between servers. */
    private final Map<UUID, PingTrace> pings = new HashMap<>();
    /** Whose record was last asked for ahead of the duel's screen. */
    private UUID prefetched;
    /** Where the player was last tick: a different player or world is a move. */
    private net.minecraft.client.player.LocalPlayer seenPlayer;
    private ClientLevel seenLevel;
    private Vec3 lastPosition;
    private int joinIn = -1;
    private String joinCommand;
    private long lastJoinAt;

    /**
     * @param duelists the duelists' ranked records, for the duel screens; may give {@code null}
     * @param log      where finished duels are kept; may give {@code null}
     */
    public DuelTracker(BetterLokaConfig config, Supplier<Duelists> duelists, Supplier<DuelLog> log) {
        this.config = config;
        this.duelists = duelists;
        this.log = log;
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
            uuids.clear();
            pings.clear();
        } else {
            session.forget();
        }
        List<String> texts = texts(message);
        String command = DuelMessages.joinCommand(clickCommand(message));
        String opponent = DuelMessages.opponentIn(texts, client.player.getName().getString());
        // What exactly the line carries, for working out what Loka puts in it.
        BetterLoka.LOGGER.info("[betterloka] Match Ready line: texts={} command=/{} opponent={}", texts, command, opponent);
        if (config.duelAutoAccept() && now - lastJoinAt > JOIN_COOLDOWN_MILLIS) {
            lastJoinAt = now;
            joinCommand = command;
            joinIn = JOIN_DELAY_TICKS;
        } else if (config.duelScreen() && (client.gui.screen() == null || client.gui.screen() instanceof ChatScreen)) {
            String self = client.player.getName().getString();
            UUID selfId = client.player.getUUID();
            client.gui.setScreen(new DuelVersusScreen(self, selfId, opponent, () -> {
                if (Minecraft.getInstance().player != null) {
                    joinCommand = command;
                    sendJoin(Minecraft.getInstance());
                }
            }));
        }
    }

    /** The line's text and every tooltip in it. */
    private static List<String> texts(Component message) {
        List<String> texts = new ArrayList<>();
        texts.add(message.getString());
        message.visit((style, text) -> {
            if (style.getHoverEvent() instanceof HoverEvent.ShowText show) {
                String tip = show.value().getString();
                if (!texts.contains(tip)) {
                    texts.add(tip);
                }
            }
            return Optional.empty();
        }, Style.EMPTY);
        return texts;
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
        if (action == ClientboundAnimatePacket.CRITICAL_HIT) {
            tracker.session.duel().crit(entityId, System.currentTimeMillis());
        }
    }

    /** Somebody else swung an arm (the server never shows a player their own). */
    public static void onSwing(int entityId, boolean mainHand) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.session.duel() == null || !mainHand) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || entityId == client.player.getId()) {
            return;
        }
        // Only with a weapon in that hand and near this player: throwing a potion or placing a block
        // swings the arm too, and so does somebody fighting somebody else.
        Entity entity = client.level.getEntity(entityId);
        if (entity instanceof LivingEntity living && counts(living) && holdsWeapon(living.getMainHandItem())
                && living.distanceTo(client.player) <= SWING_RANGE) {
            tracker.session.duel().swing(entityId);
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
        uuids.put(entity.getId(), entity.getUUID());
    }

    /**
     * Reads the ping of everybody the player can see from the player list. The server refreshes it
     * there on its own schedule — about every thirty seconds — so most ticks read the same values,
     * and {@link PingTrace} keeps only a change.
     */
    private void samplePings(Minecraft client, long now) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null || client.level == null) {
            return;
        }
        for (Player player : client.level.players()) {
            PlayerInfo listed = connection.getPlayerInfo(player.getUUID());
            if (listed != null) {
                pings.computeIfAbsent(player.getUUID(), id -> new PingTrace()).sample(now, listed.getLatency());
            }
        }
    }

    /**
     * Starts loading whoever the opponent has turned out to be, so the duel's screen has their
     * record when it is opened. Once per opponent; the lookup itself is kept for two minutes.
     */
    private void prefetch(UUID uuid, String name) {
        Duelists service = duelists == null ? null : duelists.get();
        if (service == null || uuid == null || uuid.equals(prefetched)) {
            return;
        }
        prefetched = uuid;
        service.load(name, uuid);
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
            moved(player == null ? null : player.getId(), now);
            lastPosition = player == null ? null : player.position();
        }
        if (player == null) {
            return;
        }
        Vec3 position = player.position();
        if (lastPosition != null && position.distanceTo(lastPosition) > TELEPORT_BLOCKS) {
            moved(player.getId(), now);
        }
        lastPosition = position;
        DuelStats duel = session.duel();
        if (duel != null) {
            samplePings(client, now);
            int opponent = duel.opponent();
            if (opponent != -1) {
                prefetch(uuids.get(opponent), names.get(opponent));
            }
        }
        say(client, session.tick(player.getId(), player.isDeadOrDying(), now));
    }

    private void moved(Integer self, long now) {
        if (session.duel() != null && !session.duel().fought()) {
            // On the way to the duel: what the last server said of anybody's ping is not this one's.
            pings.clear();
        }
        session.moved(self, now);
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

    /**
     * Keeps a finished duel in the duel log and says it in chat in one short line, which opens the
     * duel's screen — both sides' hits, crits, sweeps, combo, ping, season and ranks — when clicked.
     */
    private void say(Minecraft client, DuelStats.Summary summary) {
        if (summary == null || !config.duelStats() || client.player == null) {
            return;
        }
        String opponent = names.getOrDefault(summary.opponentId(), "?");
        UUID uuid = uuids.get(summary.opponentId());
        PingTrace theirs = uuid == null ? null : pings.get(uuid);
        PingTrace yours = pings.get(client.player.getUUID());
        PingTrace.Summary ping = theirs == null ? null : theirs.summary(summary.startedAt(), summary.endedAt());
        PingTrace.Summary own = yours == null ? null : yours.summary(summary.startedAt(), summary.endedAt());

        DuelLog duelLog = log == null ? null : log.get();
        DuelLog.Entry entry = duelLog == null ? null : duelLog.add(System.currentTimeMillis(),
                client.player.getName().getString(), client.player.getUUID(), opponent, uuid, summary, own, ping);
        chat(client, summaryLine(summary, opponent, entry == null ? -1 : entry.id()));
        BetterLoka.LOGGER.info("[betterloka] duel vs {} said in chat (duel #{}, ping {})", opponent,
                entry == null ? "-" : entry.id(), ping == null ? "none" : ping.readings() + " readings");
    }

    /** The click that opens duel {@code id}'s screen; handled in the game, never sent anywhere. */
    public static ClickEvent openDuel(int id) {
        return new ClickEvent.Custom(Identifier.fromNamespaceAndPath("betterloka", "duel/" + id), Optional.empty());
    }

    /** {@code betterloka:duel/12} → 12, or -1 if the click is not one of these. */
    public static int duelId(Identifier id) {
        if (id == null || !"betterloka".equals(id.getNamespace()) || !id.getPath().startsWith("duel/")) {
            return -1;
        }
        try {
            return Integer.parseInt(id.getPath().substring("duel/".length()));
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    /** "Duel vs Knucior · 0:42 · Won", and under it the link to the whole of it. */
    static MutableComponent summaryLine(DuelStats.Summary summary, String opponent, int id) {
        MutableComponent text = Component.translatable("betterloka.duel.summary",
                Component.literal(opponent).withStyle(ChatFormatting.GOLD), clock(summary.millis()));
        if (summary.result() == DuelStats.Result.WON) {
            text.append(Component.literal(" · ")).append(Component.translatable("betterloka.duel.won")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        } else if (summary.result() == DuelStats.Result.LOST) {
            text.append(Component.literal(" · ")).append(Component.translatable("betterloka.duel.lost")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }
        if (id <= 0) {
            return text;
        }
        text.append(Component.literal("\n ")).append(Component.translatable("betterloka.duel.open")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE));
        ClickEvent click = openDuel(id);
        return text.withStyle(style -> style.withClickEvent(click)
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("betterloka.duel.open_hover"))));
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

    /** Development only: a reading of somebody's ping, as the player list would give it. */
    public void devPing(UUID uuid, int ms) {
        pings.computeIfAbsent(uuid, id -> new PingTrace()).sample(System.currentTimeMillis(), ms);
    }

    /** Development only: the duel's numbers, as the summary would give them. */
    public DuelStats.Summary devSummary() {
        return session.duel() == null ? null : session.duel().summary();
    }
}
