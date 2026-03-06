package com.spoticraft;

import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.SpotifyAuth;
import com.spoticraft.ui.HudOverlay;
import com.spoticraft.ui.SpotiCraftScreen;
import com.spoticraft.ui.ToastNotification;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * SpotiCraft client initializer.
 * Registers keybindings and HUD overlays.
 */
public class SpotiCraftClient implements ClientModInitializer {

    // Keybindings
    public static KeyBinding openUiKey;
    public static KeyBinding playPauseKey;
    public static KeyBinding nextTrackKey;
    public static KeyBinding prevTrackKey;
    public static KeyBinding volumeUpKey;
    public static KeyBinding volumeDownKey;

    // Singleton instances for HUD components
    public static final HudOverlay HUD_OVERLAY = new HudOverlay();
    public static final ToastNotification TOAST = new ToastNotification();

    @Override
    public void onInitializeClient() {
        SpotiCraftMod.LOGGER.info("SpotiCraft client initializing...");

        // Register keybindings
        openUiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.open_ui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "key.categories.spoticraft"
        ));

        playPauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.play_pause",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.spoticraft"
        ));

        nextTrackKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.next_track",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.spoticraft"
        ));

        prevTrackKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.prev_track",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.spoticraft"
        ));

        volumeUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.volume_up",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.spoticraft"
        ));

        volumeDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.volume_down",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "key.categories.spoticraft"
        ));

        // Register HUD overlay for now-playing widget and toasts
        HudRenderCallback.EVENT.register((drawContext, tickDeltaManager) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                HUD_OVERLAY.render(drawContext, client);
                TOAST.render(drawContext, client);
            }
        });

        // Tick event — handle keybinds and poll Spotify state
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Open/close main UI
            if (openUiKey.wasPressed()) {
                if (client.currentScreen instanceof SpotiCraftScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new SpotiCraftScreen());
                }
            }

            // Play/Pause
            if (playPauseKey.wasPressed()) {
                SpotifyAPI.getInstance().togglePlayPause();
            }

            // Next track
            if (nextTrackKey.wasPressed()) {
                SpotifyAPI.getInstance().skipToNext();
            }

            // Previous track
            if (prevTrackKey.wasPressed()) {
                SpotifyAPI.getInstance().skipToPrevious();
            }

            // Volume up
            if (volumeUpKey.wasPressed()) {
                SpotifyAPI.getInstance().adjustVolume(10);
            }

            // Volume down
            if (volumeDownKey.wasPressed()) {
                SpotifyAPI.getInstance().adjustVolume(-10);
            }

            // Tick HUD components
            HUD_OVERLAY.tick();
            TOAST.tick();
        });

        // Start background polling for playback state if authenticated
        SpotifyAuth.getInstance().startTokenRefreshScheduler();

        SpotiCraftMod.LOGGER.info("SpotiCraft client initialized. Press M to open the UI.");
    }
}
