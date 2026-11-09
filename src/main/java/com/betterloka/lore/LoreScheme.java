package com.betterloka.lore;

/**
 * A colour scheme for a lore: the title's gradient, the colour of the text, and an accent for the
 * small touches. Every colour is chosen to read on the tooltip's dark violet ground.
 */
public enum LoreScheme {
    FIRE("fire", 0xFF4500, 0xFFD700, 0xFFB066, 0xFF7A1A, LoreMotif.FIRE),
    ICE("ice", 0x5FD8FF, 0xFFFFFF, 0xBFEFFF, 0x7FE8FF, LoreMotif.ICE),
    SHADOW("shadow", 0x9B59FF, 0x5A5A7A, 0xB4A8D8, 0x8A6BD8, LoreMotif.SHADOW),
    BLOOD("blood", 0xB30000, 0xFF4D4D, 0xE88080, 0xFF3030, LoreMotif.BLOOD),
    NATURE("nature", 0x2ECC40, 0xC8F59A, 0xA8E6A1, 0x6BDB6B, LoreMotif.NATURE),
    OCEAN("ocean", 0x1E90FF, 0x40E0D0, 0x9FD8F0, 0x38C8F0, LoreMotif.SEA),
    ROYAL("royal", 0xFFAA00, 0xFFF3A0, 0xEADCA6, 0xFFC94A, LoreMotif.ROYAL),
    HOLY("holy", 0xFFFFFF, 0xFFE87A, 0xF5F0DC, 0xFFF4B0, LoreMotif.HOLY),
    VOID("void", 0x8A2BE2, 0xFF5CF4, 0xD2AEF0, 0xDA70D6, LoreMotif.VOID),
    STORM("storm", 0x7DA7FF, 0xFFFF7A, 0xC4D4F8, 0xA8C8FF, LoreMotif.STORM),
    TOXIC("toxic", 0x7CFC00, 0xE8FF5A, 0xCFFF8A, 0xA6E82E, LoreMotif.DEATH),
    RAINBOW("rainbow", 0xFF5555, 0x5555FF, 0xE6E6E6, 0xFFFF55, null);

    private final String id;
    private final int titleFrom;
    private final int titleTo;
    private final int text;
    private final int accent;
    private final LoreMotif motif;

    LoreScheme(String id, int titleFrom, int titleTo, int text, int accent, LoreMotif motif) {
        this.id = id;
        this.titleFrom = titleFrom;
        this.titleTo = titleTo;
        this.text = text;
        this.accent = accent;
        this.motif = motif;
    }

    public String id() {
        return id;
    }

    public String nameKey() {
        return "betterloka.lore.scheme." + id;
    }

    public int titleFrom() {
        return titleFrom;
    }

    public int titleTo() {
        return titleTo;
    }

    public int text() {
        return text;
    }

    public int accent() {
        return accent;
    }

    /** What the scheme's colours suggest the lore is about, when the description does not say. */
    public LoreMotif motif() {
        return motif;
    }

    public boolean rainbow() {
        return this == RAINBOW;
    }

    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
