package com.betterloka.miner;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * The ores the miner knows how to work, and what each turns into.
 *
 * <p>Both the stone and the deepslate form of every ore count as the same kind. They drop the same
 * thing and a player's stock is usually a mix of the two, so treating them separately would stop the
 * run halfway through a chest for no reason a player would recognise.
 */
public enum OreKind {
    GOLD("gold", List.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE), Items.RAW_GOLD, Items.RAW_GOLD_BLOCK),
    DIAMOND("diamond", List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), Items.DIAMOND, Items.DIAMOND_BLOCK),
    EMERALD("emerald", List.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE), Items.EMERALD, Items.EMERALD_BLOCK),
    LAPIS("lapis", List.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE), Items.LAPIS_LAZULI, Items.LAPIS_BLOCK),
    IRON("iron", List.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), Items.RAW_IRON, Items.RAW_IRON_BLOCK);

    /** How many drops go into one block. Every one of these is a three by three. */
    public static final int PER_BLOCK = 9;

    private final String key;
    private final List<Block> blocks;
    private final Item drop;
    private final Item storageBlock;

    OreKind(String key, List<Block> blocks, Item drop, Item storageBlock) {
        this.key = key;
        this.blocks = blocks;
        this.drop = drop;
        this.storageBlock = storageBlock;
    }

    public String translationKey() {
        return "betterloka.miner.ore." + key;
    }

    /** The ore blocks of this kind, stone and deepslate alike. */
    public List<Block> blocks() {
        return blocks;
    }

    /** What mining one of these yields — raw metal, or the gem itself. */
    public Item drop() {
        return drop;
    }

    /** What nine drops craft into. */
    public Item storageBlock() {
        return storageBlock;
    }

    /** Whether this stack is an ore of this kind, in either of its two forms. */
    public boolean isOre(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Block block : blocks) {
            if (stack.isOf(block.asItem())) {
                return true;
            }
        }
        return false;
    }

    public boolean isDrop(ItemStack stack) {
        return !stack.isEmpty() && stack.isOf(drop);
    }

    public boolean isStorageBlock(ItemStack stack) {
        return !stack.isEmpty() && stack.isOf(storageBlock);
    }

    /** Whether a placed block is an ore of this kind, for knowing when a break has landed. */
    public boolean isOreBlock(Block block) {
        return blocks.contains(block);
    }

    public OreKind next() {
        OreKind[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public static OreKind byName(String name) {
        for (OreKind kind : values()) {
            if (kind.key.equalsIgnoreCase(name) || kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return null;
    }

    public String key() {
        return key;
    }

    @Override
    public String toString() {
        return key.toUpperCase(Locale.ROOT);
    }
}
