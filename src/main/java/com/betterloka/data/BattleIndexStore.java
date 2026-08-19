package com.betterloka.data;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists the battle history so that a player pays the ~400 request first sync once instead of
 * every launch. The format is a gzipped binary dump with a shared string pool — the raw JSON is
 * around 65 MB, this lands near 2 MB and parses in a fraction of the time.
 */
public final class BattleIndexStore {
    private static final int MAGIC = 0x424C4B49; // "BLKI"
    private static final int FORMAT_VERSION = 1;

    private static final byte FLAG_ATTACKER = 1;
    private static final byte FLAG_PARTICIPATED = 2;

    private final Path file;

    public BattleIndexStore(Path file) {
        this.file = file;
    }

    /** @return the stored index, or an empty one if there is nothing readable on disk. */
    public BattleIndex load() {
        BattleIndex index = new BattleIndex();
        if (!Files.isRegularFile(file)) {
            return index;
        }
        try (InputStream in = Files.newInputStream(file);
             DataInputStream data = new DataInputStream(new GZIPInputStream(new BufferedInputStream(in), 1 << 16))) {

            if (data.readInt() != MAGIC || data.readInt() != FORMAT_VERSION) {
                BetterLoka.LOGGER.info("Battle cache is from a different format version, resyncing from scratch");
                return new BattleIndex();
            }
            index.setSyncedTotalElements(data.readInt());

            String[] pool = new String[data.readInt() + 1];
            for (int i = 1; i < pool.length; i++) {
                pool[i] = data.readUTF();
            }

            int battleCount = data.readInt();
            for (int i = 0; i < battleCount; i++) {
                index.add(readBattle(data, pool));
            }
            BetterLoka.LOGGER.info("Loaded {} cached battles from {}", index.size(), file.getFileName());
            return index;
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.warn("Battle cache is unreadable, resyncing from scratch", e);
            return new BattleIndex();
        }
    }

    private static BattleZone readBattle(DataInputStream data, String[] pool) throws IOException {
        String id = data.readUTF();
        String territory = pool[data.readInt()];
        long timeStarted = data.readLong();
        long timeEnded = data.readLong();
        String attackerTownId = pool[data.readInt()];
        String defenderTownId = pool[data.readInt()];
        String attackerName = pool[data.readInt()];
        String defenderName = pool[data.readInt()];
        int attackerCount = data.readInt();
        int defenderCount = data.readInt();

        int participantCount = data.readInt();
        List<BattleParticipant> participants = new ArrayList<>(participantCount);
        for (int i = 0; i < participantCount; i++) {
            UUID uuid = new UUID(data.readLong(), data.readLong());
            int kills = data.readInt();
            int deaths = data.readInt();
            byte flags = data.readByte();
            String townId = pool[data.readInt()];
            participants.add(new BattleParticipant(uuid, kills, deaths,
                    (flags & FLAG_ATTACKER) != 0, (flags & FLAG_PARTICIPATED) != 0, townId));
        }

        return new BattleZone(id, territory, timeStarted, timeEnded, false, attackerTownId, defenderTownId,
                attackerName, defenderName, List.copyOf(participants), attackerCount, defenderCount);
    }

    /** Writes to a temporary file and moves it into place so a crash mid-write cannot corrupt the cache. */
    public void save(BattleIndex index) {
        Collection<BattleZone> battles = index.battles();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());

            Map<String, Integer> pool = new HashMap<>();
            List<String> poolOrder = new ArrayList<>();
            for (BattleZone battle : battles) {
                intern(pool, poolOrder, battle.territory());
                intern(pool, poolOrder, battle.attackerTownId());
                intern(pool, poolOrder, battle.defenderTownId());
                intern(pool, poolOrder, battle.attackerName());
                intern(pool, poolOrder, battle.defenderName());
                for (BattleParticipant participant : battle.participants()) {
                    intern(pool, poolOrder, participant.townId());
                }
            }

            try (OutputStream out = Files.newOutputStream(temp);
                 DataOutputStream data = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(out), 1 << 16))) {

                data.writeInt(MAGIC);
                data.writeInt(FORMAT_VERSION);
                data.writeInt(index.syncedTotalElements());

                data.writeInt(poolOrder.size());
                for (String value : poolOrder) {
                    data.writeUTF(value);
                }

                data.writeInt(battles.size());
                for (BattleZone battle : battles) {
                    writeBattle(data, pool, battle);
                }
            }

            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            BetterLoka.LOGGER.info("Saved {} battles to {}", battles.size(), file.getFileName());
        } catch (IOException | RuntimeException e) {
            BetterLoka.LOGGER.warn("Could not save the battle cache", e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing useful to do; the stale temp file is harmless.
            }
        }
    }

    private static void writeBattle(DataOutputStream data, Map<String, Integer> pool, BattleZone battle) throws IOException {
        data.writeUTF(battle.id());
        data.writeInt(ref(pool, battle.territory()));
        data.writeLong(battle.timeStarted());
        data.writeLong(battle.timeEnded());
        data.writeInt(ref(pool, battle.attackerTownId()));
        data.writeInt(ref(pool, battle.defenderTownId()));
        data.writeInt(ref(pool, battle.attackerName()));
        data.writeInt(ref(pool, battle.defenderName()));
        data.writeInt(battle.attackerCount());
        data.writeInt(battle.defenderCount());

        data.writeInt(battle.participants().size());
        for (BattleParticipant participant : battle.participants()) {
            data.writeLong(participant.uuid().getMostSignificantBits());
            data.writeLong(participant.uuid().getLeastSignificantBits());
            data.writeInt(participant.kills());
            data.writeInt(participant.deaths());
            byte flags = 0;
            if (participant.attacker()) {
                flags |= FLAG_ATTACKER;
            }
            if (participant.participated()) {
                flags |= FLAG_PARTICIPATED;
            }
            data.writeByte(flags);
            data.writeInt(ref(pool, participant.townId()));
        }
    }

    private static void intern(Map<String, Integer> pool, List<String> order, String value) {
        if (value != null && !pool.containsKey(value)) {
            order.add(value);
            pool.put(value, order.size());
        }
    }

    /** Pool references are 1-based so that 0 can mean null. */
    private static int ref(Map<String, Integer> pool, String value) {
        return value == null ? 0 : pool.get(value);
    }
}
