package com.spoticraft.ui;

import com.spoticraft.SpotiCraftClient;
import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.models.PlaybackState;
import com.spoticraft.api.models.Track;
import com.spoticraft.config.SpotiCraftConfig;
import com.spoticraft.util.AlbumArtCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Small HUD overlay displayed in a corner of the screen while in-game.
 * Shows: album art thumbnail, track name, artist, and a small progress bar.
 * Visibility is toggled via config.
 */
public class HudOverlay {

    private static final int WIDGET_W = 180;
    private static final int WIDGET_H = 52;
    private static final int MARGIN = 8;
    private static final int ART_SIZE = 36;
    private static final int PADDING = 6;

    // Fade animation
    private float alpha = 0;
    private boolean visible = true;

    // Track last track ID for toast notifications
    private String lastTrackId = null;

    public void tick() {
        boolean shouldShow = SpotiCraftConfig.getInstance().hudVisible &&
                SpotifyAPI.getInstance().getPlaybackState().hasActiveDevice;

        if (shouldShow && alpha < 1.0f) {
            alpha = Math.min(1.0f, alpha + 0.05f);
        } else if (!shouldShow && alpha > 0) {
            alpha = Math.max(0, alpha - 0.05f);
        }

        // Check for track change to fire toast
        PlaybackState state = SpotifyAPI.getInstance().getPlaybackState();
        if (state.currentTrack != null) {
            String tid = state.currentTrack.id;
            if (!tid.equals(lastTrackId)) {
                lastTrackId = tid;
                // Fire the toast notification
                SpotiCraftClient.TOAST.showTrack(state.currentTrack);
            }
        }
    }

    public void render(DrawContext context, MinecraftClient client) {
        if (alpha <= 0) return;

        PlaybackState state = SpotifyAPI.getInstance().getPlaybackState();
        if (!state.hasActiveDevice) return;

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // Position based on config (bottom-right by default)
        int widgetX, widgetY;
        int pos = SpotiCraftConfig.getInstance().hudPosition;
        switch (pos) {
            case 0 -> { widgetX = MARGIN; widgetY = MARGIN; }
            case 1 -> { widgetX = screenW - WIDGET_W - MARGIN; widgetY = MARGIN; }
            case 2 -> { widgetX = MARGIN; widgetY = screenH - WIDGET_H - MARGIN; }
            default -> { widgetX = screenW - WIDGET_W - MARGIN; widgetY = screenH - WIDGET_H - MARGIN; }
        }

        int alphaByte = (int) (alpha * 0xCC);

        // Background panel
        int bgColor = (alphaByte << 24) | 0x000000;
        context.fill(widgetX, widgetY, widgetX + WIDGET_W, widgetY + WIDGET_H, bgColor);

        // Green accent top bar
        int accentAlpha = (int) (alpha * 0xFF);
        context.fill(widgetX, widgetY, widgetX + WIDGET_W, widgetY + 2, (accentAlpha << 24) | 0x1DB954);

        Track track = state.currentTrack;
        if (track == null) {
            // Show "Nothing playing" text
            int color = (accentAlpha << 24) | 0xB3B3B3;
            context.drawCenteredTextWithShadow(client.textRenderer, "Nothing playing",
                    widgetX + WIDGET_W / 2, widgetY + WIDGET_H / 2 - 4, color);
            return;
        }

        // Album art
        if (track.albumArtUrl != null && !track.albumArtUrl.isBlank()) {
            Identifier tex = AlbumArtCache.getInstance().get(track.albumArtUrl);
            if (tex != null) {
                context.drawTexture(tex, widgetX + PADDING, widgetY + PADDING + 2,
                        0, 0, ART_SIZE, ART_SIZE, ART_SIZE, ART_SIZE);
            } else {
                context.fill(widgetX + PADDING, widgetY + PADDING + 2,
                        widgetX + PADDING + ART_SIZE, widgetY + PADDING + 2 + ART_SIZE, (alphaByte << 24) | 0x1A1A1A);
            }
        }

        // Track info
        int textX = widgetX + PADDING + ART_SIZE + 6;
        int maxTextW = WIDGET_W - ART_SIZE - PADDING * 2 - 6;

        String title = track.name != null ? truncate(client, track.name, maxTextW) : "Unknown";
        String artist = track.getArtistString();
        int textAlpha = (int) (alpha * 0xFF);

        context.drawText(client.textRenderer, title, textX, widgetY + PADDING + 3,
                (textAlpha << 24) | 0xFFFFFF, false);
        context.drawText(client.textRenderer, truncate(client, artist, maxTextW), textX, widgetY + PADDING + 14,
                (textAlpha << 24) | 0xB3B3B3, false);

        // Play/pause indicator
        String playIcon = state.isPlaying ? "▶" : "⏸";
        int iconColor = (textAlpha << 24) | 0x1DB954;
        context.drawText(client.textRenderer, playIcon, textX, widgetY + PADDING + 25, iconColor, false);

        // Progress bar (small)
        int barX = widgetX + PADDING;
        int barY = widgetY + WIDGET_H - 8;
        int barW = WIDGET_W - PADDING * 2;
        int barH = 2;

        long progress = state.progressMs;
        long duration = track.durationMs;
        float fraction = duration > 0 ? (float) progress / duration : 0;

        int barBg = (alphaByte << 24) | 0x333333;
        int barFill = (accentAlpha << 24) | 0x1DB954;

        context.fill(barX, barY, barX + barW, barY + barH, barBg);
        context.fill(barX, barY, barX + (int)(barW * fraction), barY + barH, barFill);
    }

    private String truncate(MinecraftClient client, String text, int maxW) {
        if (client.textRenderer.getWidth(text) <= maxW) return text;
        while (text.length() > 0 && client.textRenderer.getWidth(text + "…") > maxW) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }
}
