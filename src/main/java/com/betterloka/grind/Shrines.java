package com.betterloka.grind;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loka's Nether shrines, and how a chat line saying one was purged is tied to the right one.
 *
 * <p>Plain Java with no Minecraft in it, so it is tested outside the game.
 */
public final class Shrines {
    /** How long a purged shrine takes to come back, until the player sets it otherwise. */
    public static final long COOLDOWN_MILLIS = 20 * 60 * 1000L;

    /**
     * The cooldown 0.34 assumed, which is what its saved end times were counted with. Only used to
     * turn those back into purge times.
     */
    static final long LEGACY_COOLDOWN_MILLIS = 15 * 60 * 1000L;

    /**
     * Every shrine, as the player gave them.
     *
     * <p>Blaze Shrine 2 came with two numbers rather than three — {@code 864, 908} — which can only
     * be X and Z: no height in the Nether reaches 864. Its height is left unknown rather than
     * invented.
     */
    public static final List<Shrine> ALL = List.of(
            new Shrine(ShrineKind.GHAST, 1, 1037, 177, 1190),
            new Shrine(ShrineKind.GHAST, 2, 527, 129, 963),
            new Shrine(ShrineKind.GHAST, 3, 1207, 71, 1386),
            new Shrine(ShrineKind.GHAST, 4, 630, 67, 1230),
            new Shrine(ShrineKind.BLAZE, 1, 1169, 68, 1285),
            new Shrine(ShrineKind.BLAZE, 2, 864, null, 908),
            new Shrine(ShrineKind.BLAZE, 3, 351, 172, 1173),
            new Shrine(ShrineKind.BLAZE, 4, 397, 72, 1267),
            new Shrine(ShrineKind.MAGMA_CUBE, 1, 1016, 71, 1202),
            new Shrine(ShrineKind.MAGMA_CUBE, 2, 1347, 178, 1346),
            new Shrine(ShrineKind.MAGMA_CUBE, 3, 675, 153, 1194));

    /** {@code <name> was purged!}, with whatever prefix the server puts in front of it. */
    private static final Pattern PURGED = Pattern.compile("(?i)^(.*?\\S)\\s+(?:was|has been)\\s+purged\\b.*$");

    /** Characters a server uses to set a prefix off from the message itself. */
    private static final Pattern PREFIX = Pattern.compile("^.*[»>:|\\]]\\s*");

    private static final Pattern NUMBER = Pattern.compile("(?:^|\\D)(\\d{1,2})(?:\\D|$)");
    private static final Pattern ROMAN = Pattern.compile("(?i)(?:^|\\s)(iv|i{1,3})(?:\\s|$)");

    private Shrines() {
    }

    public static Shrine byId(String id) {
        for (Shrine shrine : ALL) {
            if (shrine.id().equals(id)) {
                return shrine;
            }
        }
        return null;
    }

    /** The shrine whose ground a position is on, the nearest one should two ever touch. */
    public static Shrine at(double worldX, double worldZ) {
        Shrine best = null;
        for (Shrine shrine : ALL) {
            if (shrine.contains(worldX, worldZ)
                    && (best == null || shrine.distanceTo(worldX, worldZ) < best.distanceTo(worldX, worldZ))) {
                best = shrine;
            }
        }
        return best;
    }

    /**
     * The shrine name out of a purge announcement, or {@code null} for any other line.
     *
     * <p>Colour codes and a chat prefix — {@code [Loka] }, {@code Shrines » } — are taken off, so
     * what is left is the name as the server spells it.
     */
    public static String purgedName(String line) {
        if (line == null) {
            return null;
        }
        String plain = line.replaceAll("§.", "").strip();
        Matcher matcher = PURGED.matcher(plain);
        if (!matcher.matches()) {
            return null;
        }
        String name = PREFIX.matcher(matcher.group(1)).replaceFirst("").strip();
        return name.isEmpty() ? null : name;
    }

    /**
     * Which shrine a purged name means.
     *
     * <ol>
     *   <li>A name that says the mob and the number — {@code Ghast Shrine 2} — is that shrine,
     *       wherever the player is.</li>
     *   <li>Otherwise it is the shrine the player is standing at, or just left, as long as the name
     *       does not name a different mob. This is what the shrine areas are for: the server's own
     *       name for a shrine need not say which one it is, but where the player stood does.</li>
     *   <li>Away from every shrine, a name learned at one earlier is that one — unless it has been
     *       seen at two different shrines, which makes it a name for several.</li>
     * </ol>
     *
     * <p>Standing at a shrine beats a learned name so that a name the server uses for more than one
     * shrine gets the chance to show it, and stops being trusted.
     *
     * @param here    the shrine the player is at or has just left, or {@code null}
     * @param learned names seen before, normalised by {@link #key}, to shrine ids or {@link #SEVERAL}
     * @return the shrine, or {@code null} when there is no telling
     */
    public static Shrine resolve(String name, Shrine here, Map<String, String> learned) {
        if (name == null) {
            return null;
        }
        String key = key(name);
        ShrineKind kind = kindIn(key);
        Integer number = numberIn(key);
        if (kind != null && number != null) {
            for (Shrine shrine : ALL) {
                if (shrine.kind() == kind && shrine.number() == number) {
                    return shrine;
                }
            }
        }
        if (here != null && (kind == null || kind == here.kind())) {
            return here;
        }
        String known = learned.get(key);
        return known == null ? null : byId(known);
    }

    /** What a learned name is set to once it has turned up at two different shrines. */
    public static final String SEVERAL = "*";

    /**
     * Whether a name is worth remembering against the shrine it was heard at.
     *
     * <p>Only a name of its own: one that says the mob but not the number — {@code Ghast Shrine} —
     * is almost certainly what every shrine of that mob is called, and remembering it against the
     * first one would send every later purge of that mob there.
     */
    public static boolean learnable(String name) {
        String key = key(name);
        return !key.isEmpty() && kindIn(key) == null && numberIn(key) == null;
    }

    /** Whether a name says for itself which shrine it is, so it need not be learned. */
    public static boolean explicit(String name) {
        String key = key(name);
        return kindIn(key) != null && numberIn(key) != null;
    }

    /** A name reduced for comparison: lower case, accents off, punctuation to single spaces. */
    public static String key(String name) {
        String plain = Normalizer.normalize(name.replaceAll("§.", ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static ShrineKind kindIn(String key) {
        if (key.contains("ghast")) {
            return ShrineKind.GHAST;
        }
        if (key.contains("blaze")) {
            return ShrineKind.BLAZE;
        }
        if (key.contains("magma")) {
            return ShrineKind.MAGMA_CUBE;
        }
        return null;
    }

    static Integer numberIn(String key) {
        Matcher digits = NUMBER.matcher(key);
        if (digits.find()) {
            return Integer.parseInt(digits.group(1));
        }
        Matcher roman = ROMAN.matcher(key);
        if (roman.find()) {
            return switch (roman.group(1).toLowerCase(Locale.ROOT)) {
                case "i" -> 1;
                case "ii" -> 2;
                case "iii" -> 3;
                default -> 4;
            };
        }
        return null;
    }
}
