package com.betterloka.stats;

import com.betterloka.api.LokaApi;
import com.betterloka.api.LokaApiException;
import com.betterloka.api.model.BattleParticipant;
import com.betterloka.api.model.BattleZone;
import com.betterloka.api.model.LokaPlayer;
import com.betterloka.api.model.LokaTown;
import com.betterloka.data.BattleIndex;
import com.betterloka.data.BattleSyncService;
import com.betterloka.data.TownCache;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Turns a player name into a {@link PlayerProfile} by joining the API's identity data to the local
 * battle index.
 *
 * <p>The lookup runs in two stages. Identity and town come back in a couple of requests and are
 * handed over straight away; the combat numbers need the whole battle history, which on a first run
 * takes about a minute to download, so they follow once the sync lands.
 */
public final class PlayerStatsService {
    /** How many recent fights the Player Finder lists. */
    public static final int RECENT_FIGHT_COUNT = 5;

    private final LokaApi api;
    private final BattleSyncService sync;
    private final TownCache towns;

    public PlayerStatsService(LokaApi api, BattleSyncService sync, TownCache towns) {
        this.api = api;
        this.sync = sync;
        this.towns = towns;
    }

    public TownCache towns() {
        return towns;
    }

    /**
     * Runs the lookup off the render thread.
     *
     * @param onIdentity called with an identity-only profile as soon as the player resolves, so the
     *                   GUI has something to show while the battle history syncs. Never called if
     *                   the player does not exist.
     * @return the completed profile; fails with {@link LokaApiException} if the player cannot be
     * resolved at all.
     */
    public CompletableFuture<PlayerProfile> lookup(String name, Consumer<PlayerProfile> onIdentity) {
        CompletableFuture<PlayerProfile> identity = CompletableFuture.supplyAsync(() -> {
            try {
                return buildIdentity(name);
            } catch (LokaApiException e) {
                throw new CompletionException(e);
            }
        }, api.executor());

        identity.thenAccept(onIdentity);

        // A sync failure is not a lookup failure: the identity half is still worth showing, so the
        // index is allowed to arrive as null here rather than sinking the whole future.
        CompletableFuture<BattleIndex> synced = sync.ensureSynced().handle((index, error) -> index);

        return identity.thenCombineAsync(synced, this::withCombatRecord, api.executor());
    }

    private PlayerProfile buildIdentity(String name) throws LokaApiException {
        LokaPlayer player = api.findPlayerByName(name);
        towns.ensureLoaded();
        return PlayerProfile.identityOnly(player, api.findTownByMember(player.identityId()));
    }

    private PlayerProfile withCombatRecord(PlayerProfile identity, BattleIndex index) {
        if (index == null) {
            return identity.withStatsState(PlayerProfile.StatsState.UNAVAILABLE);
        }

        UUID uuid = identity.player().uuid();
        List<BattleIndex.Entry> entries = uuid == null ? List.of() : index.entriesFor(uuid);

        int kills = 0;
        int deaths = 0;
        int battlesFought = 0;
        for (BattleIndex.Entry entry : entries) {
            kills += entry.participant().kills();
            deaths += entry.participant().deaths();
            if (entry.participant().participated()) {
                battlesFought++;
            }
        }

        // Battles still in progress are not in the index — they would be cached half-finished — so
        // they are folded in here, newest first, straight from the live endpoint.
        List<FightSummary> recent = new ArrayList<>();
        for (BattleZone battle : sync.activeBattles()) {
            BattleParticipant participant = participantIn(battle, uuid);
            if (participant == null) {
                continue;
            }
            kills += participant.kills();
            deaths += participant.deaths();
            if (participant.participated()) {
                battlesFought++;
            }
            recent.add(summarise(battle, participant, true));
        }

        String fightingFor = recent.isEmpty() ? null : recent.get(0).foughtForTown();

        for (BattleIndex.Entry entry : entries) {
            if (recent.size() >= RECENT_FIGHT_COUNT) {
                break;
            }
            if (!entry.participant().participated()) {
                continue;
            }
            if (fightingFor == null) {
                fightingFor = towns.nameOf(entry.participant().townId());
            }
            recent.add(summarise(entry.battle(), entry.participant(), false));
        }

        return new PlayerProfile(identity.player(), identity.town(), fightingFor, identity.firstSeen(),
                kills, deaths, battlesFought, List.copyOf(recent), PlayerProfile.StatsState.READY);
    }

    private static BattleParticipant participantIn(BattleZone battle, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (BattleParticipant participant : battle.participants()) {
            if (uuid.equals(participant.uuid())) {
                return participant;
            }
        }
        return null;
    }

    private FightSummary summarise(BattleZone battle, BattleParticipant participant, boolean live) {
        return new FightSummary(
                battle.territory(),
                battle.timeEnded(),
                townName(battle.attackerTownId(), battle.attackerName()),
                townName(battle.defenderTownId(), battle.defenderName()),
                battle.attackerCount(),
                battle.defenderCount(),
                participant.attacker(),
                towns.nameOf(participant.townId()),
                participant.kills(),
                participant.deaths(),
                live);
    }

    /** Older battles carry the town name inline; newer ones only carry the ID. */
    private String townName(String townId, String inlineName) {
        String resolved = towns.nameOf(townId);
        return resolved != null ? resolved : inlineName;
    }
}
