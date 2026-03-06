package com.spoticraft.ui;

import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.SpotifyAuth;
import com.spoticraft.api.models.SearchResult;
import com.spoticraft.api.models.Track;
import com.spoticraft.api.models.Playlist;
import com.spoticraft.api.models.Album;
import com.spoticraft.api.models.Artist;
import com.spoticraft.config.SpotiCraftConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Search widget with debounced input and categorized results.
 */
public class SearchWidget {

    private final int x, y, w, h;
    private final SpotiCraftScreen parent;

    private String searchQuery = "";
    private SearchResult results = null;
    private boolean loading = false;
    private int debounceTimer = 0;
    private static final int DEBOUNCE_TICKS = 20; // ~1 second

    private float scrollOffset = 0;
    private boolean searchBarFocused = false;

    private static final int SEARCH_BAR_HEIGHT = 28;
    private static final int ROW_HEIGHT = 26;
    private static final int PADDING = 10;

    public SearchWidget(int x, int y, int w, int h, SpotiCraftScreen parent) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.parent = parent;
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer tr) {
        context.fill(x, y, x + w, y + h, SpotiCraftConfig.COLOR_BG);

        // Search bar
        int sbY = y + PADDING;
        int sbX = x + PADDING;
        int sbW = w - PADDING * 2;

        context.fill(sbX, sbY, sbX + sbW, sbY + SEARCH_BAR_HEIGHT, 0xFF1A1A1A);
        int borderColor = searchBarFocused ? SpotiCraftConfig.COLOR_ACCENT : 0xFF333333;
        drawBorder(context, sbX, sbY, sbW, SEARCH_BAR_HEIGHT, borderColor);

        String displayQuery = searchBarFocused && (System.currentTimeMillis() / 500) % 2 == 0
                ? searchQuery + "│" : searchQuery;
        if (searchQuery.isEmpty() && !searchBarFocused) {
            context.drawText(tr, "Search songs, albums, artists, playlists...",
                    sbX + 8, sbY + (SEARCH_BAR_HEIGHT - 8) / 2, 0xFF555555, false);
        } else {
            context.drawText(tr, displayQuery, sbX + 8, sbY + (SEARCH_BAR_HEIGHT - 8) / 2,
                    SpotiCraftConfig.COLOR_TEXT, false);
        }

        // Results area
        int resultsY = sbY + SEARCH_BAR_HEIGHT + 8;
        int resultsH = h - SEARCH_BAR_HEIGHT - PADDING * 3;

        if (!SpotifyAuth.getInstance().isAuthenticated()) {
            context.drawCenteredTextWithShadow(tr, "Connect to Spotify to search.",
                    x + w / 2, resultsY + resultsH / 2, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        if (loading) {
            context.drawCenteredTextWithShadow(tr, "Searching...",
                    x + w / 2, resultsY + resultsH / 2, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        if (results == null) {
            context.drawCenteredTextWithShadow(tr, "Start typing to search Spotify",
                    x + w / 2, resultsY + resultsH / 2, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            return;
        }

        context.enableScissor(x, resultsY, x + w, resultsY + resultsH);
        int rowY = resultsY - (int) scrollOffset;

        rowY = renderSection(context, tr, mouseX, mouseY, "Songs", rowY,
                results.tracks != null ? results.tracks.stream().map(t -> (Object) t).toList() : List.of(), "track");
        rowY = renderSection(context, tr, mouseX, mouseY, "Albums", rowY,
                results.albums != null ? results.albums.stream().map(a -> (Object) a).toList() : List.of(), "album");
        rowY = renderSection(context, tr, mouseX, mouseY, "Artists", rowY,
                results.artists != null ? results.artists.stream().map(a -> (Object) a).toList() : List.of(), "artist");
        rowY = renderSection(context, tr, mouseX, mouseY, "Playlists", rowY,
                results.playlists != null ? results.playlists.stream().map(p -> (Object) p).toList() : List.of(), "playlist");

        context.disableScissor();
    }

    private int renderSection(DrawContext context, TextRenderer tr, int mouseX, int mouseY,
                               String sectionTitle, int rowY, List<Object> items, String type) {
        if (items.isEmpty()) return rowY;

        // Section header
        context.fill(x, rowY, x + w, rowY + 18, 0xCC0D0D0D);
        context.drawText(tr, sectionTitle, x + PADDING, rowY + 5, SpotiCraftConfig.COLOR_ACCENT, false);
        rowY += 18;

        for (Object item : items) {
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (hovered) context.fill(x, rowY, x + w, rowY + ROW_HEIGHT, SpotiCraftConfig.COLOR_BG_HOVER);
            context.fill(x, rowY + ROW_HEIGHT - 1, x + w, rowY + ROW_HEIGHT, 0xFF1A1A1A);

            String name = "";
            String sub = "";
            if (item instanceof Track t) {
                name = t.name;
                sub = t.getArtistString() + (t.albumName != null && !t.albumName.isEmpty() ? " • " + t.albumName : "");
            } else if (item instanceof Album a) {
                name = a.name;
                sub = a.getArtistString() + " • " + a.totalTracks + " tracks";
            } else if (item instanceof Artist a) {
                name = a.name;
                sub = a.followers > 0 ? a.followers + " followers" : "";
            } else if (item instanceof Playlist p) {
                name = p.name;
                sub = p.ownerName != null ? "by " + p.ownerName : "";
            }

            int textW = w - PADDING * 2;
            context.drawText(tr, truncate(tr, name, textW), x + PADDING, rowY + 4, SpotiCraftConfig.COLOR_TEXT, false);
            context.drawText(tr, truncate(tr, sub, textW), x + PADDING, rowY + 15,
                    SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);

            rowY += ROW_HEIGHT;
        }
        rowY += 4; // spacing between sections
        return rowY;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Search bar focus
        int sbY = y + PADDING;
        int sbX = x + PADDING;
        int sbW = w - PADDING * 2;
        if (mouseX >= sbX && mouseX <= sbX + sbW && mouseY >= sbY && mouseY <= sbY + SEARCH_BAR_HEIGHT) {
            searchBarFocused = true;
            return true;
        }
        searchBarFocused = false;

        // Click on search results
        if (results == null) return false;

        int resultsY = sbY + SEARCH_BAR_HEIGHT + 8;
        int rowY = resultsY - (int) scrollOffset;

        // Tracks
        if (results.tracks != null) {
            rowY += 18; // section header
            for (Track t : results.tracks) {
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX < x + w) {
                    SpotifyAPI.getInstance().playUri(t.uri);
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
            rowY += 4;
        }

        // Albums
        if (results.albums != null) {
            rowY += 18;
            for (Album a : results.albums) {
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX < x + w) {
                    SpotifyAPI.getInstance().playUri(a.uri);
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
            rowY += 4;
        }

        // Artists  
        if (results.artists != null) {
            rowY += 18;
            for (Artist a : results.artists) {
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX < x + w) {
                    SpotifyAPI.getInstance().playUri(a.uri);
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
            rowY += 4;
        }

        // Playlists
        if (results.playlists != null) {
            rowY += 18;
            for (Playlist p : results.playlists) {
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX < x + w) {
                    SpotifyAPI.getInstance().playUri(p.uri);
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
        }

        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        scrollOffset = Math.max(0, scrollOffset - (float) amount * 10);
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!searchBarFocused) return false;
        if (keyCode == 259) { // Backspace
            if (!searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                debounceTimer = DEBOUNCE_TICKS;
            }
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (!searchBarFocused) return false;
        if (chr >= 32 && chr != 127) {
            searchQuery += chr;
            debounceTimer = DEBOUNCE_TICKS;
            return true;
        }
        return false;
    }

    public void tick() {
        if (debounceTimer > 0) {
            debounceTimer--;
            if (debounceTimer == 0 && !searchQuery.isBlank()) {
                performSearch();
            }
        }
    }

    private void performSearch() {
        loading = true;
        results = null;
        scrollOffset = 0;
        SpotifyAPI.getInstance().search(searchQuery, 10)
                .thenAccept(result -> {
                    results = result;
                    loading = false;
                });
    }

    private String truncate(TextRenderer tr, String text, int maxW) {
        if (text == null) return "";
        if (tr.getWidth(text) <= maxW) return text;
        while (text.length() > 0 && tr.getWidth(text + "…") > maxW) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    private void drawBorder(DrawContext ctx, int bx, int by, int bw, int bh, int color) {
        ctx.fill(bx, by, bx + bw, by + 1, color);
        ctx.fill(bx, by + bh - 1, bx + bw, by + bh, color);
        ctx.fill(bx, by, bx + 1, by + bh, color);
        ctx.fill(bx + bw - 1, by, bx + bw, by + bh, color);
    }
}
