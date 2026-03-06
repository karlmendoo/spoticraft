package com.spoticraft.ui;

import com.spoticraft.api.models.Track;
import com.spoticraft.config.SpotiCraftConfig;
import com.spoticraft.util.AlbumArtCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Toast notification that slides in when a new track starts playing.
 * Shows album art, track name, and artist. Auto-dismisses after ~5 seconds.
 */
public class ToastNotification {

    private static final int TOAST_W = 200;
    private static final int TOAST_H = 48;
    private static final int MARGIN = 10;
    private static final int ART_SIZE = 32;
    private static final int PADDING = 8;

    // Animation
    private float slideOffset = TOAST_W + MARGIN; // starts off-screen to the right
    private float alpha = 0;
    private int displayTick = 0;
    private static final int DISPLAY_DURATION = 100; // ~5 seconds at 20 TPS
    private static final int FADE_DURATION = 20;

    private Track currentTrack = null;
    private boolean active = false;

    /** Show a new track notification. */
    public void showTrack(Track track) {
        this.currentTrack = track;
        this.displayTick = DISPLAY_DURATION + FADE_DURATION;
        this.active = true;
    }

    public void tick() {
        if (!active) return;

        if (displayTick > 0) {
            displayTick--;
            // Slide in
            if (slideOffset > 0) {
                slideOffset = Math.max(0, slideOffset - 12);
            }
            // Fade in
            if (displayTick > DISPLAY_DURATION) {
                alpha = Math.min(1.0f, alpha + 0.1f);
            }
            // Fade out during final FADE_DURATION ticks
            if (displayTick <= FADE_DURATION) {
                alpha = Math.max(0, (float) displayTick / FADE_DURATION);
                slideOffset += 4; // slide out
            }
        } else {
            active = false;
            slideOffset = TOAST_W + MARGIN;
            alpha = 0;
            currentTrack = null;
        }
    }

    public void render(DrawContext context, MinecraftClient client) {
        if (!active || currentTrack == null || alpha <= 0) return;

        int screenW = client.getWindow().getScaledWidth();
        int toastX = screenW - TOAST_W - MARGIN + (int) slideOffset;
        int toastY = MARGIN + 40; // below hotbar

        int alphaByte = (int) (alpha * 0xCC);
        int textAlpha = (int) (alpha * 0xFF);

        // Background
        context.fill(toastX, toastY, toastX + TOAST_W, toastY + TOAST_H, (alphaByte << 24) | 0x000000);

        // Green accent strip on the left
        context.fill(toastX, toastY, toastX + 3, toastY + TOAST_H, (textAlpha << 24) | 0x1DB954);

        // Album art
        if (currentTrack.albumArtUrl != null && !currentTrack.albumArtUrl.isBlank()) {
            Identifier tex = AlbumArtCache.getInstance().get(currentTrack.albumArtUrl);
            if (tex != null) {
                context.drawTexture(tex, toastX + PADDING, toastY + (TOAST_H - ART_SIZE) / 2,
                        0, 0, ART_SIZE, ART_SIZE, ART_SIZE, ART_SIZE);
            } else {
                context.fill(toastX + PADDING, toastY + (TOAST_H - ART_SIZE) / 2,
                        toastX + PADDING + ART_SIZE, toastY + (TOAST_H + ART_SIZE) / 2,
                        (alphaByte << 24) | 0x1A1A1A);
            }
        }

        // Text
        int textX = toastX + PADDING + ART_SIZE + 6;
        int maxTextW = TOAST_W - ART_SIZE - PADDING * 2 - 10;

        String name = currentTrack.name != null ? currentTrack.name : "Unknown";
        String artist = currentTrack.getArtistString();

        // "Now Playing" label
        context.drawText(client.textRenderer, "Now Playing",
                textX, toastY + 6, (textAlpha << 24) | 0x1DB954, false);

        context.drawText(client.textRenderer, truncate(client, name, maxTextW),
                textX, toastY + 16, (textAlpha << 24) | 0xFFFFFF, false);

        context.drawText(client.textRenderer, truncate(client, artist, maxTextW),
                textX, toastY + 27, (textAlpha << 24) | 0xB3B3B3, false);
    }

    private String truncate(MinecraftClient client, String text, int maxW) {
        if (client.textRenderer.getWidth(text) <= maxW) return text;
        while (text.length() > 0 && client.textRenderer.getWidth(text + "…") > maxW) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }
}
