package com.betterloka.gui;

import com.betterloka.BetterLokaClient;
import com.betterloka.api.model.LokaAlliance;
import com.betterloka.api.model.LokaTown;
import com.betterloka.towns.Vulnerability;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Whose window of vulnerability a town fights under, and how to say it.
 *
 * <p>An alliance shares one window between all its towns, set with {@code /town a vulnerable}, so a
 * town in one is attackable when its alliance is; a town on its own has its own. Everything here is
 * read from what is already in hand — the town roster and the alliances — so it is safe on the
 * render thread; {@link #prefetch} brings them in for the next frame.
 */
final class VulnInfo {
    /** A window, and the alliance it belongs to ({@code null} when it is the town's own). */
    record Found(Vulnerability window, String alliance) {
        boolean fromAlliance() {
            return alliance != null;
        }
    }

    private VulnInfo() {
    }

    /** Starts loading the roster and the alliances if they are not in hand yet. */
    static void prefetch() {
        CompletableFuture.runAsync(() -> BetterLokaClient.towns().ensureLoaded(), BetterLokaClient.lokaExecutor());
        if (alliances().isEmpty()) {
            BetterLokaClient.standings().poll(System.currentTimeMillis());
        }
    }

    /**
     * The window {@code townName} is attackable in, or {@code null} when it is not known (yet).
     *
     * @param allianceName the alliance the map names for it, if any; also found from the alliance
     *                     list when the map does not say
     */
    static Found find(String townName, String allianceName) {
        LokaTown town = BetterLokaClient.towns().peekByName(townName);
        LokaAlliance alliance = alliance(allianceName, town);
        if (alliance == null && allianceName != null && !allianceName.isBlank() && alliances().isEmpty()) {
            // In an alliance whose window is not in hand yet: better nothing than the town's own,
            // which is not the one that counts.
            return null;
        }
        return find(town, alliance);
    }

    /** As {@link #find(String, String)}, with the records already looked up. */
    static Found find(LokaTown town, LokaAlliance alliance) {
        if (alliance != null) {
            Vulnerability shared = Vulnerability.of(alliance.vulnerabilityWindow());
            if (shared != null) {
                return new Found(shared, alliance.name());
            }
        }
        Vulnerability own = town == null ? null : Vulnerability.of(town.vulnerabilityWindow());
        return own == null ? null : new Found(own, null);
    }

    private static LokaAlliance alliance(String name, LokaTown town) {
        for (LokaAlliance alliance : alliances()) {
            if (name != null && name.equalsIgnoreCase(alliance.name())) {
                return alliance;
            }
            if (town != null && alliance.contains(town.id())) {
                return alliance;
            }
        }
        return null;
    }

    /** The Town Logger's reading if it has made one, otherwise the calculator's. */
    private static List<LokaAlliance> alliances() {
        List<LokaAlliance> logged = BetterLokaClient.townLogger().alliances();
        return logged.isEmpty() ? BetterLokaClient.standings().alliances() : logged;
    }

    /** {@code 21:00 – 05:00 CET}: the window in the player's own clock, with the zone's name. */
    static String localRange(Vulnerability window, Instant now) {
        ZoneId zone = ZoneId.systemDefault();
        return window.range(zone, now) + " " + Vulnerability.zoneName(zone, now);
    }

    /** {@code 15:00 – 23:00 EST}: the same window in Loka's clock. */
    static String lokaRange(Vulnerability window, Instant now) {
        return window.range(Vulnerability.LOKA_ZONE, now) + " " + Vulnerability.zoneName(Vulnerability.LOKA_ZONE, now);
    }

    /** Open with how long it has left, or shut with how long until it opens. */
    static Component state(Vulnerability window, Instant now) {
        return window.openAt(now)
                ? Component.translatable("betterloka.town.vuln_open", Vulnerability.hoursMinutes(window.untilClose(now)))
                : Component.translatable("betterloka.town.vuln_opens", Vulnerability.hoursMinutes(window.untilOpen(now)));
    }

    /** Red while it can be attacked, green while it cannot. */
    static int stateColor(Vulnerability window, Instant now) {
        return window.openAt(now) ? GuiTheme.BAD : GuiTheme.GOOD;
    }
}
