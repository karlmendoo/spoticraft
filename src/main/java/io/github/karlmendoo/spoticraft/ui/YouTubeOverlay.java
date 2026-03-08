package io.github.karlmendoo.spoticraft.ui;

import io.github.karlmendoo.spoticraft.youtube.YouTubeService;
import io.github.karlmendoo.spoticraft.youtube.model.PlaybackSnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.ToastMessage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public final class YouTubeOverlay {
    private static final int MINI_PLAYER_MARGIN = 16;
    private static final int MINI_PLAYER_WIDTH = 222;
    private static final int MINI_PLAYER_HEIGHT = 64;
    private static final int TOAST_Y_OFFSET = MINI_PLAYER_HEIGHT + 80;

    private final YouTubeService service;
    private final AlbumArtCache albumArtCache;

    public YouTubeOverlay(YouTubeService service, AlbumArtCache albumArtCache) {
        this.service = service;
        this.albumArtCache = albumArtCache;
    }

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!this.service.config().overlayEnabled) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) {
            return;
        }

        PlaybackSnapshot playback = this.service.playback();
        if (playback.hasActiveDevice()) {
            renderMiniPlayer(context, client, playback);
        }

        ToastMessage toast = this.service.toastMessage();
        if (toast == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (toast.isExpired(now)) {
            this.service.clearToast();
            return;
        }

        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        float progress = 1.0F - (float) (now - toast.createdAtMs()) / toast.durationMs();
        int cardWidth = 210;
        int x = width - cardWidth - MINI_PLAYER_MARGIN;
        int y = height - TOAST_Y_OFFSET - (int) (MathHelper.clamp(progress, 0.0F, 1.0F) * 8.0F);
        int alpha = MathHelper.clamp(this.service.config().overlayOpacity, 0, 255);
        int background = alpha << 24 | 0x101114;
        context.fill(x, y, x + cardWidth, y + 56, background);
        context.fill(x, y, x + cardWidth, y + 2, 0xFFFF4343);

        Identifier art = this.albumArtCache.request(toast.imageUrl());
        if (art != null) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, art, x + 10, y + 10, 0, 0, 36, 36, 36, 36);
        } else {
            context.fill(x + 10, y + 10, x + 46, y + 46, 0x553B4450);
            context.drawText(client.textRenderer, Text.literal("▶"), x + 23, y + 22, 0xFFFFFFFF, false);
        }

        String toastTitle = toast.title() != null ? toast.title() : "";
        String toastSubtitle = toast.subtitle() != null ? toast.subtitle() : "";
        context.drawText(client.textRenderer, Text.literal("Now Playing"), x + 56, y + 10, 0xFFFFB7B7, false);
        context.drawText(client.textRenderer, client.textRenderer.trimToWidth(toastTitle, 136), x + 56, y + 24, 0xFFFFFFFF, false);
        context.drawText(client.textRenderer, client.textRenderer.trimToWidth(toastSubtitle, 136), x + 56, y + 37, 0xFFB7BEC9, false);
    }

    private void renderMiniPlayer(DrawContext context, MinecraftClient client, PlaybackSnapshot playback) {
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        int cardWidth = MINI_PLAYER_WIDTH;
        int cardHeight = MINI_PLAYER_HEIGHT;
        int x = width - cardWidth - MINI_PLAYER_MARGIN;
        int y = height - cardHeight - MINI_PLAYER_MARGIN;
        int alpha = MathHelper.clamp(this.service.config().overlayOpacity, 0, 255);
        int background = alpha << 24 | 0x101114;
        context.fill(x, y, x + cardWidth, y + cardHeight, background);
        context.fill(x, y, x + cardWidth, y + 2, playback.isPlaying() ? 0xFFFF4343 : 0xFFB7BEC9);

        Identifier art = this.albumArtCache.request(playback.imageUrl(), playback.trackId());
        if (art != null) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, art, x + 8, y + 8, 0, 0, 40, 40, 40, 40);
        } else {
            context.fill(x + 8, y + 8, x + 48, y + 48, 0x553B4450);
            context.drawText(client.textRenderer, Text.literal(playback.isPlaying() ? "▶" : "❚❚"), x + 22, y + 20, 0xFFFFFFFF, false);
        }

        int textX = x + 56;
        int progress = this.service.renderedProgressMs();
        context.drawText(client.textRenderer, client.textRenderer.trimToWidth(playback.title(), 152), textX, y + 8, 0xFFFFFFFF, false);
        context.drawText(
            client.textRenderer,
            client.textRenderer.trimToWidth(playback.artist() + " · " + playback.state().label(), 152),
            textX,
            y + 22,
            0xFFB7BEC9,
            false
        );
        context.fill(textX, y + 42, x + cardWidth - 8, y + 46, 0x44323A46);
        int progressWidth = cardWidth - 64;
        int filled = (int) ((progress / (float) Math.max(1, playback.durationMs())) * progressWidth);
        context.fill(textX, y + 42, textX + filled, y + 46, 0xFFFF4343);
        context.drawText(
            client.textRenderer,
            Text.literal(formatMillis(progress) + " / " + formatMillis(playback.durationMs())),
            textX,
            y + 50,
            0xFFE5EAF1,
            false
        );
    }

    private static String formatMillis(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }
}
