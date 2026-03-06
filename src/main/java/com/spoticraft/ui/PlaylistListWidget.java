package com.spoticraft.ui;

import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.SpotifyAuth;
import com.spoticraft.api.models.Playlist;
import com.spoticraft.api.models.Track;
import com.spoticraft.config.SpotiCraftConfig;
import com.spoticraft.util.AlbumArtCache;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable playlist browser. Shows user playlists on the left,
 * track list on the right when a playlist is selected.
 */
public class PlaylistListWidget {

    private final int x, y, w, h;
    private final SpotiCraftScreen parent;

    private List<Playlist> playlists = new ArrayList<>();
    private List<Track> tracks = new ArrayList<>();
    private String selectedPlaylistId = null;
    private String selectedPlaylistName = "";

    // View state: "playlists" or "tracks"
    private String view = "playlists";

    private float scrollOffset = 0;
    private float tracksScrollOffset = 0;
    private boolean loading = false;

    private static final int ROW_HEIGHT = 36;
    private static final int PADDING = 8;

    public PlaylistListWidget(int x, int y, int w, int h, SpotiCraftScreen parent) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.parent = parent;
    }

    public void loadPlaylists() {
        if (!SpotifyAuth.getInstance().isAuthenticated()) return;
        loading = true;
        playlists.clear();
        SpotifyAPI.getInstance().getUserPlaylists(50, 0)
                .thenAccept(result -> {
                    playlists = result;
                    loading = false;
                });
    }

    public void openPlaylist(String id, String name) {
        selectedPlaylistId = id;
        selectedPlaylistName = name;
        view = "tracks";
        tracksScrollOffset = 0;
        tracks.clear();
        loading = true;
        SpotifyAPI.getInstance().getPlaylistTracks(id, 100, 0)
                .thenAccept(result -> {
                    tracks = result;
                    loading = false;
                });
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer tr) {
        // Background
        context.fill(x, y, x + w, y + h, SpotiCraftConfig.COLOR_BG);

        if (!SpotifyAuth.getInstance().isAuthenticated()) {
            context.drawCenteredTextWithShadow(tr, "Connect to Spotify to see your playlists.",
                    x + w / 2, y + h / 2 - 4, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        if (loading) {
            context.drawCenteredTextWithShadow(tr, "Loading...", x + w / 2, y + h / 2 - 4,
                    SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        if ("tracks".equals(view)) {
            renderTrackList(context, mouseX, mouseY, tr);
        } else {
            renderPlaylistList(context, mouseX, mouseY, tr);
        }
    }

    private void renderPlaylistList(DrawContext context, int mouseX, int mouseY, TextRenderer tr) {
        // Header
        context.fill(x, y, x + w, y + 28, 0xCC0D0D0D);
        context.drawText(tr, "Your Playlists", x + PADDING, y + 10, SpotiCraftConfig.COLOR_TEXT, false);

        if (playlists.isEmpty()) {
            context.drawCenteredTextWithShadow(tr, "No playlists found.",
                    x + w / 2, y + h / 2, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        int listY = y + 28;
        int listH = h - 28;
        context.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - (int) scrollOffset;
        for (Playlist p : playlists) {
            if (rowY + ROW_HEIGHT < listY) { rowY += ROW_HEIGHT; continue; }
            if (rowY > listY + listH) break;

            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) context.fill(x, rowY, x + w, rowY + ROW_HEIGHT, SpotiCraftConfig.COLOR_BG_HOVER);
            context.fill(x, rowY + ROW_HEIGHT - 1, x + w, rowY + ROW_HEIGHT, 0xFF1A1A1A);

            // Playlist image
            int imgSize = ROW_HEIGHT - 6;
            int imgX = x + PADDING;
            int imgY = rowY + 3;
            if (p.imageUrl != null && !p.imageUrl.isBlank()) {
                Identifier tex = AlbumArtCache.getInstance().get(p.imageUrl);
                if (tex != null) {
                    context.drawTexture(tex, imgX, imgY, 0, 0, imgSize, imgSize, imgSize, imgSize);
                } else {
                    context.fill(imgX, imgY, imgX + imgSize, imgY + imgSize, 0xFF1A1A1A);
                    context.drawCenteredTextWithShadow(tr, "♫", imgX + imgSize / 2, imgY + imgSize / 2 - 4, SpotiCraftConfig.COLOR_ACCENT);
                }
            } else {
                context.fill(imgX, imgY, imgX + imgSize, imgY + imgSize, 0xFF1A1A1A);
            }

            int textX = imgX + imgSize + 6;
            int textW = w - imgSize - PADDING * 2 - 6;
            String nameStr = truncate(tr, p.name, textW);
            context.drawText(tr, nameStr, textX, rowY + 7, SpotiCraftConfig.COLOR_TEXT, false);
            String sub = p.trackCount + " tracks  •  " + (p.ownerName != null ? p.ownerName : "");
            context.drawText(tr, truncate(tr, sub, textW), textX, rowY + 20, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);

            rowY += ROW_HEIGHT;
        }
        context.disableScissor();
    }

    private void renderTrackList(DrawContext context, int mouseX, int mouseY, TextRenderer tr) {
        // Back button + header
        context.fill(x, y, x + w, y + 28, 0xCC0D0D0D);
        context.fill(x, y + 2, x + 40, y + 26, 0xFF1A1A1A);
        context.drawText(tr, "← Back", x + 6, y + 10, SpotiCraftConfig.COLOR_ACCENT, false);
        context.drawText(tr, selectedPlaylistName, x + 50, y + 10, SpotiCraftConfig.COLOR_TEXT, false);

        if (tracks.isEmpty()) {
            context.drawCenteredTextWithShadow(tr, "No tracks.",
                    x + w / 2, y + h / 2, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        int listY = y + 28;
        int listH = h - 28;
        int trackH = 26;
        context.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - (int) tracksScrollOffset;
        int index = 1;
        for (Track track : tracks) {
            if (rowY + trackH < listY) { rowY += trackH; index++; continue; }
            if (rowY > listY + listH) break;

            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + trackH;
            if (hovered) context.fill(x, rowY, x + w, rowY + trackH, SpotiCraftConfig.COLOR_BG_HOVER);
            context.fill(x, rowY + trackH - 1, x + w, rowY + trackH, 0xFF1A1A1A);

            // Track number
            context.drawText(tr, String.valueOf(index), x + PADDING, rowY + 9, 0xFF555555, false);

            int textX = x + PADDING + 20;
            int textW = w - PADDING * 2 - 80;
            context.drawText(tr, truncate(tr, track.name, textW), textX, rowY + 5, SpotiCraftConfig.COLOR_TEXT, false);
            context.drawText(tr, truncate(tr, track.getArtistString(), textW), textX, rowY + 15,
                    SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);

            // Duration
            String dur = formatMs(track.durationMs);
            context.drawText(tr, dur, x + w - PADDING - tr.getWidth(dur), rowY + 9, 0xFF555555, false);

            rowY += trackH;
            index++;
        }
        context.disableScissor();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        if ("tracks".equals(view)) {
            // Back button
            if (mouseX >= x && mouseX < x + 40 && mouseY >= y + 2 && mouseY < y + 26) {
                view = "playlists";
                return true;
            }
            // Click track to play
            int listY = y + 28;
            int trackH = 26;
            int rowY = listY - (int) tracksScrollOffset;
            for (Track track : tracks) {
                if (mouseY >= rowY && mouseY < rowY + trackH && mouseX >= x && mouseX < x + w) {
                    SpotifyAPI.getInstance().playUri(track.uri);
                    return true;
                }
                rowY += trackH;
            }
        } else {
            // Click playlist to open
            int listY = y + 28;
            int rowY = listY - (int) scrollOffset;
            for (Playlist p : playlists) {
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX < x + w) {
                    openPlaylist(p.id, p.name);
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if ("tracks".equals(view)) {
            tracksScrollOffset = Math.max(0, tracksScrollOffset - (float) amount * 10);
        } else {
            scrollOffset = Math.max(0, scrollOffset - (float) amount * 10);
        }
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
