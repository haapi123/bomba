package com.betterloka.miner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three marked blocks: that they survive a restart, and that a mark made in one world is never
 * used in another.
 *
 * <p>The world check is the part worth pinning. The same coordinates exist on every continent, so a
 * chest marked on Balak names whatever happens to stand at that spot on Kalros — and the miner
 * would open it and start moving somebody else's things about. Failing closed is the only safe way
 * for that to behave.
 */
class MinerSitesTest {
    private static final String BALAK = "minecraft:bigboi";
    private static final String KALROS = "minecraft:north";

    @Test
    void nothingIsMarkedToBeginWith(@TempDir Path dir) {
        MinerSites sites = new MinerSites(dir.resolve("miner.json"));
        assertNull(sites.crafting());
        assertNull(sites.output());
        assertNull(sites.input());
        assertFalse(sites.readyIn(BALAK));
    }

    @Test
    void eachMarkIsKeptSeparatelyAndSurvivesARestart(@TempDir Path dir) {
        Path file = dir.resolve("miner.json");
        MinerSites sites = new MinerSites(file);
        sites.setCrafting(BALAK, new net.minecraft.util.math.BlockPos(10, 64, 20));
        sites.setOutput(BALAK, new net.minecraft.util.math.BlockPos(11, 64, 20));
        sites.setInput(BALAK, new net.minecraft.util.math.BlockPos(12, 64, 20));

        MinerSites reopened = new MinerSites(file);
        assertEquals(10, reopened.crafting().x());
        assertEquals(11, reopened.output().x());
        assertEquals(12, reopened.input().x());
        assertEquals(64, reopened.crafting().y());
        assertEquals(20, reopened.input().z());
        assertTrue(reopened.readyIn(BALAK));
    }

    @Test
    void marksMadeInAnotherWorldAreNotUsedInThisOne(@TempDir Path dir) {
        MinerSites sites = new MinerSites(dir.resolve("miner.json"));
        sites.setCrafting(BALAK, new net.minecraft.util.math.BlockPos(10, 64, 20));
        sites.setOutput(BALAK, new net.minecraft.util.math.BlockPos(11, 64, 20));
        sites.setInput(BALAK, new net.minecraft.util.math.BlockPos(12, 64, 20));

        assertTrue(sites.readyIn(BALAK));
        assertFalse(sites.readyIn(KALROS), "a mark from another continent must not be used here");
        assertFalse(MinerSites.usable(sites.output(), KALROS));
        assertTrue(MinerSites.usable(sites.output(), BALAK));
    }

    @Test
    void oneMarkMissingIsEnoughToNotBeReady(@TempDir Path dir) {
        MinerSites sites = new MinerSites(dir.resolve("miner.json"));
        sites.setCrafting(BALAK, new net.minecraft.util.math.BlockPos(10, 64, 20));
        sites.setOutput(BALAK, new net.minecraft.util.math.BlockPos(11, 64, 20));
        assertFalse(sites.readyIn(BALAK), "the input chest is still unmarked");
        sites.setInput(BALAK, new net.minecraft.util.math.BlockPos(12, 64, 20));
        assertTrue(sites.readyIn(BALAK));
    }

    /** Re-marking replaces rather than adding a second one. */
    @Test
    void markingAgainMovesTheMark(@TempDir Path dir) {
        MinerSites sites = new MinerSites(dir.resolve("miner.json"));
        sites.setInput(BALAK, new net.minecraft.util.math.BlockPos(1, 2, 3));
        sites.setInput(BALAK, new net.minecraft.util.math.BlockPos(4, 5, 6));
        assertEquals(4, sites.input().x());
        assertEquals(5, sites.input().y());
        assertEquals(6, sites.input().z());
    }

    @Test
    void nullIsNeverUsable() {
        assertFalse(MinerSites.usable(null, BALAK));
    }
}
