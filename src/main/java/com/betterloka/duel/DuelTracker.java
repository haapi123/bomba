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
import java.util.concurrent.CompletableFuture;
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
    /** How long after arriving at the arena the opponent is looked for. */
    private static final long ARENA_LOOK_MILLIS = 3 * 60_000L;
    /** How far the opponent may come towards the player before the fight is taken to be on. */
    private static final double APPROACH_BLOCKS = 4;
    /** A move this far in one tick, short of a server change, is the arena putting them on their start. */
    private static final double START_TELEPORT_BLOCKS = 3;
    /** How far another player may be and still be at this arena. */
    private static final double ARENA_RANGE = 96;

    /**
     * At the arena, waiting for the fight: the VS screen, who it shows, and what the moment it was
     * opened looked like — so a hit, a swing, a teleport onto the start, the opponent coming on or a
     * new game mode can each say the fight has begun.
     */
    private static final class Arena {
        final long since;
        final FightStart start = new FightStart();
        /** The screen was opened, or will not be: once per arrival. */
        boolean done;
        boolean logged;
        DuelVersusScreen screen;
        String opponent;
        long openedAt;
        double openDistance = -1;
        net.minecraft.world.level.GameType mode;
        float health;
        long lastSwingNear;
        Component title;
        long titleAt;

        Arena(long since) {
            this.since = since;
        }
    }

    /**
     * After a duel is said with no result: its result, read off the player's own ranked record on
     * Loka — the record from "Match Ready!" against the record now, a few times over eight minutes —
     * and meanwhile from whatever the server says.
     */
    private static final class ResultCheck {
        /** When to look at the record, after the summary: often at first, then less, for eight minutes. */
        static final long[] AFTER_MILLIS = {3_000, 8_000, 15_000, 30_000, 60_000, 120_000, 240_000, 480_000};
        /** How long the duel's screen says the result is being checked. */
        static final long SHOWN_MILLIS = 120_000;
        /** How long after the summary the server's word on the result is still read. */
        static final long TEXT_MILLIS = 25_000;

        final int entryId;
        final String opponent;
        final CompletableFuture<Map<String, int[]>> before;
        final long saidAt;
        int attempt;
        boolean inFlight;
        boolean done;

        ResultCheck(int entryId, String opponent, CompletableFuture<Map<String, int[]>> before, long saidAt) {
            this.entryId = entryId;
            this.opponent = opponent;
            this.before = before;
            this.saidAt = saidAt;
        }
    }

    /** Development only: count fights against any creature, so a dummy can stand in for a player. */
    public static boolean devAnyTarget;

    private static DuelTracker instance;

    private final BetterLokaConfig config;
    private final Supplier<Duelists> duelists;
    private final Supplier<DuelLog> log;
    private final Supplier<com.betterloka.api.LokaApi> loka;
    private final DuelSession session = new DuelSession();
    /** The player's ranked record as "Match Ready!" came, per ladder {wins, losses}; {@code null} if never asked. */
    private CompletableFuture<Map<String, int[]>> recordBefore;
    /** The latest duel said, while its result is still being found out. */
    private ResultCheck check;
    /** Told about each duel once its result is as known as it will be. */
    private java.util.function.Consumer<DuelLog.Entry> settled = entry -> {
    };
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, UUID> uuids = new HashMap<>();
    /** Everybody's ping in the player list while a duel is on, by UUID: ids change between servers. */
    private final Map<UUID, PingTrace> pings = new HashMap<>();
    /** Whose record was last asked for ahead of the duel's screen. */
    private UUID prefetched;
    /** Splash potions just thrown, waiting for their data: entity id → {thrower, when}. */
    private final Map<Integer, long[]> thrown = new HashMap<>();
    /** Waiting at the arena for the fight, or {@code null}. */
    private Arena arena;
    /** The opponent, if Loka's "Match Ready!" line named them. */
    private String hinted;
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
    public DuelTracker(BetterLokaConfig config, Supplier<Duelists> duelists, Supplier<DuelLog> log,
                       Supplier<com.betterloka.api.LokaApi> loka) {
        this.config = config;
        this.duelists = duelists;
        this.log = log;
        this.loka = loka;
    }

    /** Called with each duel once its result is as known as it will be: right away, or up to two minutes on. */
    public void onSettled(java.util.function.Consumer<DuelLog.Entry> listener) {
        this.settled = listener == null ? entry -> {
        } : listener;
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

    /** Reads one line of chat: Loka's "Match Ready!", and at the arena, word that the fight is on. */
    public void onMessage(Component message) {
        String line = message.getString();
        readOutcome(line, true);
        Arena waiting = arena;
        if (waiting != null) {
            waiting.start.chat(line, System.currentTimeMillis());
        }
        if (!DuelMessages.isMatchReady(line)) {
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
            // The record before it, to tell the result by afterwards.
            recordBefore = ownRecord(client.player.getUUID());
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
        hinted = opponent;
        arena = null;
        if (config.duelAutoAccept() && now - lastJoinAt > JOIN_COOLDOWN_MILLIS) {
            lastJoinAt = now;
            joinCommand = command;
            joinIn = JOIN_DELAY_TICKS;
        }
    }

    /** A title, subtitle or action bar: at the arena, maybe the countdown to the fight. */
    public static void onTitle(Component text) {
        DuelTracker tracker = instance;
        if (tracker != null && text != null) {
            tracker.readOutcome(text.getString(), false);
        }
        Arena waiting = tracker == null ? null : tracker.arena;
        if (waiting == null || text == null) {
            return;
        }
        String line = text.getString();
        BetterLoka.LOGGER.info("[betterloka] arena title: {}", line);
        long now = System.currentTimeMillis();
        waiting.start.title(line, now);
        if (!line.isBlank()) {
            waiting.title = text;
            waiting.titleAt = now;
        }
    }

    /**
     * The arena's latest title (a countdown, as a rule) while it is fresh: the game draws titles
     * under screens, so the VS screen shows it itself.
     */
    public static Component arenaTitle() {
        DuelTracker tracker = instance;
        Arena waiting = tracker == null ? null : tracker.arena;
        return waiting == null || waiting.title == null || System.currentTimeMillis() - waiting.titleAt > 1500
                ? null : waiting.title;
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
        Entity swinger = client.level.getEntity(entityId);
        Arena waiting = tracker.arena;
        if (waiting != null && swinger instanceof LivingEntity near && counts(near)
                && near.distanceTo(client.player) <= SWING_RANGE) {
            waiting.lastSwingNear = System.currentTimeMillis();
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

    /**
     * A thrown splash potion has appeared: the server names its thrower in the spawn itself. What it
     * is arrives with its data a moment later ({@link #onEntityData}); it is held until then.
     */
    public static void onSplashPotion(int entityId, int throwerId) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.session.duel() == null || throwerId <= 0) {
            return;
        }
        tracker.thrown.put(entityId, new long[]{throwerId, System.currentTimeMillis()});
        onEntityData(entityId);
    }

    /** Something's data has arrived; for a potion just thrown, that says whether it is a heal. */
    public static void onEntityData(int entityId) {
        DuelTracker tracker = instance;
        if (tracker == null || tracker.thrown.isEmpty()) {
            return;
        }
        long[] held = tracker.thrown.get(entityId);
        Minecraft client = Minecraft.getInstance();
        if (held == null || client.level == null) {
            return;
        }
        Entity entity = client.level.getEntity(entityId);
        if (!(entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion potion)) {
            return;
        }
        ItemStack item = potion.getItem();
        net.minecraft.world.item.alchemy.PotionContents contents = item.get(DataComponents.POTION_CONTENTS);
        if (contents == null || (contents.potion().isEmpty() && contents.customEffects().isEmpty())) {
            // Still the blank potion every thrown one starts as: what it is has not come yet.
            return;
        }
        tracker.thrown.remove(entityId);
        if (com.betterloka.helper.ShulkerScanner.isHealPot(item) && tracker.session.duel() != null) {
            tracker.session.duel().pot((int) held[0]);
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
        if (!thrown.isEmpty()) {
            // Whatever never said what it was in two seconds is let go.
            long now = System.currentTimeMillis();
            thrown.values().removeIf(held -> now - held[1] > 2000);
        }
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
            moved(player == null ? null : player.getId(), now, true);
            lastPosition = player == null ? null : player.position();
        }
        if (player == null) {
            return;
        }
        Vec3 position = player.position();
        double jump = lastPosition == null ? 0 : position.distanceTo(lastPosition);
        if (jump > TELEPORT_BLOCKS) {
            moved(player.getId(), now, false);
        }
        lastPosition = position;
        if (arena != null) {
            atArena(client, player, now, jump);
        }
        DuelStats duel = session.duel();
        if (duel != null) {
            samplePings(client, now);
            int opponent = duel.opponent();
            if (opponent != -1) {
                prefetch(uuids.get(opponent), names.get(opponent));
                // A server that lets the loser die shows their health reach nothing.
                if (client.level != null && client.level.getEntity(opponent) instanceof LivingEntity other
                        && other.getHealth() <= 0f) {
                    duel.died(opponent, now);
                }
            }
        }
        say(client, session.tick(player.getId(), player.isDeadOrDying(), now));
        checkResult(client, now);
    }

    /** @param newWorld another server or world, rather than a long teleport within one */
    private void moved(Integer self, long now, boolean newWorld) {
        boolean toFight = session.duel() != null && !session.duel().fought();
        if (toFight) {
            // On the way to the duel: what the last server said of anybody's ping is not this one's.
            pings.clear();
        }
        session.moved(self, now);
        // Arrived on another server or world after "Match Ready!", with the duel still to fight: the
        // arena, as Loka has a server of its own for duels. A teleport on the lobby is not that.
        if (toFight && self != null && newWorld) {
            arena = new Arena(now);
        } else if (!toFight) {
            arena = null;
        }
    }

    // ---- at the arena ----

    /**
     * Once a tick at the arena: until the opponent is known, look for them, and open the VS screen;
     * while it is open, close it the moment the fight starts.
     */
    private void atArena(Minecraft client, net.minecraft.client.player.LocalPlayer player, long now, double jump) {
        Arena waiting = arena;
        DuelStats duel = session.duel();
        if (duel == null || duel.fought()) {
            close(client, waiting, "first hit");
            arena = null;
            return;
        }
        if (!waiting.done) {
            if (!config.duelScreen() || now - waiting.since > ARENA_LOOK_MILLIS || waiting.start.poll(now)) {
                waiting.done = true;
                return;
            }
            ArenaOpponent.Found found = findOpponent(client, player);
            if (found == null) {
                if (!waiting.logged && now - waiting.since > 10_000) {
                    waiting.logged = true;
                    BetterLoka.LOGGER.info("[betterloka] arena: no opponent yet; sidebar={} nearby={} server={}",
                            sidebar(client), nearby(client, player), onServer(client));
                }
                return;
            }
            UUID uuid = uuidOf(client, found.name());
            prefetch(uuid, found.name());
            if (client.gui.screen() != null && !(client.gui.screen() instanceof ChatScreen)) {
                // Busy with something else: wait until they are not.
                return;
            }
            waiting.done = true;
            waiting.opponent = found.name();
            waiting.openedAt = now;
            waiting.health = player.getHealth();
            waiting.mode = client.gameMode == null ? null : client.gameMode.getPlayerMode();
            Entity them = opponentEntity(client, found.name());
            waiting.openDistance = them == null ? -1 : them.distanceTo(player);
            waiting.screen = new DuelVersusScreen(player.getName().getString(), player.getUUID(), found.name(), uuid);
            client.gui.setScreen(waiting.screen);
            BetterLoka.LOGGER.info("[betterloka] arena: VS screen vs {} (by {}), sidebar={}", found.name(), found.source(),
                    sidebar(client));
            return;
        }
        if (waiting.screen == null) {
            return;
        }
        if (client.gui.screen() != waiting.screen) {
            // Closed with Esc, or replaced by the server.
            waiting.screen = null;
            return;
        }
        String why = fightStarted(client, player, waiting, now, jump);
        if (why != null) {
            close(client, waiting, why);
        }
    }

    /** What says the fight has begun, or {@code null} while nothing does. */
    private String fightStarted(Minecraft client, net.minecraft.client.player.LocalPlayer player, Arena waiting,
                                long now, double jump) {
        if (waiting.start.poll(now)) {
            return "countdown";
        }
        if (waiting.lastSwingNear > waiting.openedAt) {
            return "swing";
        }
        // Health lost since the screen opened; what came back raises the mark, so a blow taken just
        // before it opened (a fall on arrival) does not count.
        if (player.getHealth() < waiting.health - 0.5f) {
            return "hurt";
        }
        waiting.health = Math.max(waiting.health, player.getHealth());
        if (jump > START_TELEPORT_BLOCKS) {
            return "teleport";
        }
        if (client.gameMode != null && waiting.mode != null && client.gameMode.getPlayerMode() != waiting.mode) {
            return "game mode";
        }
        Entity them = opponentEntity(client, waiting.opponent);
        if (them != null) {
            double distance = them.distanceTo(player);
            if (waiting.openDistance < 0) {
                waiting.openDistance = distance;
            } else if (distance < waiting.openDistance - APPROACH_BLOCKS) {
                return "opponent coming";
            }
        }
        return null;
    }

    private static void close(Minecraft client, Arena waiting, String why) {
        if (waiting != null && waiting.screen != null && client.gui.screen() == waiting.screen) {
            client.gui.setScreen(null);
            BetterLoka.LOGGER.info("[betterloka] arena: VS screen closed, the fight is on ({})", why);
        }
        if (waiting != null) {
            waiting.screen = null;
        }
    }

    private ArenaOpponent.Found findOpponent(Minecraft client, net.minecraft.client.player.LocalPlayer player) {
        ArenaOpponent.Found found = ArenaOpponent.find(player.getName().getString(), sidebar(client),
                nearby(client, player), onServer(client));
        if (found == null && hinted != null) {
            found = new ArenaOpponent.Found(hinted, ArenaOpponent.Source.SIDEBAR_LABEL);
        }
        return found;
    }

    /** The sidebar as it is shown: its title, then each line with its team's prefix and suffix. */
    private static List<String> sidebar(Minecraft client) {
        List<String> lines = new ArrayList<>();
        if (client.level == null) {
            return lines;
        }
        net.minecraft.world.scores.Scoreboard board = client.level.getScoreboard();
        net.minecraft.world.scores.Objective objective = board.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (objective == null) {
            return lines;
        }
        lines.add(objective.getDisplayName().getString());
        for (net.minecraft.world.scores.PlayerScoreEntry entry : board.listPlayerScores(objective)) {
            if (!entry.isHidden()) {
                lines.add(net.minecraft.world.scores.PlayerTeam.formatNameForTeam(board.getPlayersTeam(entry.owner()),
                        entry.ownerName()).getString());
            }
        }
        return lines;
    }

    /** The other players the game has nearby, not watching. */
    private static List<String> nearby(Minecraft client, net.minecraft.client.player.LocalPlayer player) {
        List<String> names = new ArrayList<>();
        if (client.level == null) {
            return names;
        }
        for (Player other : client.level.players()) {
            if (other != player && !other.isSpectator() && other.distanceTo(player) <= ARENA_RANGE) {
                names.add(other.getName().getString());
            }
        }
        return names;
    }

    /** Everybody on the server's player list, not watching. */
    private static List<String> onServer(Minecraft client) {
        List<String> names = new ArrayList<>();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return names;
        }
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getGameMode() != net.minecraft.world.level.GameType.SPECTATOR) {
                names.add(info.getProfile().name());
            }
        }
        return names;
    }

    private static UUID uuidOf(Minecraft client, String name) {
        ClientPacketListener connection = client.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(name);
        return info == null ? null : info.getProfile().id();
    }

    /** The opponent in the world, if the game has them. */
    private static Entity opponentEntity(Minecraft client, String name) {
        if (client.level == null || name == null) {
            return null;
        }
        for (Player other : client.level.players()) {
            if (other.getName().getString().equalsIgnoreCase(name)) {
                return other;
            }
        }
        if (devAnyTarget) {
            for (Entity entity : client.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity && entity.getName().getString().equalsIgnoreCase(name)) {
                    return entity;
                }
            }
        }
        return null;
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
        BetterLoka.LOGGER.info("[betterloka] duel vs {} said in chat (duel #{}, result {}, ping {})", opponent,
                entry == null ? "-" : entry.id(), summary.result(), ping == null ? "none" : ping.readings() + " readings");
        if (entry != null) {
            settle(check);
            check = new ResultCheck(entry.id(), opponent, recordBefore, System.currentTimeMillis());
            recordBefore = null;
            if (summary.result() != DuelStats.Result.UNKNOWN) {
                // Known already: nothing to wait for.
                settle(check);
            }
        }
    }

    // ---- the result, when nobody was seen to die ----

    /**
     * A title, the action bar or a line of chat while a duel is on or just over: maybe the server
     * saying how it went. Chat that starts with a player's name is a player talking, and is not read.
     */
    private void readOutcome(String text, boolean chat) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !config.duelStats()) {
            return;
        }
        long now = System.currentTimeMillis();
        DuelStats duel = session.duel();
        boolean fighting = duel != null && duel.fought();
        DuelStats.Summary pending = session.pending();
        boolean lately = check != null && now - check.saidAt < ResultCheck.TEXT_MILLIS;
        if (!fighting && pending == null && !lately) {
            return;
        }
        ClientPacketListener connection = client.getConnection();
        if (chat && DuelOutcome.looksLikePlayerChat(text, name -> connection != null && connection.getPlayerInfo(name) != null)) {
            return;
        }
        String opponent = fighting ? names.get(duel.opponent())
                : pending != null ? names.get(pending.opponentId()) : check.opponent;
        DuelStats.Result result = DuelOutcome.fromText(text, client.player.getName().getString(), opponent);
        if (result == null) {
            return;
        }
        BetterLoka.LOGGER.info("[betterloka] duel result {} read from \"{}\"", result, text);
        if (!session.decide(result, now) && lately && !check.done) {
            resolve(check.entryId, result, null, "text");
            settle(check);
        }
    }

    /** Asks for the player's ranked record now, per ladder {wins, losses}. */
    private CompletableFuture<Map<String, int[]>> ownRecord(UUID self) {
        com.betterloka.api.LokaApi api = loka == null ? null : loka.get();
        if (api == null || self == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no Loka API"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                com.betterloka.api.model.LokaPlayer player = api.findPlayerByUuid(self);
                Map<String, int[]> record = new HashMap<>();
                if (player != null) {
                    player.ranked().forEach((ladder, ranked) ->
                            record.put(ladder.profileKey(), new int[]{ranked.wins(), ranked.losses()}));
                }
                return record;
            } catch (com.betterloka.api.ApiException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, api.executor());
    }

    /** Once a tick: the latest duel's record, when the next look at it is due. */
    private void checkResult(Minecraft client, long now) {
        ResultCheck current = check;
        if (current == null || current.done || current.inFlight || client.player == null) {
            return;
        }
        if (current.before == null || current.before.isCompletedExceptionally()) {
            settle(current);
            return;
        }
        if (!current.before.isDone() || now - current.saidAt < ResultCheck.AFTER_MILLIS[current.attempt]) {
            return;
        }
        current.inFlight = true;
        ownRecord(client.player.getUUID()).whenComplete((after, failure) -> client.execute(() -> {
            current.inFlight = false;
            if (current.done) {
                return;
            }
            DuelOutcome.Recorded recorded = failure == null ? DuelOutcome.fromRecords(current.before.join(), after) : null;
            if (recorded != null) {
                BetterLoka.LOGGER.info("[betterloka] duel #{} result {} on {} from the ranked record", current.entryId,
                        recorded.result(), recorded.ladder());
                resolve(current.entryId, recorded.result(), recorded.ladder(), "profile");
                settle(current);
            } else if (++current.attempt >= ResultCheck.AFTER_MILLIS.length) {
                settle(current);
            }
        }));
    }

    private void resolve(int entryId, DuelStats.Result result, String ladder, String by) {
        DuelLog duelLog = log == null ? null : log.get();
        if (duelLog != null) {
            duelLog.resolve(entryId, result, ladder, by);
        }
    }

    /** The check is over: the duel is as known as it will be. */
    private void settle(ResultCheck finished) {
        if (finished == null || finished.done) {
            return;
        }
        finished.done = true;
        DuelLog duelLog = log == null ? null : log.get();
        DuelLog.Entry entry = duelLog == null ? null : duelLog.byId(finished.entryId);
        if (entry != null) {
            try {
                settled.accept(entry);
            } catch (RuntimeException e) {
                BetterLoka.LOGGER.warn("[betterloka] after duel #{}", finished.entryId, e);
            }
        }
    }

    /** Whether duel {@code id}'s result is still being found out. */
    public static boolean checking(int id) {
        DuelTracker tracker = instance;
        ResultCheck current = tracker == null ? null : tracker.check;
        return current != null && current.entryId == id && !current.done
                && System.currentTimeMillis() - current.saidAt < ResultCheck.SHOWN_MILLIS;
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
                + " pots " + summary.you().pots() + " | them pots " + summary.them().pots()
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
