package io.github.karlmendoo.spoticraft.ui.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAPI;
import io.github.karlmendoo.spoticraft.ui.widget.SpotiCraftButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Browse screen for viewing playlists, liked songs, and recently played tracks.
 */
public class SpotifyBrowseScreen extends Screen {
    private final Screen parent;

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 240;
    private static final int ACCENT_COLOR = 0xFF1DB954;
    private static final int PANEL_COLOR = 0xE0222222;
    private static final int TEXT_COLOR = 0xFFEEEEEE;
    private static final int DIM_TEXT = 0xFF999999;
    private static final int HOVER_COLOR = 0xFF333333;
    private static final int ITEM_HEIGHT = 18;

    private enum Tab { PLAYLISTS, LIKED, RECENT }
    private Tab currentTab = Tab.PLAYLISTS;

    private List<SpotifyAPI.PlaylistInfo> playlists = new ArrayList<>();
    private List<SpotifyAPI.TrackInfo> likedSongs = new ArrayList<>();
    private List<SpotifyAPI.TrackInfo> recentTracks = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean loading = false;

    public SpotifyBrowseScreen(Screen parent) {
        super(Text.literal("Browse"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Tab buttons
        int tabY = panelY + 18;
        int tabWidth = 70;

        this.addDrawableChild(new SpotiCraftButton(panelX + 20, tabY, tabWidth, 14,
                Text.literal("Playlists"), b -> { currentTab = Tab.PLAYLISTS; scrollOffset = 0; loadData(); }));
        this.addDrawableChild(new SpotiCraftButton(panelX + 95, tabY, tabWidth, 14,
                Text.literal("Liked"), b -> { currentTab = Tab.LIKED; scrollOffset = 0; loadData(); }));
        this.addDrawableChild(new SpotiCraftButton(panelX + 170, tabY, tabWidth, 14,
                Text.literal("Recent"), b -> { currentTab = Tab.RECENT; scrollOffset = 0; loadData(); }));

        // Back button
        this.addDrawableChild(new SpotiCraftButton(panelX + PANEL_WIDTH - 42, panelY + 4, 38, 12,
                Text.literal("\u00A77\u2190 Back"), b -> this.close()));

        loadData();
    }

    private void loadData() {
        loading = true;
        SpotifyAPI api = SpotiCraftClient.getApi();

        switch (currentTab) {
            case PLAYLISTS -> api.getUserPlaylists(20, 0).thenAccept(resp -> {
                if (resp != null) {
                    playlists = SpotifyAPI.parsePlaylists(resp);
                }
                loading = false;
            });
            case LIKED -> api.getLikedSongs(20, 0).thenAccept(resp -> {
                if (resp != null) {
                    likedSongs = parseSavedTracks(resp);
                }
                loading = false;
            });
            case RECENT -> api.getRecentlyPlayed(20).thenAccept(resp -> {
                if (resp != null) {
                    recentTracks = parseRecentTracks(resp);
                }
                loading = false;
            });
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xA0000000);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Panel
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, PANEL_COLOR);
        drawBorder(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, ACCENT_COLOR);

        // Title
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7l\u00A7aBrowse"), panelX + 10, panelY + 6, TEXT_COLOR);

        // Content area
        int contentY = panelY + 38;
        int contentHeight = PANEL_HEIGHT - 46;
        int maxVisible = contentHeight / ITEM_HEIGHT;

        if (loading) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("\u00A77Loading..."),
                    this.width / 2, panelY + PANEL_HEIGHT / 2, DIM_TEXT);
        } else {
            switch (currentTab) {
                case PLAYLISTS -> renderPlaylists(context, mouseX, mouseY, panelX, contentY, maxVisible);
                case LIKED -> renderTracks(context, mouseX, mouseY, panelX, contentY, maxVisible, likedSongs);
                case RECENT -> renderTracks(context, mouseX, mouseY, panelX, contentY, maxVisible, recentTracks);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderPlaylists(DrawContext context, int mouseX, int mouseY, int panelX, int startY, int maxVisible) {
        for (int i = 0; i < maxVisible && i + scrollOffset < playlists.size(); i++) {
            SpotifyAPI.PlaylistInfo playlist = playlists.get(i + scrollOffset);
            int itemY = startY + i * ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            boolean hovered = mouseX >= panelX + 10 && mouseX <= endX && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            if (hovered) {
                context.fill(panelX + 10, itemY, endX, itemY + ITEM_HEIGHT, HOVER_COLOR);
            }

            String name = playlist.name();
            if (name.length() > 38) name = name.substring(0, 35) + "...";
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7f" + name), panelX + 14, itemY + 4, TEXT_COLOR);

            String info = playlist.totalTracks() + " tracks";
            int infoWidth = this.textRenderer.getWidth(info);
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + info), endX - infoWidth - 4, itemY + 4, DIM_TEXT);
        }
    }

    private void renderTracks(DrawContext context, int mouseX, int mouseY, int panelX, int startY, int maxVisible, List<SpotifyAPI.TrackInfo> tracks) {
        for (int i = 0; i < maxVisible && i + scrollOffset < tracks.size(); i++) {
            SpotifyAPI.TrackInfo track = tracks.get(i + scrollOffset);
            int itemY = startY + i * ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            boolean hovered = mouseX >= panelX + 10 && mouseX <= endX && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            if (hovered) {
                context.fill(panelX + 10, itemY, endX, itemY + ITEM_HEIGHT, HOVER_COLOR);
            }

            String displayName = track.name();
            if (displayName.length() > 28) displayName = displayName.substring(0, 25) + "...";
            String displayArtist = track.artist();
            if (displayArtist.length() > 18) displayArtist = displayArtist.substring(0, 15) + "...";

            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A7f" + displayName), panelX + 14, itemY + 4, TEXT_COLOR);
            int artistWidth = this.textRenderer.getWidth(displayArtist);
            context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + displayArtist), endX - artistWidth - 4, itemY + 4, DIM_TEXT);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int panelX = (this.width - PANEL_WIDTH) / 2;
            int panelY = (this.height - PANEL_HEIGHT) / 2;
            int contentY = panelY + 38;
            int contentHeight = PANEL_HEIGHT - 46;
            int maxVisible = contentHeight / ITEM_HEIGHT;
            int endX = panelX + PANEL_WIDTH - 10;

            if (mouseX >= panelX + 10 && mouseX <= endX && mouseY >= contentY) {
                int index = (int) ((mouseY - contentY) / ITEM_HEIGHT) + scrollOffset;

                switch (currentTab) {
                    case PLAYLISTS -> {
                        if (index >= 0 && index < playlists.size()) {
                            SpotiCraftClient.getApi().playContext(playlists.get(index).uri());
                        }
                    }
                    case LIKED -> {
                        if (index >= 0 && index < likedSongs.size()) {
                            SpotiCraftClient.getApi().playTrack(likedSongs.get(index).uri());
                        }
                    }
                    case RECENT -> {
                        if (index >= 0 && index < recentTracks.size()) {
                            SpotiCraftClient.getApi().playTrack(recentTracks.get(index).uri());
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
            case PLAYLISTS -> playlists.size();
            case LIKED -> likedSongs.size();
            case RECENT -> recentTracks.size();
        };
        int contentHeight = PANEL_HEIGHT - 46;
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

    private List<SpotifyAPI.TrackInfo> parseSavedTracks(JsonObject response) {
        List<SpotifyAPI.TrackInfo> tracks = new ArrayList<>();
        if (!response.has("items")) return tracks;
        for (JsonElement item : response.getAsJsonArray("items")) {
            JsonObject obj = item.getAsJsonObject();
            if (obj.has("track")) {
                SpotifyAPI.TrackInfo track = SpotifyAPI.parseTrack(obj.getAsJsonObject("track"));
                if (track != null) tracks.add(track);
            }
        }
        return tracks;
    }

    private List<SpotifyAPI.TrackInfo> parseRecentTracks(JsonObject response) {
        List<SpotifyAPI.TrackInfo> tracks = new ArrayList<>();
        if (!response.has("items")) return tracks;
        for (JsonElement item : response.getAsJsonArray("items")) {
            JsonObject obj = item.getAsJsonObject();
            if (obj.has("track")) {
                SpotifyAPI.TrackInfo track = SpotifyAPI.parseTrack(obj.getAsJsonObject("track"));
                if (track != null) tracks.add(track);
            }
        }
        return tracks;
    }

    private void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y, x + 1, y + h, color);
        context.fill(x + w - 1, y, x + w, y + h, color);
    }
}
