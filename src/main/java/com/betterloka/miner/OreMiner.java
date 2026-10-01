package com.betterloka.miner;

import com.betterloka.BetterLoka;
import com.betterloka.config.BetterLokaConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Works a pile of ore the player already owns: places it, breaks it, crafts what falls out into
 * blocks, puts those away, and fetches the next lot.
 *
 * <p>It stands still. Every block it touches — the spot it mines on, the crafting bench, the two
 * containers — has to be within the player's own reach from where they are standing when they start,
 * and the run stops the moment that stops being true. Nothing here walks, and nothing here reaches
 * further than a hand would.
 *
 * <p>It is also deliberately visible. The head turns to look at what is being placed, broken or
 * opened, the arm swings, and the screens open and close as they would under a hand. Everything goes
 * through the same {@link ClientPlayerInteractionManager} the keyboard and mouse drive, so the server
 * sees ordinary play at an ordinary pace rather than anything it has no packet for.
 *
 * <p>One action per tick at most, and several stages wait a tick for the server to answer. That is
 * not caution for its own sake: a container's contents only exist on the client once the server has
 * sent them, so a machine that did not wait would read an empty chest and decide the run was over.
 */
public final class OreMiner {
    /** What the run is doing. */
    public enum Stage {
        IDLE, MINING, CRAFTING, STORING, DRAWING
    }

    /** The steps one visit to the bench goes through. */
    private enum CraftStep {
        TIDY, FILL, SPREAD, RETURN, AWAIT, COUNT
    }

    /** How long to wait for a screen the server has been asked to open, in ticks. */
    private static final int SCREEN_TIMEOUT_TICKS = 60;

    /**
     * A breather between actions, so the run reads as play rather than as a burst.
     *
     * <p>Only used where nothing is waiting on the server: moving the ore into the off-hand, and
     * stepping away from a block. Everything that touches a container goes through
     * {@link #containerPace()} instead, because that is where latency bites.
     */
    private static final int ACTION_COOLDOWN_TICKS = 2;

    /**
     * How long to leave between two clicks inside a container, in ticks.
     *
     * <p>Every one of these is a round trip. The client shows the result of a slot click straight
     * away and the server confirms it a moment later, so on a connection with any latency a run
     * that clicks as fast as it can is reading its own guesses rather than what the server has
     * actually done — and a server that dislikes the pace simply refuses and the two drift apart.
     * Loka sits above a hundred milliseconds, which is three ticks before an answer can even
     * arrive, so the pace is set well clear of that rather than at it.
     */
    private int containerPace() {
        return config.minerPaceTicks();
    }

    /** How far the player may drift from where they started before the run gives up. */
    private static final double MAX_DRIFT = 2.0;

    /** The offhand's button number in a slot click, which is not a hotbar index. */
    private static final int OFFHAND_SWAP_BUTTON = 40;

    /** The pretend slot a drag begins and ends on. */
    private static final int QUICK_CRAFT_MARKER_SLOT = -999;

    private final BetterLokaConfig config;
    private final MinerSites sites;

    private Stage stage = Stage.IDLE;
    private boolean running;
    private int cooldown;
    private int waited;

    /** Where the player stood when the run began, so drifting away can end it. */
    private Vec3d anchor = Vec3d.ZERO;

    /** The one block the ore is placed on and broken at, reused for the whole run. */
    private BlockPos spot;

    /** Whether our own ore is standing on the spot, so its going away can be counted as mined. */
    private boolean placed;

    /** How long a drop has been sitting in the square, so it can never stall the run for good. */
    private int blockedTicks;

    /** Whether the attack button is being held, so it is released exactly once. */
    private boolean attacking;

    /** Where the crafting stage has got to; see tickCrafting for why it has steps at all. */
    private CraftStep craftStep = CraftStep.TIDY;
    private int craftWaited;

    /** The slot the stack being spread came from, so the leftovers go back where they belong. */
    private int dragSource;
    private int blocksBefore;

    private int minedSinceCraft;
    private int minedTotal;
    private int blocksMade;
    private Text lastMessage = Text.empty();

    /** Set when the run ends by itself, so the screen and the chat can say why. */
    private String stopReason;

    public OreMiner(BetterLokaConfig config, MinerSites sites) {
        this.config = config;
        this.sites = sites;
    }

    // --- what the screen and the commands see ---

    public boolean running() {
        return running;
    }

    public Stage stage() {
        return stage;
    }

    public int minedTotal() {
        return minedTotal;
    }

    public int minedSinceCraft() {
        return minedSinceCraft;
    }

    public int blocksMade() {
        return blocksMade;
    }

    public Text lastMessage() {
        return lastMessage;
    }

    public String stopReason() {
        return stopReason;
    }

    /**
     * Begins a run, or explains why it cannot.
     *
     * @return null when it started, or a translation key naming what is missing
     */
    public String start() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return "betterloka.miner.error.no_world";
        }
        String world = worldKey(client);
        if (!MinerSites.usable(sites.crafting(), world)) {
            return "betterloka.miner.error.no_crafting";
        }
        if (!MinerSites.usable(sites.output(), world)) {
            return "betterloka.miner.error.no_output";
        }
        if (!MinerSites.usable(sites.input(), world)) {
            return "betterloka.miner.error.no_input";
        }
        for (MinerSites.Site site : new MinerSites.Site[] {
                sites.crafting(), sites.output(), sites.input()}) {
            if (!inReach(player, site.pos())) {
                return "betterloka.miner.error.out_of_reach";
            }
        }
        if (player.getMainHandStack().isEmpty()) {
            return "betterloka.miner.error.no_pickaxe";
        }

        anchor = player.getEntityPos();
        spot = null;
        placed = false;
        blockedTicks = 0;
        craftStep = CraftStep.TIDY;
        minedSinceCraft = 0;
        minedTotal = 0;
        blocksMade = 0;
        stopReason = null;
        cooldown = 0;
        waited = 0;
        stage = Stage.MINING;
        running = true;
        say("betterloka.miner.started", Text.translatable(ore().translationKey()));
        return null;
    }

    /** Ends the run. {@code reason} is a translation key, or null when a person asked. */
    public void stop(String reason) {
        if (!running) {
            return;
        }
        running = false;
        stage = Stage.IDLE;
        stopReason = reason;
        placed = false;
        MinecraftClient client = MinecraftClient.getInstance();
        setAttacking(client, false);
        if (client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        if (reason != null) {
            say(reason);
        }
        say("betterloka.miner.stopped", minedTotal, blocksMade);
    }

    /** Development only: one line per breaking tick, for finding out why a break does not land. */
    public static boolean devTrace;

    private OreKind ore() {
        return config.minerOre();
    }

    // --- the loop ---

    public void tick() {
        if (!running) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            stop("betterloka.miner.error.no_world");
            return;
        }
        if (player.getEntityPos().distanceTo(anchor) > MAX_DRIFT) {
            stop("betterloka.miner.error.moved");
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        try {
            switch (stage) {
                case MINING -> tickMining(client, player);
                case CRAFTING -> tickCrafting(client, player);
                case STORING -> tickStoring(client, player);
                case DRAWING -> tickDrawing(client, player);
                case IDLE -> stop(null);
            }
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.warn("[betterloka] the ore miner hit a snag and stopped", e);
            stop("betterloka.miner.error.failed");
        }
    }

    // --- mining ---

    private void tickMining(MinecraftClient client, ClientPlayerEntity player) {
        if (client.currentScreen != null) {
            // A screen left open from the last stage, or one the player opened. Either way the
            // player's own hands come first: shut ours, and if it is theirs, stand down.
            setAttacking(client, false);
            closeScreen(client, player);
            return;
        }
        int held = countOre(player);
        if (held == 0) {
            // Everything in reach is mined. Turn it into blocks before fetching more.
            setAttacking(client, false);
            stage = Stage.CRAFTING;
            return;
        }
        if (config.minerCraftEvery() > 0 && minedSinceCraft >= config.minerCraftEvery()) {
            setAttacking(client, false);
            stage = Stage.CRAFTING;
            return;
        }
        if (!ore().isOre(player.getOffHandStack()) && !moveOreToOffHand(client, player)) {
            stage = Stage.CRAFTING;
            return;
        }
        if (spot == null || !usableSpot(client.world, spot)) {
            spot = findSpot(client, player);
            if (spot == null) {
                stop("betterloka.miner.error.no_room");
                return;
            }
        }

        var state = client.world.getBlockState(spot);
        if (state.isAir()) {
            if (placed) {
                // It was our ore last tick and it is gone now, so that is one mined. Give the drop
                // its moment to be collected before the square is filled again.
                placed = false;
                minedSinceCraft++;
                minedTotal++;
                setAttacking(client, false);
                cooldown = PICKUP_GRACE_TICKS;
                return;
            }
            setAttacking(client, false);
            placeOre(client, player, spot);
        } else if (ore().isOreBlock(state.getBlock())) {
            placed = true;
            breakSpot(client, player, spot);
        } else {
            // Something else is standing there now — gravel poured in, or a neighbour built. Find
            // somewhere else rather than mining a block that is not ours.
            setAttacking(client, false);
            placed = false;
            spot = null;
        }
    }

    /**
     * How long to leave a fresh drop alone before filling its square again.
     *
     * <p>An item cannot be picked up for the first ten ticks of its life, so without a pause the
     * next block goes down on top of it. A longer pause was tried and did not help, so this is the
     * floor plus a little rather than a figure tuned against the leftovers.
     */
    private static final int PICKUP_GRACE_TICKS = 12;

    /** How long a drop may hold up the next placement before it is placed over anyway. */
    private static final int PLACE_BLOCKED_LIMIT = 40;

    private void placeOre(MinecraftClient client, ClientPlayerEntity player, BlockPos target) {
        // Never place on top of a drop. Putting a block into a square an item is sitting in makes
        // the game shove the item out of the solid space, and it does not shove it gently: measured
        // at two to four blocks away, one of them caught two blocks up in the air. Two thirds of a
        // run's yield ended up scattered on the floor out of reach that way.
        // The block's own box, and not a whisker wider. Widening it to catch drops that had slid
        // to the edge of the next square was tried and deadlocked the run: an item resting just
        // outside the square overlapped the test for ever, so nothing was ever placed again.
        boolean dropInTheWay = !client.world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                new net.minecraft.util.math.Box(target), item -> true).isEmpty();
        if (dropInTheWay && ++blockedTicks < PLACE_BLOCKED_LIMIT) {
            cooldown = 2;
            return;
        }
        // Past the limit, place anyway. Losing one drop to the shove is a far smaller fault than a
        // run that waits for ever on an item nothing is going to collect.
        blockedTicks = 0;
        BlockPos against = target.down();
        if (!client.world.getBlockState(against).isSolidBlock(client.world, against)) {
            spot = null;
            return;
        }
        lookAt(player, Vec3d.ofCenter(target));
        Vec3d hit = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        client.interactionManager.interactBlock(player, Hand.OFF_HAND,
                new BlockHitResult(hit, Direction.UP, against, false));
        player.swingHand(Hand.OFF_HAND);
        cooldown = ACTION_COOLDOWN_TICKS;
    }

    /**
     * Holds the attack button on the ore, which is how the game itself mines.
     *
     * <p>Driving {@code attackBlock} and {@code updateBlockBreakingProgress} by hand does not work,
     * and the way it fails is silent: once a tick the client cancels any break the attack key is not
     * holding, which sets the progress back to zero, so the break restarts forever and the block
     * never falls. Measured before this was changed — the progress read zero on every tick for a
     * minute and a half. Holding the button instead means the ordinary mining path runs, with the
     * tool, the enchantments and the haste all counted the way they normally are.
     *
     * <p>The button is only held while the crosshair is genuinely on our own ore. That guard is not
     * decoration: the attack key mines whatever is under the crosshair, and without it a glance at
     * the output chest while the key was down would break the chest.
     */
    private void breakSpot(MinecraftClient client, ClientPlayerEntity player, BlockPos target) {
        lookAt(player, Vec3d.ofCenter(target));
        boolean onTarget = client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target)
                && ore().isOreBlock(client.world.getBlockState(target).getBlock());
        setAttacking(client, onTarget);
        if (onTarget) {
            player.swingHand(Hand.MAIN_HAND);
        }
        if (devTrace) {
            BetterLoka.LOGGER.info("DIAG miner break at {} onTarget={} breaking={} progress={}",
                    target, onTarget, client.interactionManager.isBreakingBlock(),
                    client.interactionManager.getBlockBreakingProgress());
        }
    }

    /** Presses or releases the attack button, remembering so it is always let go again. */
    private void setAttacking(MinecraftClient client, boolean down) {
        if (attacking == down) {
            return;
        }
        attacking = down;
        client.options.attackKey.setPressed(down);
    }

    /**
     * A block of air within reach, standing on something solid, that is not where the player is.
     *
     * <p>One spot is found and then reused for the whole run, because that is what a person doing
     * this by hand does: they clear a square in front of them and work it. Hunting for a new square
     * every block would have the head swinging about for no gain.
     */
    private BlockPos findSpot(MinecraftClient client, ClientPlayerEntity player) {
        BlockPos feet = player.getBlockPos();

        // The four squares touching the player, at the height they stand at, come first and are not
        // merely "nearest": a dropped item is only picked up within about one and a third blocks of
        // the player, so a square straight in front is inside that and a diagonal one, at one and
        // four tenths, sits right on the edge. Working a diagonal leaves roughly one drop in nine
        // lying on the floor.
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos candidate = feet.offset(side);
            if (usableSpot(client.world, candidate) && inReach(player, candidate)) {
                return candidate;
            }
        }

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.add(dx, dy, dz);
                    if (candidate.equals(feet) || candidate.equals(feet.up())) {
                        continue;
                    }
                    if (!usableSpot(client.world, candidate) || !inReach(player, candidate)) {
                        continue;
                    }
                    double distance = Vec3d.ofCenter(candidate).distanceTo(player.getEyePos());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** Air with something solid under it, and not one of the three marked blocks. */
    private boolean usableSpot(ClientWorld world, BlockPos pos) {
        if (!world.getBlockState(pos).isAir() && !ore().isOreBlock(world.getBlockState(pos).getBlock())) {
            return false;
        }
        BlockPos below = pos.down();
        if (!world.getBlockState(below).isSolidBlock(world, below)) {
            return false;
        }
        for (MinerSites.Site site : new MinerSites.Site[] {
                sites.crafting(), sites.output(), sites.input()}) {
            if (site != null && site.pos().equals(pos)) {
                return false;
            }
        }
        return true;
    }

    // --- crafting ---

    /**
     * Fills the grid, waits for the bench to offer a result, then takes it.
     *
     * <p>The waiting is the part that is not obvious. A crafting table's result is worked out by the
     * server, not the client, so the result slot stays empty until an update comes back. Filling the
     * grid and shift-clicking the result in the same tick clicks an empty slot: nothing is made, the
     * grid is tidied away on the next pass, and the whole thing loops forever making nothing.
     * Measured doing exactly that before this was split up.
     */
    private void tickCrafting(MinecraftClient client, ClientPlayerEntity player) {
        MinerSites.Site site = sites.crafting();
        if (!openedOr(client, player, site, OreMiner::isBench)) {
            return;
        }
        AbstractCraftingScreenHandler handler = (AbstractCraftingScreenHandler) player.currentScreenHandler;

        if (devTrace) {
            StringBuilder grid = new StringBuilder();
            for (Slot input : handler.getInputSlots()) {
                grid.append(input.id).append('=').append(input.getStack().getCount()).append(' ');
            }
            BetterLoka.LOGGER.info("DIAG miner craft step={} waited={} drops={} out={} grid=[{}]",
                    craftStep, craftWaited, countIn(handler, player, ore()::isDrop),
                    handler.getOutputSlot().getStack().getCount(), grid.toString().trim());
        }
        switch (craftStep) {
            case TIDY -> {
                // Anything left in the grid from a previous round goes back first, or the count of
                // what there is to work with comes out wrong.
                for (Slot input : handler.getInputSlots()) {
                    if (input.hasStack()) {
                        click(client, player, handler.syncId, input.id, 0, SlotActionType.QUICK_MOVE);
                        cooldown = containerPace();
                        return;
                    }
                }
                craftStep = CraftStep.FILL;
            }
            case FILL -> {
                int drops = countIn(handler, player, ore()::isDrop);
                if (drops < OreKind.PER_BLOCK) {
                    finishCrafting(client, player);
                    return;
                }
                int source = findSlot(handler, player, stack -> ore().isDrop(stack));
                if (source < 0) {
                    finishCrafting(client, player);
                    return;
                }
                // Taken onto the cursor on its own tick: the spread that follows depends on what
                // the cursor is actually holding, and on a slow link that is not settled yet.
                dragSource = source;
                click(client, player, handler.syncId, source, 0, SlotActionType.PICKUP);
                craftStep = CraftStep.SPREAD;
                cooldown = containerPace();
            }
            case SPREAD -> {
                // Left-drag across the nine squares, which is how a hand spreads a stack evenly.
                // The three stages of a drag are one gesture and go together; it is the pauses on
                // either side of it that give the server room.
                click(client, player, handler.syncId, QUICK_CRAFT_MARKER_SLOT,
                        ScreenHandler.packQuickCraftData(0, 0), SlotActionType.QUICK_CRAFT);
                for (Slot input : handler.getInputSlots()) {
                    click(client, player, handler.syncId, input.id,
                            ScreenHandler.packQuickCraftData(1, 0), SlotActionType.QUICK_CRAFT);
                }
                click(client, player, handler.syncId, QUICK_CRAFT_MARKER_SLOT,
                        ScreenHandler.packQuickCraftData(2, 0), SlotActionType.QUICK_CRAFT);
                craftStep = CraftStep.RETURN;
                cooldown = containerPace();
            }
            case RETURN -> {
                // Whatever the drag could not spread evenly is still on the cursor; put it back.
                // Its own step, because leaving items on the cursor is how a desync turns into a
                // lost stack when the screen closes.
                if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
                    click(client, player, handler.syncId, dragSource, 0, SlotActionType.PICKUP);
                }
                craftStep = CraftStep.AWAIT;
                craftWaited = 0;
                cooldown = containerPace();
            }
            case AWAIT -> {
                if (handler.getOutputSlot().hasStack()) {
                    blocksBefore = countStorageBlocks(player);
                    click(client, player, handler.syncId, handler.getOutputSlot().id, 0,
                            SlotActionType.QUICK_MOVE);
                    craftStep = CraftStep.COUNT;
                    cooldown = containerPace();
                    return;
                }
                // Its own counter: openedOr clears the shared one on every tick it succeeds, so a
                // wait measured with that one never reaches its limit and the stage spins for ever.
                if (++craftWaited > SCREEN_TIMEOUT_TICKS) {
                    // The bench never offered anything. Rather than spin, put the grid back and
                    // carry on with what has already been made.
                    BetterLoka.LOGGER.debug("[betterloka] the bench offered no result; moving on");
                    craftStep = CraftStep.TIDY;
                    finishCrafting(client, player);
                }
            }
            case COUNT -> {
                // Counted rather than assumed: how many a shift-click makes depends on what the
                // drag managed to spread, and claiming a number the inventory does not show would
                // make the totals on screen fiction.
                blocksMade += Math.max(0, countStorageBlocks(player) - blocksBefore);
                minedSinceCraft = 0;
                craftStep = CraftStep.TIDY;
            }
        }
    }

    /** Leaves the bench and moves on to putting the blocks away. */
    private void finishCrafting(MinecraftClient client, ClientPlayerEntity player) {
        craftStep = CraftStep.TIDY;
        minedSinceCraft = 0;
        closeScreen(client, player);
        stage = Stage.STORING;
    }

    private int countStorageBlocks(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (ore().isStorageBlock(inventory.getStack(i))) {
                total += inventory.getStack(i).getCount();
            }
        }
        return total;
    }

    // --- putting the blocks away ---

    private void tickStoring(MinecraftClient client, ClientPlayerEntity player) {
        MinerSites.Site site = sites.output();
        if (!openedOr(client, player, site, OreMiner::isContainer)) {
            return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        int containerSlots = containerSlotCount(handler);
        int slot = findSlot(handler, player, stack -> ore().isStorageBlock(stack));
        if (slot >= 0 && hasRoom(handler, containerSlots)) {
            click(client, player, handler.syncId, slot, 0, SlotActionType.QUICK_MOVE);
            cooldown = containerPace();
            return;
        }
        closeScreen(client, player);
        stage = Stage.DRAWING;
    }

    // --- fetching more ---

    private void tickDrawing(MinecraftClient client, ClientPlayerEntity player) {
        MinerSites.Site site = sites.input();
        if (!openedOr(client, player, site, OreMiner::isContainer)) {
            return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        int containerSlots = containerSlotCount(handler);

        int held = countOre(player);
        int wanted = config.minerDrawLimit();
        if (held < wanted) {
            for (int slot = 0; slot < containerSlots; slot++) {
                if (ore().isOre(handler.getSlot(slot).getStack())) {
                    click(client, player, handler.syncId, slot, 0, SlotActionType.QUICK_MOVE);
                    cooldown = containerPace();
                    return;
                }
            }
        }
        closeScreen(client, player);

        if (countOre(player) == 0) {
            // Nothing left anywhere the run can reach, which is the end rather than a fault.
            stop("betterloka.miner.finished");
            return;
        }
        stage = Stage.MINING;
    }

    // --- the plumbing ---

    /**
     * Makes sure the right screen is open, opening it if not.
     *
     * @return whether the screen is open and ready to be worked on this tick
     */
    private boolean openedOr(MinecraftClient client, ClientPlayerEntity player, MinerSites.Site site,
                             java.util.function.Predicate<ScreenHandler> wanted) {
        if (site == null || !site.inWorld(worldKey(client))) {
            stop("betterloka.miner.error.unmarked");
            return false;
        }
        BlockPos pos = site.pos();
        if (!inReach(player, pos)) {
            stop("betterloka.miner.error.out_of_reach");
            return false;
        }
        if (client.currentScreen != null && wanted.test(player.currentScreenHandler)) {
            waited = 0;
            return true;
        }
        if (client.currentScreen != null) {
            // Some other screen is up — ours from the last stage, or the player's. Close and retry.
            closeScreen(client, player);
            return false;
        }
        if (waited == 0) {
            lookAt(player, Vec3d.ofCenter(pos));
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
            player.swingHand(Hand.MAIN_HAND);
        }
        if (++waited > SCREEN_TIMEOUT_TICKS) {
            stop("betterloka.miner.error.no_answer");
        }
        return false;
    }

    private void closeScreen(MinecraftClient client, ClientPlayerEntity player) {
        player.closeHandledScreen();
        client.setScreen(null);
        waited = 0;
        // Opening the next thing straight after closing this one is the same round trip problem.
        cooldown = containerPace();
    }

    /**
     * A real crafting table, and nothing that merely looks like one.
     *
     * <p>Testing for {@link AbstractCraftingScreenHandler} is the obvious thing and it is wrong:
     * the player's own inventory extends it for the two-by-two corner, and {@code
     * currentScreenHandler} is that handler whenever no screen is open. So the test passed before a
     * bench had been opened at all, and the run tipped its ore into the inventory corner and waited
     * for a three-by-three result that corner can never produce. Measured doing exactly that: a grid
     * of four slots with five raw iron in each, forever.
     */
    private static boolean isBench(ScreenHandler handler) {
        return handler instanceof CraftingScreenHandler bench
                && bench.getInputSlots().size() == OreKind.PER_BLOCK;
    }

    private static boolean isContainer(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler
                || handler instanceof ShulkerBoxScreenHandler;
    }

    private static int containerSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) {
            return generic.getRows() * 9;
        }
        if (handler instanceof ShulkerBoxScreenHandler) {
            return 27;
        }
        return 0;
    }

    /** Whether the container has anywhere left to put something. */
    private static boolean hasRoom(ScreenHandler handler, int containerSlots) {
        for (int slot = 0; slot < containerSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty() || stack.getCount() < stack.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    /** A slot in the player's own part of an open screen holding something that matches. */
    private static int findSlot(ScreenHandler handler, ClientPlayerEntity player,
                                java.util.function.Predicate<ItemStack> match) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == player.getInventory() && match.test(slot.getStack())) {
                return slot.id;
            }
        }
        return -1;
    }

    private static int countIn(ScreenHandler handler, ClientPlayerEntity player,
                               java.util.function.Predicate<ItemStack> match) {
        int total = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory == player.getInventory() && match.test(slot.getStack())) {
                total += slot.getStack().getCount();
            }
        }
        return total;
    }

    private int countOre(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (ore().isOre(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Puts a stack of the ore in the off-hand, so the pickaxe can stay in the main one.
     *
     * @return whether there was any to move
     */
    private boolean moveOreToOffHand(MinecraftClient client, ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (!ore().isOre(inventory.getStack(i))) {
                continue;
            }
            click(client, player, player.playerScreenHandler.syncId, playerSlotOf(i),
                    OFFHAND_SWAP_BUTTON, SlotActionType.SWAP);
            cooldown = ACTION_COOLDOWN_TICKS;
            return true;
        }
        return false;
    }

    /**
     * An inventory index as the player's own screen numbers it.
     *
     * <p>The two disagree, and not by a constant: the hotbar is the first nine of the inventory and
     * the last nine of the screen. Getting this wrong swaps armour into the off-hand.
     */
    static int playerSlotOf(int inventoryIndex) {
        return inventoryIndex < PlayerInventory.getHotbarSize()
                ? inventoryIndex + 36
                : inventoryIndex;
    }

    private static void click(MinecraftClient client, ClientPlayerEntity player, int syncId,
                              int slot, int button, SlotActionType action) {
        client.interactionManager.clickSlot(syncId, slot, button, action, player);
    }

    private boolean inReach(ClientPlayerEntity player, BlockPos pos) {
        double reach = player.getBlockInteractionRange();
        return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= reach * reach;
    }

    /**
     * Turns the head towards a block, as a person would before touching it.
     *
     * <p>Set on the player rather than sent alongside the packet on purpose: this way the turn is
     * the real one, everybody nearby sees where the player is looking, and what the server is told
     * matches what the world shows.
     */
    private static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dy, flat) * 180.0 / Math.PI));
        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, -90.0f, 90.0f));
    }

    public static String worldKey(MinecraftClient client) {
        return client.world == null ? "" : client.world.getRegistryKey().getValue().toString();
    }

    private void say(String key, Object... args) {
        lastMessage = Text.translatable(key, args);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(lastMessage, false);
        }
    }
}
