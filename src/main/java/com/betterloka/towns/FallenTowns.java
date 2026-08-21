package com.betterloka.towns;

import com.betterloka.api.model.LokaTown;
import com.betterloka.api.model.Territory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding the towns Loka has deleted, from the territories they are still recorded against.
 *
 * <p>Deleting a town does not clear its claims: the territories keep pointing at an id that no longer
 * resolves. That dangling id is the whole signal, and it is a standing fact rather than an event — so
 * a fallen town is visible in one sweep, without having had to be watching when it happened.
 *
 * <p>Shared by the in-game Town Logger and the Discord bot so the two cannot drift: they are the same
 * question asked from two places, and an answer that differed between them would be a bug in one.
 */
public final class FallenTowns {

    /** One dead town and everything it is still recorded as holding. */
    public record Fallen(LokaTown town, List<Territory> territories) {

        /** Territory numbers, in order, for a one-line summary. */
        public String numbers() {
            List<String> nums = new ArrayList<>();
            for (Territory territory : territories) {
                nums.add("#" + territory.num());
            }
            return String.join(", ", nums);
        }
    }

    private FallenTowns() {
    }

    /**
     * @param territories every territory swept
     * @param deleted     town id to town, for the towns Loka has deleted
     * @return one entry per fallen town that still holds ground, with its territories, ordered by
     * continent and then territory number so the output is stable between sweeps
     */
    public static List<Fallen> detect(Collection<Territory> territories, Map<String, LokaTown> deleted) {
        Map<String, List<Territory>> byTown = new LinkedHashMap<>();
        for (Territory territory : territories) {
            if (territory.isOwned() && deleted.containsKey(territory.townId())) {
                byTown.computeIfAbsent(territory.townId(), key -> new ArrayList<>()).add(territory);
            }
        }

        List<Fallen> fallen = new ArrayList<>();
        for (Map.Entry<String, List<Territory>> entry : byTown.entrySet()) {
            List<Territory> held = new ArrayList<>(entry.getValue());
            held.sort(Comparator.comparing(Territory::world).thenComparingInt(FallenTowns::numberOf));
            fallen.add(new Fallen(deleted.get(entry.getKey()), List.copyOf(held)));
        }
        fallen.sort(Comparator.comparing((Fallen f) -> f.town().name() == null ? "" : f.town().name()));
        return List.copyOf(fallen);
    }

    static int numberOf(Territory territory) {
        try {
            return Integer.parseInt(territory.num());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
