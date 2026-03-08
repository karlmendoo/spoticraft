package io.github.karlmendoo.spoticraft.ui.overlay;

import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAPI;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * HUD overlay that shows a notification when the current track changes.
 * Displays track name and artist with a smooth fade-in/fade-out animation.
 */
public class TrackChangeOverlay {
    private static String trackName = "";
    private static String trackArtist = "";
    private static long showUntil = 0;
    private static long showStart = 0;

    private static final int FADE_IN_MS = 300;
    private static final int FADE_OUT_MS = 500;

    public static void register() {
        HudRenderCallback.EVENT.register(TrackChangeOverlay::render);

        // Register track change listener once polling starts
        SpotiCraftClient.getPlayer().setOnTrackChange(() -> {
            if (!SpotiCraftClient.getConfig().isShowTrackNotifications()) return;
            SpotifyAPI.TrackInfo track = SpotiCraftClient.getPlayer().getCurrentTrack();
            if (track != null) {
                trackName = track.name();
                trackArtist = track.artist();
                showStart = System.currentTimeMillis();
                showUntil = showStart + SpotiCraftClient.getConfig().getNotificationDurationMs();
            }
        });
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        long now = System.currentTimeMillis();
        if (now >= showUntil || trackName.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) return; // Don't show during GUI screens

        // Calculate alpha for fade animation
        float alpha;
        long elapsed = now - showStart;
        long remaining = showUntil - now;

        if (elapsed < FADE_IN_MS) {
            alpha = (float) elapsed / FADE_IN_MS;
        } else if (remaining < FADE_OUT_MS) {
            alpha = (float) remaining / FADE_OUT_MS;
        } else {
            alpha = 1.0f;
        }

        int a = (int) (alpha * 220);
        if (a <= 0) return;

        int textAlpha = (int) (alpha * 255);
        int screenWidth = client.getWindow().getScaledWidth();

        // Calculate panel dimensions
        int padding = 8;
        int nameWidth = client.textRenderer.getWidth(trackName);
        int artistWidth = client.textRenderer.getWidth(trackArtist);
        int panelWidth = Math.max(nameWidth, artistWidth) + padding * 2 + 10;
        int panelHeight = 30;
        int panelX = screenWidth - panelWidth - 10;
        int panelY = 10;

        // Background
        int bgColor = (a << 24) | 0x181818;
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, bgColor);

        // Accent bar
        int accentColor = (textAlpha << 24) | 0x1DB954;
        context.fill(panelX, panelY, panelX + 3, panelY + panelHeight, accentColor);

        // Text
        int nameColor = (textAlpha << 24) | 0xFFFFFF;
        int artistColor = (textAlpha << 24) | 0x999999;

        String displayName = trackName.length() > 35 ? trackName.substring(0, 32) + "..." : trackName;
        String displayArtist = trackArtist.length() > 40 ? trackArtist.substring(0, 37) + "..." : trackArtist;

        context.drawTextWithShadow(client.textRenderer, Text.literal(displayName), panelX + padding + 3, panelY + 5, nameColor);
        context.drawTextWithShadow(client.textRenderer, Text.literal(displayArtist), panelX + padding + 3, panelY + 17, artistColor);
    }
}
