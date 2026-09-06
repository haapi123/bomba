package com.betterloka.map;

/**
 * A town as Loka's map describes it, from the card its marker shows on hover.
 *
 * <p>The map publishes numbers the game API does not put on a town record — its strength as the map
 * counts it, and how many territories it currently holds — so this is worth keeping even though the
 * API is the better source for everything else.
 *
 * @param strength the map's strength figure, or {@code -1} when the card did not give one
 * @param x        where the town's marker stands, world X: the territory it falls inside is that
 *                 town's seat, which the map has no other way of saying
 * @param z        the same marker's world Z
 */
public record MapTown(String name, String alliance, double strength, int members, int territories,
                      double x, double z) {

    public boolean hasAlliance() {
        return alliance != null && !alliance.isBlank();
    }
}
