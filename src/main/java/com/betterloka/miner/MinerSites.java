package com.betterloka.miner;

import com.betterloka.BetterLoka;
import com.betterloka.data.JsonStore;
import com.google.gson.reflect.TypeToken;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Type;
import java.nio.file.Path;

/**
 * The three blocks the player marks with a command: where to craft, where to put the blocks made,
 * and where to take more ore from.
 *
 * <p>Kept on disk with the world they were marked in. A position alone would be worse than nothing:
 * the same coordinates exist in every world, so a chest marked on one continent would silently name
 * whatever happens to stand there on the next, and the miner would start moving somebody's things
 * about. Marks made elsewhere are shown as not set rather than used.
 */
public final class MinerSites {
    /** One marked block: where it is, and which world it was marked in. */
    public record Site(String world, int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        public static Site of(String world, BlockPos pos) {
            return new Site(world, pos.getX(), pos.getY(), pos.getZ());
        }

        public boolean inWorld(String current) {
            return world != null && world.equals(current);
        }
    }

    /** What is written out. Null fields mean nothing has been marked yet. */
    public record Saved(Site crafting, Site output, Site input) {
        static final Saved EMPTY = new Saved(null, null, null);
    }

    private static final Type STORE_TYPE = JsonStore.envelopeOf(Saved.class);

    /** Marks are only cleared by being replaced, so they never expire. */
    private static final long FOREVER = Long.MAX_VALUE;

    private final JsonStore<Saved> disk;
    private Saved saved = Saved.EMPTY;

    public MinerSites(Path file) {
        this.disk = new JsonStore<>(file, STORE_TYPE, FOREVER);
        restore();
    }

    private void restore() {
        try {
            Saved read = disk.read();
            if (read != null) {
                saved = read;
            }
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not read the miner's marks", e);
        }
    }

    private void write() {
        try {
            disk.write(saved);
        } catch (RuntimeException e) {
            BetterLoka.LOGGER.debug("Could not write the miner's marks", e);
        }
    }

    public Site crafting() {
        return saved.crafting();
    }

    public Site output() {
        return saved.output();
    }

    public Site input() {
        return saved.input();
    }

    public void setCrafting(String world, BlockPos pos) {
        saved = new Saved(Site.of(world, pos), saved.output(), saved.input());
        write();
    }

    public void setOutput(String world, BlockPos pos) {
        saved = new Saved(saved.crafting(), Site.of(world, pos), saved.input());
        write();
    }

    public void setInput(String world, BlockPos pos) {
        saved = new Saved(saved.crafting(), saved.output(), Site.of(world, pos));
        write();
    }

    /** Whether all three are marked, and marked in the world the player is standing in. */
    public boolean readyIn(String world) {
        return usable(saved.crafting(), world) && usable(saved.output(), world)
                && usable(saved.input(), world);
    }

    public static boolean usable(Site site, String world) {
        return site != null && site.inWorld(world);
    }
}
