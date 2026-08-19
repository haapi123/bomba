package com.betterloka.data;

import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every battle BetterLoka knows about, plus a reverse index from player to the battles they fought
 * in. The Loka API has no "battles for player X" endpoint, so the only way to answer that question
 * is to hold the whole (small) battle history locally and look sideways through it.
 *
 * <p>All methods are synchronised: the sync service writes from several API threads while the GUI
 * reads.
 */
public final class BattleIndex {
    /** One player's line in one battle. */
    public record Entry(BattleZone battle, BattleParticipant participant) {
    }

    private static final Comparator<Entry> NEWEST_FIRST =
            Comparator.comparingLong((Entry entry) -> entry.battle().timeEnded()).reversed();

    private final Map<String, BattleZone> byId = new HashMap<>();
    private final Map<UUID, List<Entry>> byPlayer = new HashMap<>();

    /**
     * The value of {@code page.totalElements} the last time a sync completed. Comparing it against
     * a fresh page 0 tells us exactly how many battles appeared since, which is what lets an
     * incremental sync fetch a handful of pages instead of all of them.
     */
    private int syncedTotalElements;

    /** @return {@code true} if this battle was not already indexed. */
    public synchronized boolean add(BattleZone battle) {
        if (battle == null || battle.id() == null || byId.putIfAbsent(battle.id(), battle) != null) {
            return false;
        }
        for (BattleParticipant participant : battle.participants()) {
            byPlayer.computeIfAbsent(participant.uuid(), key -> new ArrayList<>(4))
                    .add(new Entry(battle, participant));
        }
        return true;
    }

    public synchronized boolean contains(String battleId) {
        return byId.containsKey(battleId);
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized int playerCount() {
        return byPlayer.size();
    }

    public synchronized Collection<BattleZone> battles() {
        return List.copyOf(byId.values());
    }

    /** @return the player's battles, newest first. Empty if they never fought. */
    public synchronized List<Entry> entriesFor(UUID uuid) {
        List<Entry> entries = byPlayer.get(uuid);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(NEWEST_FIRST);
        return copy;
    }

    public synchronized int syncedTotalElements() {
        return syncedTotalElements;
    }

    public synchronized void setSyncedTotalElements(int syncedTotalElements) {
        this.syncedTotalElements = syncedTotalElements;
    }
}
