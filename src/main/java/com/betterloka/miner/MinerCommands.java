package com.betterloka.miner;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * The five commands that drive the miner.
 *
 * <p>Client-side commands: they are handled in the game and never reach the server, so the server's
 * own {@code /lokabetter} — if it ever grows one — is not shadowed and nothing is typed into chat.
 *
 * <p>{@code /betterloka} answers to the same things, because the mod is called one way round and the
 * command the other, and getting it wrong once is enough to think the mod is not installed.
 */
public final class MinerCommands {
    private final OreMiner miner;
    private final MinerSites sites;

    public MinerCommands(OreMiner miner, MinerSites sites) {
        this.miner = miner;
        this.sites = sites;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            build(dispatcher, "lokabetter");
            build(dispatcher, "betterloka");
        });
    }

    private void build(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommandManager.literal(name)
                .then(ClientCommandManager.literal("start").executes(context -> {
                    String problem = miner.start();
                    if (problem != null) {
                        error(context.getSource(), problem);
                    }
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(context -> {
                    if (!miner.running()) {
                        error(context.getSource(), "betterloka.miner.error.not_running");
                        return 1;
                    }
                    miner.stop(null);
                    return 1;
                }))
                .then(ClientCommandManager.literal("crafting").executes(context ->
                        mark(context.getSource(), Mark.CRAFTING)))
                .then(ClientCommandManager.literal("output").executes(context ->
                        mark(context.getSource(), Mark.OUTPUT)))
                .then(ClientCommandManager.literal("input").executes(context ->
                        mark(context.getSource(), Mark.INPUT)))
                .executes(context -> {
                    context.getSource().sendFeedback(
                            Text.translatable("betterloka.miner.command.help")
                                    .formatted(Formatting.GRAY));
                    return 1;
                }));
    }

    private enum Mark {
        CRAFTING("betterloka.miner.marked.crafting"),
        OUTPUT("betterloka.miner.marked.output"),
        INPUT("betterloka.miner.marked.input");

        final String key;

        Mark(String key) {
            this.key = key;
        }
    }

    /**
     * Records the block the player is looking at.
     *
     * <p>Deliberately the block under the crosshair rather than the one the player is standing on or
     * near: a chest and the bench beside it are one block apart, and anything cleverer than "what
     * you are pointing at" would pick the wrong one half the time.
     */
    private int mark(FabricClientCommandSource source, Mark mark) {
        MinecraftClient client = source.getClient();
        ClientPlayerEntity player = source.getPlayer();
        if (player == null || client.world == null) {
            error(source, "betterloka.miner.error.no_world");
            return 1;
        }
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            error(source, "betterloka.miner.error.look_at_block");
            return 1;
        }
        BlockPos pos = block.getBlockPos();
        String world = OreMiner.worldKey(client);
        switch (mark) {
            case CRAFTING -> sites.setCrafting(world, pos);
            case OUTPUT -> sites.setOutput(world, pos);
            case INPUT -> sites.setInput(world, pos);
        }
        source.sendFeedback(Text.translatable(mark.key,
                        client.world.getBlockState(pos).getBlock().getName(),
                        pos.getX(), pos.getY(), pos.getZ())
                .formatted(Formatting.GREEN));
        return 1;
    }

    private static void error(FabricClientCommandSource source, String key) {
        source.sendError(Text.translatable(key));
    }
}
