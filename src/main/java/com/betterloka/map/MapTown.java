package com.betterloka.map;

/**
 * A town as Loka's map describes it, from the card its marker shows on hover.
 *
 * <p>The map publishes numbers the game API does not put on a town record — its strength as the map
 * counts it, and how many territories it currently holds — so this is worth keeping even though the
 * API is the better source for everything else.
 *
 * @param strength the map's strength figure, or {@code -1} when the card did not give one
 */
public record MapTown(String name, String alliance, double strength, int members, int territories) {

    public boolean hasAlliance() {
        return alliance != null && !alliance.isBlank();
    }
}
