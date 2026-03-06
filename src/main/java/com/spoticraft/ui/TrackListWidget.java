package com.spoticraft.ui;

import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.models.Track;
import com.spoticraft.config.SpotiCraftConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Generic scrollable list of tracks. Reused for playlists, albums, search results.
 */
public class TrackListWidget {

    private final int x, y, w, h;
    private List<Track> tracks;

    private float scrollOffset = 0;
    private static final int ROW_HEIGHT = 28;
    private static final int PADDING = 8;

    public TrackListWidget(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void setTracks(List<Track> tracks) {
        this.tracks = tracks;
        this.scrollOffset = 0;
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer tr) {
        if (tracks == null || tracks.isEmpty()) {
            context.drawCenteredTextWithShadow(tr, "No tracks.", x + w / 2, y + h / 2 - 4,
                    SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        context.enableScissor(x, y, x + w, y + h);
        int rowY = y - (int) scrollOffset;

        for (int i = 0; i < tracks.size(); i++) {
            if (rowY + ROW_HEIGHT < y) { rowY += ROW_HEIGHT; continue; }
            if (rowY > y + h) break;

            Track track = tracks.get(i);
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            if (hovered) context.fill(x, rowY, x + w, rowY + ROW_HEIGHT, SpotiCraftConfig.COLOR_BG_HOVER);
            context.fill(x, rowY + ROW_HEIGHT - 1, x + w, rowY + ROW_HEIGHT, 0xFF1A1A1A);

            // Index
            context.drawText(tr, String.valueOf(i + 1), x + PADDING, rowY + 10, 0xFF555555, false);

            // Track name & artist
            int textX = x + PADDING + 22;
            int textW = w - PADDING * 2 - 80;
            context.drawText(tr, truncate(tr, track.name, textW), textX, rowY + 5, SpotiCraftConfig.COLOR_TEXT, false);
            context.drawText(tr, truncate(tr, track.getArtistString(), textW), textX, rowY + 17,
                    SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);

            // Duration
            String dur = formatMs(track.durationMs);
            context.drawText(tr, dur, x + w - PADDING - tr.getWidth(dur), rowY + 10, 0xFF555555, false);

            rowY += ROW_HEIGHT;
        }
        context.disableScissor();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || tracks == null) return false;

        int rowY = y - (int) scrollOffset;
        for (Track track : tracks) {
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX < x + w) {
                SpotifyAPI.getInstance().playUri(track.uri);
                return true;
            }
            rowY += ROW_HEIGHT;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset = Math.max(0, scrollOffset - (float) amount * 10);
        return true;
    }

    private String truncate(TextRenderer tr, String text, int maxW) {
        if (text == null) return "";
        if (tr.getWidth(text) <= maxW) return text;
        while (text.length() > 0 && tr.getWidth(text + "…") > maxW) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    private String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
