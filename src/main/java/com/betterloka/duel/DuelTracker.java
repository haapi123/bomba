package com.betterloka.duel;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.ArenaRank;
import com.betterloka.config.BetterLokaConfig;
import com.betterloka.stats.ArenaService;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    /** How long the ranked lookup may take before the summary says it got no answer. */
    private static final long RANKS_TIMEOUT_SECONDS = 30;

    /** Development only: count fights against any creature, so a dummy can stand in for a player. */
    public static boolean devAnyTarget;

    private static DuelTracker instance;

    private final BetterLokaConfig config;
    private final Supplier<ArenaService> arena;
    private final DuelSession session = new DuelSession();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, UUID> uuids = new HashMap<>();
    /** Everybody's ping in the player list while a duel is on, by UUID: ids change between servers. */
    private final Map<UUID, PingTrace> pings = new HashMap<>();
    /** The opponent's ranked record, looked up as soon as they are known so the summary has it. */
    private UUID ranksFor;
    private CompletableFuture<List<ArenaService.Career>> ranks;
    /** Where the player was last tick: a different player or world is a move. */
    private net.minecraft.client.player.LocalPlayer seenPlayer;
    private ClientLevel seenLevel;
    private Vec3 lastPosition;
    private int joinIn = -1;
    private String joinCommand;
    private long lastJoinAt;

    /** @param arena Loka's ranked ladders, for the opponent's ranks; may give {@code null} */
    public DuelTracker(BetterLokaConfig config, Supplier<ArenaService> arena) {
        this.config = config;
        this.arena = arena;
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

    /** Starts the ranked lookup for whoever the opponent has turned out to be, once per opponent. */
    private void lookUpRanks(UUID uuid, String name) {
        ArenaService service = arena == null ? null : arena.get();
        if (service == null || uuid == null
                || (uuid.equals(ranksFor) && ranks != null && !ranks.isCompletedExceptionally())) {
            // Already asked — unless that ask failed, when a rematch asks again.
            return;
        }
        ranksFor = uuid;
        ranks = service.careers(name, uuid).orTimeout(RANKS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
                lookUpRanks(uuids.get(opponent), names.get(opponent));
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

    /** Says how a duel went, if there is one to say and the player still wants to hear it. */
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

        MutableComponent text = summaryText(summary, client.player.getName().getString(), opponent);
        for (Component line : pingLines(opponent, ping, own)) {
            text.append(Component.literal("\n ")).append(line);
        }
        lookUpRanks(uuid, opponent);
        CompletableFuture<List<ArenaService.Career>> lookup = uuid != null && uuid.equals(ranksFor) ? ranks : null;
        if (lookup != null && lookup.isDone()) {
            for (Component line : rankLines(lookup)) {
                text.append(Component.literal("\n ")).append(line);
            }
        } else if (lookup != null) {
            // Not in yet — Loka's ladders were slow, or the index is still being built: said when
            // it comes, under the summary.
            lookup.whenComplete((careers, failure) -> client.execute(() -> {
                for (Component line : rankLines(lookup)) {
                    chat(client, line);
                }
            }));
        }
        chat(client, text);
        BetterLoka.LOGGER.info("[betterloka] duel vs {} said in chat (ping {}, ranks {})", opponent,
                ping == null ? "none" : ping.readings() + " readings",
                lookup == null ? "none" : lookup.isDone() ? "in" : "to follow");
    }

    /** The duel in three lines: who and how long, then each side's hits, crits and sweeps. */
    static MutableComponent summaryText(DuelStats.Summary summary, String you, String opponent) {
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
        boolean known = side.accuracy() >= 0;
        return Component.translatable("betterloka.duel.line", who,
                        Component.literal(known ? side.accuracy() + "%" : "—").withStyle(ChatFormatting.YELLOW),
                        Component.literal(side.hits() + "/" + (known ? side.swings() : "?")).withStyle(ChatFormatting.WHITE),
                        Component.literal(side.critShare() + "%").withStyle(ChatFormatting.YELLOW),
                        Component.literal(side.sweepShare() + "%").withStyle(ChatFormatting.YELLOW),
                        Component.literal(String.valueOf(side.bestCombo())).withStyle(ChatFormatting.WHITE))
                .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent.ShowText(details)));
    }

    /**
     * The opponent's ping over the duel, in two lines: the average and the highest, then the spikes.
     * Both carry a tooltip saying where the numbers come from and how many readings they rest on —
     * the server refreshes the player list's ping only every half a minute or so.
     */
    static List<Component> pingLines(String opponent, PingTrace.Summary ping, PingTrace.Summary own) {
        Component name = Component.literal(opponent).withStyle(ChatFormatting.RED);
        if (ping == null) {
            return List.of(Component.translatable("betterloka.duel.ping_none", name)
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("betterloka.duel.ping_none_hover")))));
        }
        MutableComponent hover = Component.translatable("betterloka.duel.ping_hover", ping.readings(), PingTrace.SPIKE_MS);
        if (ping.spikeCount() > 0) {
            List<String> changes = new ArrayList<>();
            for (PingTrace.Spike spike : ping.spikes()) {
                changes.add(clock(spike.afterMillis()) + " " + signed(spike.change()));
            }
            hover.append(Component.literal("\n")).append(Component.translatable("betterloka.duel.ping_changes",
                    String.join(", ", changes)));
        }
        if (own != null) {
            hover.append(Component.literal("\n")).append(Component.translatable("betterloka.duel.ping_yours",
                    ms(own.average()), ms(own.max())));
        }
        Style tip = Style.EMPTY.withColor(ChatFormatting.GRAY).withHoverEvent(new HoverEvent.ShowText(hover));

        Component first = Component.translatable("betterloka.duel.ping", name,
                Component.literal(ms(ping.average())).withStyle(ChatFormatting.YELLOW),
                Component.literal(ms(ping.max())).withStyle(ChatFormatting.YELLOW)).withStyle(tip);
        Component second;
        if (ping.spikeCount() == 0) {
            second = Component.translatable("betterloka.duel.spikes_none", PingTrace.SPIKE_MS).withStyle(tip);
        } else {
            Component count = Component.literal(ping.spikeCount() + " (↑" + ping.up() + " ↓" + ping.down() + ")")
                    .withStyle(ChatFormatting.WHITE);
            Component biggest = Component.literal(signed(ping.biggest())).withStyle(ChatFormatting.YELLOW);
            second = ping.spikeCount() == 1
                    ? Component.translatable("betterloka.duel.spikes_one", PingTrace.SPIKE_MS, count, biggest,
                    Component.literal(clock(ping.spikes().get(0).afterMillis())).withStyle(ChatFormatting.WHITE)).withStyle(tip)
                    : Component.translatable("betterloka.duel.spikes", PingTrace.SPIKE_MS, count, biggest,
                    Component.literal(Math.round(ping.everySeconds()) + " s").withStyle(ChatFormatting.YELLOW)).withStyle(tip);
        }
        return List.of(first, second);
    }

    /**
     * The opponent's ranked record: per ladder they have one on, the highest rank they ever held and
     * how the last published season ended for them, each with its season. Where they stand now is in
     * the tooltip.
     */
    static List<Component> rankLines(CompletableFuture<List<ArenaService.Career>> lookup) {
        List<ArenaService.Career> careers;
        try {
            careers = lookup.getNow(null);
        } catch (RuntimeException failed) {
            careers = null;
        }
        if (careers == null) {
            return List.of(Component.translatable("betterloka.duel.ranks_failed").withStyle(ChatFormatting.GRAY));
        }
        List<Component> lines = new ArrayList<>();
        int lastSeason = -1;
        for (ArenaService.Career career : careers) {
            if (career.isEmpty()) {
                continue;
            }
            if (career.lastSeason() != null) {
                lastSeason = career.lastSeason().season();
            }
            Component now = career.current() == null || career.current().rank() == null
                    ? Component.translatable("betterloka.duel.rank_unranked")
                    : Component.translatable("betterloka.duel.rank_now", rank(career.current().rank()),
                    career.current().wins(), career.current().losses());
            // Short enough for one line of chat however long the ranks: what each column is, is
            // said once above them.
            lines.add(Component.literal(" ").append(Component.translatable("betterloka.duel.rank",
                            Component.literal(career.ladder().shortName()).withStyle(ChatFormatting.GOLD),
                            seasonRank(career.highest(), true), seasonRank(career.lastSeason(), false)))
                    .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                            .withHoverEvent(new HoverEvent.ShowText(now))));
        }
        if (lines.isEmpty()) {
            return List.of(Component.translatable("betterloka.duel.ranks_none").withStyle(ChatFormatting.GRAY));
        }
        Component header = (lastSeason > 0
                ? Component.translatable("betterloka.duel.ranks_header", lastSeason)
                : Component.translatable("betterloka.duel.ranks_header_no_last"))
                .withStyle(style -> style.withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("betterloka.duel.ranks_hover"))));
        lines.add(0, header);
        return lines;
    }

    /**
     * "Bedrock (S23)" or "Diamond I (now)" with its season, or just the rank for the last season,
     * whose number the header gives; a dash for a season they were not ranked in.
     */
    private static Component seasonRank(ArenaService.Best best, boolean withSeason) {
        if (best == null || best.entry().rank() == null) {
            return Component.literal("—").withStyle(ChatFormatting.DARK_GRAY);
        }
        if (!withSeason) {
            return rank(best.entry().rank());
        }
        Component season = best.season() == ArenaService.THIS_SEASON
                ? Component.translatable("betterloka.duel.this_season")
                : Component.literal("S" + best.season());
        return Component.empty().append(rank(best.entry().rank()))
                .append(Component.literal(" (").append(season).append(")").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** A rank in the colour Loka draws its tier in. */
    private static Component rank(ArenaRank rank) {
        return Component.literal(rank.toString()).withStyle(style -> style.withColor(rank.color() & 0xFFFFFF));
    }

    private static String ms(int value) {
        return value + " ms";
    }

    private static String signed(int change) {
        return (change > 0 ? "+" : "-") + Math.abs(change) + " ms";
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
