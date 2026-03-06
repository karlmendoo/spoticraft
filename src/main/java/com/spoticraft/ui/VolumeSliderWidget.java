package com.spoticraft.ui;

import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.config.SpotiCraftConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Volume control slider widget.
 */
public class VolumeSliderWidget {

    private final int x, y, w, h;
    private boolean dragging = false;

    public VolumeSliderWidget(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void render(DrawContext context, int mouseX, int mouseY, TextRenderer tr, int volumePercent) {
        // Label
        context.drawText(tr, "Volume", x, y, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);

        int barY = y + 14;
        int barH = 4;

        // Track
        context.fill(x, barY, x + w, barY + barH, 0xFF333333);

        // Fill
        int fillW = (int) (w * (volumePercent / 100.0f));
        context.fill(x, barY, x + fillW, barY + barH, SpotiCraftConfig.COLOR_ACCENT);

        // Knob
        context.fill(x + fillW - 4, barY - 3, x + fillW + 4, barY + barH + 3, SpotiCraftConfig.COLOR_TEXT);

        // Percentage label
        context.drawText(tr, volumePercent + "%", x + w + 6, barY, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (mouseX >= x && mouseX <= x + w && mouseY >= y + 10 && mouseY <= y + 22) {
            float fraction = (float) (mouseX - x) / w;
            SpotifyAPI.getInstance().setVolume(Math.round(fraction * 100));
            dragging = true;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (!dragging || button != 0) return false;
        float fraction = (float) Math.max(0, Math.min(mouseX - x, w)) / w;
        SpotifyAPI.getInstance().setVolume(Math.round(fraction * 100));
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) dragging = false;
        return false;
    }
}
