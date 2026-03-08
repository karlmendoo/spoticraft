package io.github.karlmendoo.spoticraft;

import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import io.github.karlmendoo.spoticraft.keybind.KeyBindManager;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAPI;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAuthManager;
import io.github.karlmendoo.spoticraft.spotify.SpotifyPlayer;
import io.github.karlmendoo.spoticraft.ui.overlay.TrackChangeOverlay;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main client-side entrypoint for SpotiCraft.
 * Initializes all subsystems: config, auth, API, player, keybinds, and overlay.
 */
public class SpotiCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "spoticraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SpotiCraftConfig config;
    private static SpotifyAuthManager authManager;
    private static SpotifyAPI api;
    private static SpotifyPlayer player;

    @Override
    public void onInitializeClient() {
        LOGGER.info("SpotiCraft initializing...");

        config = SpotiCraftConfig.load();
        authManager = new SpotifyAuthManager(config);
        api = new SpotifyAPI(authManager);
        player = new SpotifyPlayer(api);

        KeyBindManager.register();
        TrackChangeOverlay.register();

        LOGGER.info("SpotiCraft initialized successfully!");
    }

    public static SpotiCraftConfig getConfig() {
        return config;
    }

    public static SpotifyAuthManager getAuthManager() {
        return authManager;
    }

    public static SpotifyAPI getApi() {
        return api;
    }

    public static SpotifyPlayer getPlayer() {
        return player;
    }
}
