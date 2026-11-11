package com.betterloka.config;

import com.betterloka.BetterLoka;
import com.betterloka.assistant.AssistantClient;
import com.betterloka.grind.ShrineKind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** User settings, stored as JSON next to the mod's other files in the config directory. */
public final class BetterLokaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Show each player's K/D beside their nameplate in the world. */
    private boolean showNameplateKd = false;
    /** Conquest Elo beside each player's name tag, like the K/D. Off until turned on. */
    private boolean showNameplateElo = false;
    /** Each player's ping beside their name tag, live from the player list. Off until turned on. */
    private boolean showNameplatePing = false;

    /** The language chat is translated into in the Translator: the player's own. */
    private String nativeLanguage = "pl";

    /** What the player's own text is translated into in the Translator. English unless changed. */
    private String chatLanguage = "en";


    /** Keep watching who holds every territory, so fallen towns are noticed. */
    private boolean townLogEnabled = true;

    /**
     * Minutes between territory sweeps. Each one is three requests and roughly 850 KB, so this is
     * the setting that decides what the Town Logger costs to leave running. Town deletions are rare
     * enough that half an hour loses nothing, and it is a quarter of the traffic ten minutes was.
     */
    private int townLogIntervalMinutes = 30;

    /**
     * Seconds between checks that Loka's map has changed.
     *
     * <p>A check is about three hundred bytes — Dynmap's own "anything since?" endpoint — and the
     * hundred-and-twenty-kilobyte marker file is only refetched when the answer is yes or the data
     * has gone five minutes old. Forty-five seconds is therefore cheap and keeps a capture visible
     * within about a minute.
     */
    private int mapRefreshSeconds = 45;

    /** Start the shulker timer by itself when a shulker the player hit dies. */
    private boolean shulkerAutoStart = true;

    /** How long the glowstone timer counts, in minutes. Three hours, adjustable from the screen. */
    private int glowstoneMinutes = 180;

    /** Draw the shulker countdown in the corner of the screen while it runs. */
    private boolean shulkerHudEnabled = true;

    /** Draw the glowstone countdown in the corner of the screen while it runs. */
    private boolean glowstoneHudEnabled = true;

    /** Whether to run the inventory and hotbar at their own GUI scales. Off unless asked for. */
    private boolean guiScaleEnabled = false;

    /** GUI scale while an inventory is open. 4 is roomy enough to hit a slot without aiming. */
    private int inventoryGuiScale = 4;

    /** GUI scale the rest of the time, which is what sizes the hotbar. */
    private int hotbarGuiScale = 3;

    /** Draw the Nether shrine waypoints at all. Each kind has its own switch below as well. */
    private boolean shrineWaypointsEnabled = true;
    private boolean shrineGhastEnabled = true;
    private boolean shrineBlazeEnabled = true;
    private boolean shrineMagmaCubeEnabled = true;

    /** Say in chat, in any world, when a shrine's cooldown has run out. */
    private boolean shrineNotifications = true;

    // --- Loka Helper ---

    /** The coordinates and facing, on screen. Off until the player turns them on. */
    private boolean coordsHudEnabled = false;
    /** How big the coordinates box is, in percent of its normal size. */
    private int coordsHudScale = 100;
    /**
     * Where they are, as a fraction of the room the screen leaves for them: 0 is the left or top
     * edge, 1 the right or bottom. A fraction rather than pixels, so a resize or a new GUI scale
     * keeps them in the same corner rather than pushing them off the screen.
     */
    private double coordsHudX = 0.0;
    private double coordsHudY = 0.0;
    /** Lava seen through rather than as a wall of orange fog. Off unless the player turns it on. */
    private boolean clearLava = false;
    /** Whether the /tl key does anything; off, and with no key, until the player sets both. */
    private boolean tlKeyEnabled = false;
    /**
     * The continents whose important places are marked, by id. Empty until the player switches one
     * on: every continent starts off.
     */
    private java.util.List<String> importantRegions = new java.util.ArrayList<>();

    /** How long a purged shrine takes to come back, in minutes. */
    private int shrineCooldownMinutes = 20;

    /**
     * Whether a cooldown saved under the old default has been moved to the new one. 0.35 started at
     * fifteen minutes and wrote that into every config it saved; the default is now twenty, and a
     * saved fifteen is the old default rather than a choice, so it is moved once. Set afterwards, a
     * fifteen is the player's own and stays.
     */
    private boolean shrineCooldownMigrated = false;

    /**
     * Whether the coordinates have been turned off once. 0.39 showed them from the start and wrote
     * that into every config it saved; they now start off, and a saved "on" from then is the old
     * default rather than a choice, so it is turned off once. Turned on afterwards, it stays on.
     */
    private boolean coordsHudDefaultMigrated = false;

    /**
     * The Loka Assistant server the player entered, such as {@code http://203.0.113.5:8787}. Empty:
     * the mod's built-in server ({@link AssistantClient#DEFAULT_SERVER}), or, without one, the
     * assistant asks for an address. Nothing is sent to it before the player accepts the notice.
     */
    private String assistantServer = "";
    /** Whether the player has read where their questions go. */
    private boolean assistantNoticeAccepted = false;


    private transient Path file;

    public static BetterLokaConfig load(Path file) {
        BetterLokaConfig config = new BetterLokaConfig();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                BetterLokaConfig loaded = GSON.fromJson(reader, BetterLokaConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException | JsonSyntaxException e) {
                BetterLoka.LOGGER.warn("Could not read the BetterLoka config, using defaults", e);
            }
        }
        config.file = file;
        if (!config.shrineCooldownMigrated) {
            if (config.shrineCooldownMinutes == 15) {
                config.shrineCooldownMinutes = 20;
            }
            config.shrineCooldownMigrated = true;
            if (Files.isRegularFile(file)) {
                config.save();
            }
        }
        if (!config.coordsHudDefaultMigrated) {
            config.coordsHudEnabled = false;
            config.coordsHudDefaultMigrated = true;
            if (Files.isRegularFile(file)) {
                config.save();
            }
        }
        return config;
    }

    /** Writes through a temporary file so an interrupted save cannot leave a truncated config. */
    public void save() {
        if (file == null) {
            return;
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            BetterLoka.LOGGER.warn("Could not save the BetterLoka config", e);
        }
    }

    public boolean shrineWaypointsEnabled() {
        return shrineWaypointsEnabled;
    }

    public void setShrineWaypointsEnabled(boolean shrineWaypointsEnabled) {
        this.shrineWaypointsEnabled = shrineWaypointsEnabled;
        save();
    }

    /** Whether one kind of shrine is shown, before the master switch is considered. */
    public boolean shrineKindEnabled(ShrineKind kind) {
        return switch (kind) {
            case GHAST -> shrineGhastEnabled;
            case BLAZE -> shrineBlazeEnabled;
            case MAGMA_CUBE -> shrineMagmaCubeEnabled;
        };
    }

    public void setShrineKindEnabled(ShrineKind kind, boolean enabled) {
        switch (kind) {
            case GHAST -> shrineGhastEnabled = enabled;
            case BLAZE -> shrineBlazeEnabled = enabled;
            case MAGMA_CUBE -> shrineMagmaCubeEnabled = enabled;
        }
        save();
    }

    public boolean shrineNotifications() {
        return shrineNotifications;
    }

    public void setShrineNotifications(boolean shrineNotifications) {
        this.shrineNotifications = shrineNotifications;
        save();
    }

    public boolean coordsHudEnabled() {
        return coordsHudEnabled;
    }

    public void setCoordsHudEnabled(boolean coordsHudEnabled) {
        this.coordsHudEnabled = coordsHudEnabled;
        save();
    }

    public double coordsHudX() {
        return clamp01(coordsHudX);
    }

    public double coordsHudY() {
        return clamp01(coordsHudY);
    }

    /** Where the coordinates go, each 0 to 1 across the room the screen leaves for them. */
    public void setCoordsHudPosition(double x, double y) {
        this.coordsHudX = clamp01(x);
        this.coordsHudY = clamp01(y);
        save();
    }

    public boolean importantRegionEnabled(com.betterloka.helper.ImportantPlaces.Region region) {
        return importantRegions != null && importantRegions.contains(region.id());
    }

    /** Whether any continent's important places are switched on — the marker layer's quick exit. */
    public boolean anyImportantRegion() {
        return importantRegions != null && !importantRegions.isEmpty();
    }

    public void setImportantRegionEnabled(com.betterloka.helper.ImportantPlaces.Region region, boolean enabled) {
        if (importantRegions == null) {
            importantRegions = new java.util.ArrayList<>();
        }
        importantRegions.remove(region.id());
        if (enabled) {
            importantRegions.add(region.id());
        }
        save();
    }

    /** The smallest and largest the coordinates box can be, in percent, and the step between. */
    public static final int COORDS_SCALE_MIN = 50;
    public static final int COORDS_SCALE_MAX = 150;
    public static final int COORDS_SCALE_STEP = 10;

    /** How big the coordinates box is, in percent of its normal size. */
    public int coordsHudScale() {
        return clampCoordsScale(coordsHudScale);
    }

    public void setCoordsHudScale(int percent) {
        this.coordsHudScale = clampCoordsScale(percent);
        save();
    }

    private static int clampCoordsScale(int percent) {
        // Zero is what a config written before the setting existed reads as.
        if (percent <= 0) {
            return 100;
        }
        return Math.max(COORDS_SCALE_MIN, Math.min(COORDS_SCALE_MAX, percent));
    }

    private static double clamp01(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    public boolean clearLava() {
        return clearLava;
    }

    public void setClearLava(boolean clearLava) {
        this.clearLava = clearLava;
        save();
    }

    public boolean tlKeyEnabled() {
        return tlKeyEnabled;
    }

    public void setTlKeyEnabled(boolean tlKeyEnabled) {
        this.tlKeyEnabled = tlKeyEnabled;
        save();
    }

    public static final int SHRINE_COOLDOWN_MIN = 1;
    public static final int SHRINE_COOLDOWN_MAX = 180;

    public int shrineCooldownMinutes() {
        return Math.max(SHRINE_COOLDOWN_MIN, Math.min(SHRINE_COOLDOWN_MAX, shrineCooldownMinutes));
    }

    public void setShrineCooldownMinutes(int minutes) {
        this.shrineCooldownMinutes = Math.max(SHRINE_COOLDOWN_MIN, Math.min(SHRINE_COOLDOWN_MAX, minutes));
        save();
    }

    /** Whether a kind's waypoints are drawn: its own switch and the master one both on. */
    public boolean shrineShown(ShrineKind kind) {
        return shrineWaypointsEnabled && shrineKindEnabled(kind);
    }

    public boolean showNameplatePing() {
        return showNameplatePing;
    }

    public void setShowNameplatePing(boolean showNameplatePing) {
        this.showNameplatePing = showNameplatePing;
        save();
    }

    /** The assistant server to use: the player's own, else the built-in one; empty if neither. */
    public String assistantServer() {
        return AssistantClient.effectiveServer(assistantServer, AssistantClient.DEFAULT_SERVER);
    }

    /** Sets the player's own server; empty (or the built-in address) goes back to the built-in one. */
    public void setAssistantServer(String assistantServer) {
        this.assistantServer = AssistantClient.serverToStore(assistantServer, AssistantClient.DEFAULT_SERVER);
        save();
    }

    public boolean assistantNoticeAccepted() {
        return assistantNoticeAccepted;
    }

    public void setAssistantNoticeAccepted(boolean assistantNoticeAccepted) {
        this.assistantNoticeAccepted = assistantNoticeAccepted;
        save();
    }

    public boolean showNameplateElo() {
        return showNameplateElo;
    }

    public void setShowNameplateElo(boolean showNameplateElo) {
        this.showNameplateElo = showNameplateElo;
        save();
    }

    public boolean showNameplateKd() {
        return showNameplateKd;
    }

    public void setShowNameplateKd(boolean showNameplateKd) {
        this.showNameplateKd = showNameplateKd;
        save();
    }

    public String nativeLanguage() {
        return nativeLanguage == null || nativeLanguage.isBlank() ? "pl" : nativeLanguage;
    }

    public void setNativeLanguage(String nativeLanguage) {
        this.nativeLanguage = nativeLanguage;
        save();
    }

    public String chatLanguage() {
        return chatLanguage == null || chatLanguage.isBlank() ? "en" : chatLanguage;
    }

    public void setChatLanguage(String chatLanguage) {
        this.chatLanguage = chatLanguage;
        save();
    }

    public boolean townLogEnabled() {
        return townLogEnabled;
    }

    public void setTownLogEnabled(boolean townLogEnabled) {
        this.townLogEnabled = townLogEnabled;
        save();
    }

    /** The intervals the Town Logger screen offers, in minutes. */
    public static final int[] TOWN_LOG_INTERVALS = {2, 5, 10, 30, 60};

    public int townLogIntervalMinutes() {
        return townLogIntervalMinutes < 1 ? 30 : townLogIntervalMinutes;
    }

    public void setTownLogIntervalMinutes(int minutes) {
        this.townLogIntervalMinutes = Math.max(1, minutes);
        save();
    }

    public int mapRefreshSeconds() {
        return Math.max(15, mapRefreshSeconds);
    }

    public void setMapRefreshSeconds(int seconds) {
        this.mapRefreshSeconds = Math.max(15, seconds);
    }

    public boolean shulkerAutoStart() {
        return shulkerAutoStart;
    }

    public void setShulkerAutoStart(boolean shulkerAutoStart) {
        this.shulkerAutoStart = shulkerAutoStart;
        save();
    }

    /** Clamped to something a person would actually set, so a bad edit cannot make it useless. */
    public int glowstoneMinutes() {
        return Math.max(1, Math.min(720, glowstoneMinutes));
    }

    public void setGlowstoneMinutes(int minutes) {
        this.glowstoneMinutes = Math.max(1, Math.min(720, minutes));
        save();
    }

    public boolean shulkerHudEnabled() {
        return shulkerHudEnabled;
    }

    public void setShulkerHudEnabled(boolean shulkerHudEnabled) {
        this.shulkerHudEnabled = shulkerHudEnabled;
        save();
    }

    public boolean guiScaleEnabled() {
        return guiScaleEnabled;
    }

    public void setGuiScaleEnabled(boolean guiScaleEnabled) {
        this.guiScaleEnabled = guiScaleEnabled;
        save();
    }

    /** Clamped to what Minecraft's own option offers: Auto, or 1 to 4. */
    public int inventoryGuiScale() {
        return clampScale(inventoryGuiScale);
    }

    public void setInventoryGuiScale(int scale) {
        this.inventoryGuiScale = clampScale(scale);
        save();
    }

    public int hotbarGuiScale() {
        return clampScale(hotbarGuiScale);
    }

    public void setHotbarGuiScale(int scale) {
        this.hotbarGuiScale = clampScale(scale);
        save();
    }

    /** Zero is Auto; anything outside the range a hand-edited file might hold is brought back in. */
    private static int clampScale(int scale) {
        return Math.max(0, Math.min(4, scale));
    }

    public boolean glowstoneHudEnabled() {
        return glowstoneHudEnabled;
    }

    public void setGlowstoneHudEnabled(boolean glowstoneHudEnabled) {
        this.glowstoneHudEnabled = glowstoneHudEnabled;
        save();
    }
}
