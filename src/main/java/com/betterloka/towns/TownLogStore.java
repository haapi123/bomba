package com.betterloka.towns;

import com.betterloka.BetterLoka;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the territory log on disk, so what fell is still there next session and a restart does not
 * start from a blank baseline.
 *
 * <p>The deleted-town names are kept too. They are the expensive half of the sweep — forty requests —
 * and they never change once written, since a town cannot be un-deleted.
 */
public final class TownLogStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** How many events to keep before the oldest are dropped. */
    private static final int MAX_EVENTS = 400;

    /** The saved shape. Plain fields so Gson handles it without adapters. */
    private static final class Saved {
        List<TownLogEvent> events = new ArrayList<>();
        Map<String, Territory> territories = new LinkedHashMap<>();
        Map<String, DeadTown> deletedTowns = new LinkedHashMap<>();
        long deletedTownsLoadedAt;
        Set<String> reported = new HashSet<>();
        Map<String, Partnership> partners = new LinkedHashMap<>();
        /** The towns on the map at the last poll, so the next one can see which have gone. */
        Map<String, DeadTown> livingTowns = new LinkedHashMap<>();
    }

    /**
     * How long two towns have been allied, counted in days they were seen sharing an alliance.
     *
     * <p>Loka's API publishes who is allied right now and keeps no history, so the only way to answer
     * "who do they usually ally with" is to watch: this accumulates from the day the mod is first
     * run. Counted per day rather than per sweep so a long session does not outweigh a long alliance.
     */
    private static final class Partnership {
        String townId;
        String partnerId;
        String partnerName;
        int days;
        long firstSeen;
        long lastSeen;
        long lastCountedDay;
    }

    /** Only the name is worth keeping: it is the one thing a deleted town cannot be asked for. */
    private record DeadTown(String id, String name, String world) {
    }

    private final Path file;
    private Saved saved = new Saved();

    public TownLogStore(Path file) {
        this.file = file;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Saved loaded = GSON.fromJson(reader, new TypeToken<Saved>() {
            }.getType());
            if (loaded != null) {
                saved = loaded;
                if (saved.events == null) {
                    saved.events = new ArrayList<>();
                }
                if (saved.territories == null) {
                    saved.territories = new LinkedHashMap<>();
                }
                if (saved.deletedTowns == null) {
                    saved.deletedTowns = new LinkedHashMap<>();
                }
                if (saved.reported == null) {
                    saved.reported = new HashSet<>();
                }
                if (saved.partners == null) {
                    saved.partners = new LinkedHashMap<>();
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            BetterLoka.LOGGER.warn("Could not read the saved town log, starting fresh", e);
            saved = new Saved();
        }
    }

    /**
     * Folds one look at the alliances in, crediting each pair of allied towns with a day together.
     *
     * @param names town id to current name, for the partners this run saw
     */
    public synchronized void recordAlliances(List<LokaAlliance> alliances, Map<String, String> names) {
        long today = System.currentTimeMillis() / 86_400_000L;
        long now = System.currentTimeMillis();
        for (LokaAlliance alliance : alliances) {
            List<String> towns = alliance.townIds();
            for (String town : towns) {
                for (String partner : towns) {
                    if (!town.equals(partner)) {
                        credit(town, partner, names.get(partner), today, now);
                    }
                }
            }
        }
        save();
    }

    private void credit(String townId, String partnerId, String partnerName, long today, long now) {
        Partnership held = saved.partners.computeIfAbsent(townId + "|" + partnerId, key -> {
            Partnership fresh = new Partnership();
            fresh.townId = townId;
            fresh.partnerId = partnerId;
            fresh.firstSeen = now;
            return fresh;
        });
        if (partnerName != null) {
            held.partnerName = partnerName;
        }
        held.lastSeen = now;
        if (held.lastCountedDay != today) {
            held.lastCountedDay = today;
            held.days++;
        }
    }

    /** Who this town has been allied with, longest first. */
    public synchronized List<TownPartner> partnersOf(String townId) {
        List<TownPartner> partners = new ArrayList<>();
        if (townId == null) {
            return partners;
        }
        for (Partnership held : saved.partners.values()) {
            if (townId.equals(held.townId)) {
                partners.add(new TownPartner(held.partnerId, held.partnerName, held.days,
                        held.firstSeen, held.lastSeen));
            }
        }
        partners.sort(Comparator.comparingInt(TownPartner::days).reversed()
                .thenComparing(TownPartner::lastSeen, Comparator.reverseOrder()));
        return List.copyOf(partners);
    }

    /** A town this one has been allied with, and for how many days it has been seen. */
    public record TownPartner(String townId, String name, int days, long firstSeen, long lastSeen) {
    }

    /**
     * Folds one sweep in.
     *
     * <p>A fallen town is a standing fact about a territory rather than a moment, so the same one
     * comes back on every sweep until Loka clears the claim; those are recorded once. Handovers are
     * genuine changes and are always appended — the same territory really can change hands twice.
     */
    public synchronized void record(Map<String, Territory> territories, List<TownLogEvent> events,
                                    Map<String, LokaTown> deletedTowns, long deletedTownsLoadedAt) {
        for (TownLogEvent event : events) {
            if (event.kind().isTownGone() && !saved.reported.add(event.identity())) {
                continue;
            }
            saved.events.add(event);
        }
        while (saved.events.size() > MAX_EVENTS) {
            saved.events.remove(0);
        }

        saved.territories = new LinkedHashMap<>(territories);
        if (deletedTowns != null && !deletedTowns.isEmpty()) {
            Map<String, DeadTown> dead = new LinkedHashMap<>();
            for (LokaTown town : deletedTowns.values()) {
                dead.put(town.id(), new DeadTown(town.id(), town.name(), town.world()));
            }
            saved.deletedTowns = dead;
            saved.deletedTownsLoadedAt = deletedTownsLoadedAt;
        }
        save();
    }

    public synchronized List<TownLogEvent> events() {
        return List.copyOf(saved.events);
    }

    public synchronized Map<String, Territory> territories() {
        return Map.copyOf(saved.territories);
    }

    /**
     * The towns that were on the map at the last poll.
     *
     * <p>Kept between sessions so a town that falls while the game is shut is still noticed: the
     * first poll of the next session compares against the roster from the last one.
     */
    public synchronized Map<String, LokaTown> livingTowns() {
        Map<String, LokaTown> towns = new LinkedHashMap<>();
        for (DeadTown town : saved.livingTowns.values()) {
            towns.put(town.id(), LokaTown.deleted(town.id(), town.name(), town.world()));
        }
        return Map.copyOf(towns);
    }

    /** Remembers who was on the map, for the next poll to diff against. */
    public synchronized void recordLivingTowns(Map<String, LokaTown> living) {
        if (living == null || living.isEmpty()) {
            return;
        }
        Map<String, DeadTown> roster = new LinkedHashMap<>();
        for (LokaTown town : living.values()) {
            roster.put(town.id(), new DeadTown(town.id(), town.name(), town.world()));
        }
        saved.livingTowns = roster;
        save();
    }

    /** The deleted towns as the logger wants them: id to town. */
    public synchronized Map<String, LokaTown> deletedTowns() {
        Map<String, LokaTown> towns = new LinkedHashMap<>();
        for (DeadTown dead : saved.deletedTowns.values()) {
            towns.put(dead.id(), LokaTown.deleted(dead.id(), dead.name(), dead.world()));
        }
        return Map.copyOf(towns);
    }

    public synchronized long deletedTownsLoadedAt() {
        return saved.deletedTownsLoadedAt;
    }

    public synchronized void clearEvents() {
        saved.events.clear();
        saved.reported.clear();
        save();
    }

    /** Writes through a temporary file so an interrupted save cannot truncate the log. */
    private void save() {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BetterLoka.LOGGER.warn("Could not save the town log", e);
        }
    }
}
