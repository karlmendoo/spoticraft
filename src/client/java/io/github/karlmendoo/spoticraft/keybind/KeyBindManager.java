package io.github.karlmendoo.spoticraft.keybind;

import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import io.github.karlmendoo.spoticraft.ui.screen.SpotifyPlayerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Registers and handles keybinds for quick Spotify controls.
 */
public class KeyBindManager {
    private static KeyBinding openPlayerKey;
    private static KeyBinding playPauseKey;
    private static KeyBinding nextTrackKey;
    private static KeyBinding prevTrackKey;

    public static void register() {
        openPlayerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.open_player",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.spoticraft"
        ));

        playPauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.play_pause",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, // Unbound by default
                "category.spoticraft"
        ));

        nextTrackKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.next_track",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "category.spoticraft"
        ));

        prevTrackKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.spoticraft.prev_track",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "category.spoticraft"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(KeyBindManager::handleTick);
    }

    private static void handleTick(MinecraftClient client) {
        while (openPlayerKey.wasPressed()) {
            client.setScreen(new SpotifyPlayerScreen(null));
        }

        if (!SpotiCraftClient.getConfig().isAuthenticated()
                && !SpotiCraftClient.getConfig().hasRefreshToken()) {
            return;
        }

        while (playPauseKey.wasPressed()) {
            if (SpotiCraftClient.getPlayer().isPlaying()) {
                SpotiCraftClient.getApi().pause();
            } else {
                SpotiCraftClient.getApi().play();
            }
        }

        while (nextTrackKey.wasPressed()) {
            SpotiCraftClient.getApi().next();
        }

        while (prevTrackKey.wasPressed()) {
            SpotiCraftClient.getApi().previous();
        }
    }
}
