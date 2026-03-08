package io.github.karlmendoo.spoticraft;

import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import io.github.karlmendoo.spoticraft.spotify.SpotifyApiClient;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAuthManager;
import io.github.karlmendoo.spoticraft.spotify.SpotifyService;
import io.github.karlmendoo.spoticraft.ui.AlbumArtCache;
import io.github.karlmendoo.spoticraft.ui.SpotifyOverlay;
import io.github.karlmendoo.spoticraft.ui.SpotifyScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpotiCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "spoticraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private SpotifyService spotifyService;
    private AlbumArtCache albumArtCache;
    private KeyBinding openKey;
    private KeyBinding playPauseKey;
    private KeyBinding nextKey;
    private KeyBinding previousKey;
    private int tickCounter;

    @Override
    public void onInitializeClient() {
        SpotiCraftConfig config = SpotiCraftConfig.load(LOGGER);
        SpotifyAuthManager authManager = new SpotifyAuthManager(config, LOGGER);
        SpotifyApiClient apiClient = new SpotifyApiClient(authManager, LOGGER);
        this.spotifyService = new SpotifyService(config, authManager, apiClient, LOGGER);
        this.albumArtCache = new AlbumArtCache(LOGGER);

        this.openKey = registerKey("open", GLFW.GLFW_KEY_O);
        this.playPauseKey = registerKey("play_pause", GLFW.GLFW_KEY_K);
        this.nextKey = registerKey("next", GLFW.GLFW_KEY_L);
        this.previousKey = registerKey("previous", GLFW.GLFW_KEY_J);

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        HudRenderCallback.EVENT.register(new SpotifyOverlay(this.spotifyService, this.albumArtCache)::render);
    }

    private KeyBinding registerKey(String action, int keyCode) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.spoticraft." + action,
            InputUtil.Type.KEYSYM,
            keyCode,
            KeyBinding.Category.create(Identifier.of(MOD_ID, "keybindings"))
        ));
    }

    private void onEndTick(MinecraftClient client) {
        while (this.openKey.wasPressed()) {
            client.setScreen(new SpotifyScreen(this.spotifyService, this.albumArtCache));
        }
        while (this.playPauseKey.wasPressed()) {
            this.spotifyService.togglePlayPause();
        }
        while (this.nextKey.wasPressed()) {
            this.spotifyService.nextTrack();
        }
        while (this.previousKey.wasPressed()) {
            this.spotifyService.previousTrack();
        }

        this.tickCounter++;
        if (this.tickCounter >= 100) {
            this.tickCounter = 0;
            if (this.spotifyService.config().hasRefreshToken() && client.currentScreen == null) {
                this.spotifyService.refreshPlayback();
            }
        }
    }
}
