package io.github.karlmendoo.spoticraft.ui.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAPI;
import io.github.karlmendoo.spoticraft.ui.widget.SpotiCraftButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Search screen for finding songs, albums, artists, and playlists.
 */
public class SpotifySearchScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget searchField;

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 250;
    private static final int ACCENT_COLOR = 0xFF1DB954;
    private static final int PANEL_COLOR = 0xE0222222;
    private static final int TEXT_COLOR = 0xFFEEEEEE;
    private static final int DIM_TEXT = 0xFF999999;
    private static final int HOVER_COLOR = 0xFF333333;
    private static final int ITEM_HEIGHT = 18;

    private enum Tab { TRACKS, ALBUMS, ARTISTS, PLAYLISTS }
    private Tab currentTab = Tab.TRACKS;

    private List<SpotifyAPI.TrackInfo> trackResults = new ArrayList<>();
    private List<AlbumResult> albumResults = new ArrayList<>();
    private List<ArtistResult> artistResults = new ArrayList<>();
    private List<SpotifyAPI.PlaylistInfo> playlistResults = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean loading = false;
    private String lastQuery = "";

    public SpotifySearchScreen(Screen parent) {
        super(Text.literal("Search"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Search field
        searchField = new TextFieldWidget(this.textRenderer,
                panelX + 10, panelY + 20, PANEL_WIDTH - 80, 16,
                Text.literal("Search"));
        searchField.setMaxLength(100);
        searchField.setPlaceholder(Text.literal("Search Spotify..."));
        this.addDrawableChild(searchField);

        // Search button
        this.addDrawableChild(new SpotiCraftButton(
                panelX + PANEL_WIDTH - 64, panelY + 20, 54, 16,
                Text.literal("Search"),
                b -> performSearch()
        ));

        // Tab buttons
        int tabY = panelY + 42;
        int tabW = 55;
        this.addDrawableChild(new SpotiCraftButton(panelX + 10, tabY, tabW, 14,
                Text.literal("Tracks"), b -> { currentTab = Tab.TRACKS; scrollOffset = 0; }));
        this.addDrawableChild(new SpotiCraftButton(panelX + 70, tabY, tabW, 14,
                Text.literal("Albums"), b -> { currentTab = Tab.ALBUMS; scrollOffset = 0; }));
        this.addDrawableChild(new SpotiCraftButton(panelX + 130, tabY, tabW, 14,
                Text.literal("Artists"), b -> { currentTab = Tab.ARTISTS; scrollOffset = 0; }));
        this.addDrawableChild(new SpotiCraftButton(panelX + 190, tabY, 62, 14,
                Text.literal("Playlists"), b -> { currentTab = Tab.PLAYLISTS; scrollOffset = 0; }));

        // Back button
        this.addDrawableChild(new SpotiCraftButton(panelX + PANEL_WIDTH - 42, panelY + 4, 38, 12,
                Text.literal("\u00A77\u2190 Back"), b -> this.close()));
    }

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty() || query.equals(lastQuery)) return;
        lastQuery = query;
        loading = true;
        scrollOffset = 0;

        SpotiCraftClient.getApi().search(query, "track,album,artist,playlist", 15).thenAccept(resp -> {
            if (resp != null) {
                trackResults = parseSearchTracks(resp);
                albumResults = parseSearchAlbums(resp);
                artistResults = parseSearchArtists(resp);
                playlistResults = parseSearchPlaylists(resp);
            }
            loading = false;
        });
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchField.isFocused() && keyCode == 257) { // Enter
            performSearch();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xA0000000);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, PANEL_COLOR);
        drawBorder(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, ACCENT_COLOR);

        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7l\u00A7aSearch"), panelX + 10, panelY + 6, TEXT_COLOR);

        // Content
        int contentY = panelY + 62;
        int contentHeight = PANEL_HEIGHT - 70;
        int maxVisible = contentHeight / ITEM_HEIGHT;

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("\u00A77Searching..."),
                    this.width / 2, panelY + PANEL_HEIGHT / 2, DIM_TEXT);
        } else if (lastQuery.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("\u00A78Type to search Spotify"),
                    this.width / 2, panelY + PANEL_HEIGHT / 2, DIM_TEXT);
        } else {
            switch (currentTab) {
                case TRACKS -> renderTrackResults(context, mouseX, mouseY, panelX, contentY, maxVisible);
                case ALBUMS -> renderAlbumResults(context, mouseX, mouseY, panelX, contentY, maxVisible);
                case ARTISTS -> renderArtistResults(context, mouseX, mouseY, panelX, contentY, maxVisible);
                case PLAYLISTS -> renderPlaylistResults(context, mouseX, mouseY, panelX, contentY, maxVisible);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderTrackResults(DrawContext context, int mouseX, int mouseY, int panelX, int startY, int maxVisible) {
        for (int i = 0; i < maxVisible && i + scrollOffset < trackResults.size(); i++) {
            SpotifyAPI.TrackInfo track = trackResults.get(i + scrollOffset);
            int itemY = startY + i * ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            boolean hovered = mouseX >= panelX + 10 && mouseX <= endX && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            if (hovered) context.fill(panelX + 10, itemY, endX, itemY + ITEM_HEIGHT, HOVER_COLOR);

            String name = truncate(track.name(), 28);
            String artist = truncate(track.artist(), 18);
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7f" + name), panelX + 14, itemY + 4, TEXT_COLOR);
            int aw = this.textRenderer.getWidth(artist);
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + artist), endX - aw - 4, itemY + 4, DIM_TEXT);
        }
    }

    private void renderAlbumResults(DrawContext context, int mouseX, int mouseY, int panelX, int startY, int maxVisible) {
        for (int i = 0; i < maxVisible && i + scrollOffset < albumResults.size(); i++) {
            AlbumResult album = albumResults.get(i + scrollOffset);
            int itemY = startY + i * ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            boolean hovered = mouseX >= panelX + 10 && mouseX <= endX && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            if (hovered) context.fill(panelX + 10, itemY, endX, itemY + ITEM_HEIGHT, HOVER_COLOR);

            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7f" + truncate(album.name, 28)), panelX + 14, itemY + 4, TEXT_COLOR);
            int aw = this.textRenderer.getWidth(album.artist);
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + truncate(album.artist, 18)), endX - aw - 4, itemY + 4, DIM_TEXT);
        }
    }

    private void renderArtistResults(DrawContext context, int mouseX, int mouseY, int panelX, int startY, int maxVisible) {
        for (int i = 0; i < maxVisible && i + scrollOffset < artistResults.size(); i++) {
            ArtistResult artist = artistResults.get(i + scrollOffset);
            int itemY = startY + i * ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            boolean hovered = mouseX >= panelX + 10 && mouseX <= endX && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            if (hovered) context.fill(panelX + 10, itemY, endX, itemY + ITEM_HEIGHT, HOVER_COLOR);

            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7f" + truncate(artist.name, 35)), panelX + 14, itemY + 4, TEXT_COLOR);
        }
    }

    private void renderPlaylistResults(DrawContext context, int mouseX, int mouseY, int panelX, int startY, int maxVisible) {
        for (int i = 0; i < maxVisible && i + scrollOffset < playlistResults.size(); i++) {
            SpotifyAPI.PlaylistInfo playlist = playlistResults.get(i + scrollOffset);
            int itemY = startY + i * ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            boolean hovered = mouseX >= panelX + 10 && mouseX <= endX && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            if (hovered) context.fill(panelX + 10, itemY, endX, itemY + ITEM_HEIGHT, HOVER_COLOR);

            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7f" + truncate(playlist.name(), 35)), panelX + 14, itemY + 4, TEXT_COLOR);
            String info = playlist.totalTracks() + " tracks";
            int iw = this.textRenderer.getWidth(info);
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + info), endX - iw - 4, itemY + 4, DIM_TEXT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int panelX = (this.width - PANEL_WIDTH) / 2;
            int panelY = (this.height - PANEL_HEIGHT) / 2;
            int contentY = panelY + 62;
            int endX = panelX + PANEL_WIDTH - 10;

            if (mouseX >= panelX + 10 && mouseX <= endX && mouseY >= contentY) {
                int index = (int) ((mouseY - contentY) / ITEM_HEIGHT) + scrollOffset;
                switch (currentTab) {
                    case TRACKS -> {
                        if (index >= 0 && index < trackResults.size()) {
                            SpotiCraftClient.getApi().playTrack(trackResults.get(index).uri());
                        }
                    }
                    case ALBUMS -> {
                        if (index >= 0 && index < albumResults.size()) {
                            SpotiCraftClient.getApi().playContext(albumResults.get(index).uri);
                        }
                    }
                    case ARTISTS -> {
                        if (index >= 0 && index < artistResults.size()) {
                            SpotiCraftClient.getApi().playContext(artistResults.get(index).uri);
                        }
                    }
                    case PLAYLISTS -> {
                        if (index >= 0 && index < playlistResults.size()) {
                            SpotiCraftClient.getApi().playContext(playlistResults.get(index).uri());
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxItems = switch (currentTab) {
            case TRACKS -> trackResults.size();
            case ALBUMS -> albumResults.size();
            case ARTISTS -> artistResults.size();
            case PLAYLISTS -> playlistResults.size();
        };
        int contentHeight = PANEL_HEIGHT - 70;
        int maxVisible = contentHeight / ITEM_HEIGHT;
        scrollOffset -= (int) verticalAmount;
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, maxItems - maxVisible)));
        return true;
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    // Parsing helpers
    private List<SpotifyAPI.TrackInfo> parseSearchTracks(JsonObject resp) {
        List<SpotifyAPI.TrackInfo> list = new ArrayList<>();
        if (!resp.has("tracks")) return list;
        JsonArray items = resp.getAsJsonObject("tracks").getAsJsonArray("items");
        for (JsonElement el : items) {
            SpotifyAPI.TrackInfo t = SpotifyAPI.parseTrack(el.getAsJsonObject());
            if (t != null) list.add(t);
        }
        return list;
    }

    private List<AlbumResult> parseSearchAlbums(JsonObject resp) {
        List<AlbumResult> list = new ArrayList<>();
        if (!resp.has("albums")) return list;
        for (JsonElement el : resp.getAsJsonObject("albums").getAsJsonArray("items")) {
            JsonObject obj = el.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
            String uri = obj.has("uri") ? obj.get("uri").getAsString() : "";
            StringBuilder artists = new StringBuilder();
            if (obj.has("artists")) {
                for (int i = 0; i < obj.getAsJsonArray("artists").size(); i++) {
                    if (i > 0) artists.append(", ");
                    artists.append(obj.getAsJsonArray("artists").get(i).getAsJsonObject().get("name").getAsString());
                }
            }
            list.add(new AlbumResult(name, artists.toString(), uri));
        }
        return list;
    }

    private List<ArtistResult> parseSearchArtists(JsonObject resp) {
        List<ArtistResult> list = new ArrayList<>();
        if (!resp.has("artists")) return list;
        for (JsonElement el : resp.getAsJsonObject("artists").getAsJsonArray("items")) {
            JsonObject obj = el.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
            String uri = obj.has("uri") ? obj.get("uri").getAsString() : "";
            list.add(new ArtistResult(name, uri));
        }
        return list;
    }

    private List<SpotifyAPI.PlaylistInfo> parseSearchPlaylists(JsonObject resp) {
        List<SpotifyAPI.PlaylistInfo> list = new ArrayList<>();
        if (!resp.has("playlists")) return list;
        return SpotifyAPI.parsePlaylists(resp.getAsJsonObject("playlists"));
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    private void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }

    private record AlbumResult(String name, String artist, String uri) {}
    private record ArtistResult(String name, String uri) {}
}
