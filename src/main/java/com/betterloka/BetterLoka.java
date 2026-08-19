package com.betterloka;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod-wide constants. */
public final class BetterLoka {
    public static final String MOD_ID = "betterloka";
    public static final String MOD_NAME = "BetterLoka";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    public static final String VERSION = resolveVersion();

    private BetterLoka() {
    }

    /**
     * The loader is not running when the data layer is exercised from tests, so a missing
     * {@code FabricLoader} is not an error here — it just means "not in game".
     */
    private static String resolveVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("dev");
        } catch (Throwable ignored) {
            return "dev";
        }
    }
}
