package com.betterloka.config;

import com.betterloka.BetterLoka;
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

    /** Language the player writes in; chat is translated into it, and their replies out of it. */
    private String nativeLanguage = "pl";

    /** Language the server is spoken in — what replies get translated into. */
    private String chatLanguage = "en";

    /** Translate incoming server chat into {@link #nativeLanguage}. */
    private boolean translateIncoming = false;

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

    public boolean translateIncoming() {
        return translateIncoming;
    }

    public void setTranslateIncoming(boolean translateIncoming) {
        this.translateIncoming = translateIncoming;
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

    public boolean glowstoneHudEnabled() {
        return glowstoneHudEnabled;
    }

    public void setGlowstoneHudEnabled(boolean glowstoneHudEnabled) {
        this.glowstoneHudEnabled = glowstoneHudEnabled;
        save();
    }
}
