package com.spoticraft;

import com.spoticraft.config.SpotiCraftConfig;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SpotiCraft mod main initializer.
 * Runs on both client and server (though this mod is client-only).
 * Handles config loading on startup.
 */
public class SpotiCraftMod implements ModInitializer {

    public static final String MOD_ID = "spoticraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("SpotiCraft initializing for Minecraft 1.21.4...");
        // Load configuration on startup
        SpotiCraftConfig.load();
        LOGGER.info("SpotiCraft initialized. Use 'M' key to open the SpotiCraft UI.");
    }
}
