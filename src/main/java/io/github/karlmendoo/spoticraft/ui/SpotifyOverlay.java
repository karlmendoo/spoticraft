package io.github.karlmendoo.spoticraft.ui;

import io.github.karlmendoo.spoticraft.spotify.SpotifyService;
import io.github.karlmendoo.spoticraft.spotify.model.ToastMessage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public final class SpotifyOverlay {
    private final SpotifyService service;
    private final AlbumArtCache albumArtCache;

    public SpotifyOverlay(SpotifyService service, AlbumArtCache albumArtCache) {
        this.service = service;
        this.albumArtCache = albumArtCache;
    }

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!this.service.config().overlayEnabled) {
            return;
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

        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        float progress = 1.0F - (float) (now - toast.createdAtMs()) / toast.durationMs();
        int cardWidth = 210;
        int x = width - cardWidth - 16;
        int y = height - 72 - (int) (MathHelper.clamp(progress, 0.0F, 1.0F) * 8.0F);
        int alpha = MathHelper.clamp(this.service.config().overlayOpacity, 0, 255);
        int background = alpha << 24 | 0x101114;
        context.fill(x, y, x + cardWidth, y + 56, background);
        context.fill(x, y, x + cardWidth, y + 2, 0xFF1DB954);

        Identifier art = this.albumArtCache.request(toast.imageUrl());
        if (art != null) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, art, x + 10, y + 10, 0, 0, 36, 36, 36, 36);
        } else {
            context.fill(x + 10, y + 10, x + 46, y + 46, 0x5524D761);
            context.drawText(client.textRenderer, Text.literal("♪"), x + 23, y + 22, 0xFFFFFFFF, false);
        }

        context.drawText(client.textRenderer, Text.literal("Now Playing"), x + 56, y + 10, 0xFF9DE3B6, false);
        context.drawText(client.textRenderer, client.textRenderer.trimToWidth(toast.title(), 136), x + 56, y + 24, 0xFFFFFFFF, false);
        context.drawText(client.textRenderer, client.textRenderer.trimToWidth(toast.subtitle(), 136), x + 56, y + 37, 0xFFB7BEC9, false);
    }
}
