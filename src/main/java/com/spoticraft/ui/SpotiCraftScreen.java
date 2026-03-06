package com.spoticraft.ui;

import com.spoticraft.SpotiCraftMod;
import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.SpotifyAuth;
import com.spoticraft.api.models.PlaybackState;
import com.spoticraft.config.SpotiCraftConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The main SpotiCraft Screen.
 * Layout: left sidebar (navigation) + right content area.
 * Sidebar tabs: Now Playing, Playlists, Search, Settings
 */
public class SpotiCraftScreen extends Screen {

    private static final int SIDEBAR_WIDTH = 130;
    private static final int HEADER_HEIGHT = 30;

    // Tab indices
    public static final int TAB_NOW_PLAYING = 0;
    public static final int TAB_PLAYLISTS   = 1;
    public static final int TAB_SEARCH      = 2;
    public static final int TAB_SETTINGS    = 3;

    private int currentTab = TAB_NOW_PLAYING;

    // Child widgets
    private NowPlayingWidget nowPlayingWidget;
    private PlaylistListWidget playlistWidget;
    private SearchWidget searchWidget;
    private SettingsWidget settingsWidget;

    // Status message (error/info)
    private String statusMessage = "";
    private int statusMessageTick = 0;

    // Playback polling
    private final ScheduledExecutorService pollScheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "SpotiCraft-Poll"); t.setDaemon(true); return t; }
    );

    public SpotiCraftScreen() {
        super(Text.translatable("spoticraft.title"));
    }

    @Override
    protected void init() {
        int contentX = SIDEBAR_WIDTH;
        int contentY = HEADER_HEIGHT;
        int contentW = this.width - SIDEBAR_WIDTH;
        int contentH = this.height - HEADER_HEIGHT;

        // Initialize child widgets
        nowPlayingWidget = new NowPlayingWidget(contentX, contentY, contentW, contentH);
        playlistWidget   = new PlaylistListWidget(contentX, contentY, contentW, contentH, this);
        searchWidget     = new SearchWidget(contentX, contentY, contentW, contentH, this);
        settingsWidget   = new SettingsWidget(contentX, contentY, contentW, contentH, this);

        // Set up API error listener
        SpotifyAPI.getInstance().setErrorListener(msg -> {
            statusMessage = msg;
            statusMessageTick = 120;
        });

        // Poll playback state every 3 seconds
        pollScheduler.scheduleAtFixedRate(() -> {
            if (SpotifyAuth.getInstance().isAuthenticated()) {
                SpotifyAPI.getInstance().fetchPlaybackState();
            }
        }, 0, 3, TimeUnit.SECONDS);

        // Load initial data for current tab
        refreshCurrentTab();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Dark backdrop
        context.fill(0, 0, this.width, this.height, 0xEE0A0A0A);

        // Draw header
        drawHeader(context);

        // Draw sidebar
        drawSidebar(context, mouseX, mouseY);

        // Draw active content widget
        drawContent(context, mouseX, mouseY, delta);

        // Status message overlay
        if (statusMessageTick > 0 && !statusMessage.isEmpty()) {
            int alpha = Math.min(255, statusMessageTick * 4);
            int color = (alpha << 24) | 0xFF5555;
            int msgX = SIDEBAR_WIDTH + 10;
            int msgY = this.height - 20;
            context.drawText(this.textRenderer, statusMessage, msgX, msgY, color, true);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawHeader(DrawContext context) {
        // Header background
        context.fill(0, 0, this.width, HEADER_HEIGHT, SpotiCraftConfig.COLOR_BG);
        // Spotify green accent line at bottom of header
        context.fill(0, HEADER_HEIGHT - 1, this.width, HEADER_HEIGHT, SpotiCraftConfig.COLOR_ACCENT);

        // Title
        String title = "🎵 SpotiCraft";
        context.drawText(this.textRenderer, title, 10, (HEADER_HEIGHT - 8) / 2, SpotiCraftConfig.COLOR_ACCENT, false);

        // Auth status indicator (right side)
        boolean authenticated = SpotifyAuth.getInstance().isAuthenticated();
        String statusStr = authenticated ? "● Connected" : "○ Not Connected";
        int statusColor = authenticated ? SpotiCraftConfig.COLOR_ACCENT : 0xFFFF5555;
        int statusX = this.width - this.textRenderer.getWidth(statusStr) - 10;
        context.drawText(this.textRenderer, statusStr, statusX, (HEADER_HEIGHT - 8) / 2, statusColor, false);
    }

    private void drawSidebar(DrawContext context, int mouseX, int mouseY) {
        // Sidebar background
        context.fill(0, HEADER_HEIGHT, SIDEBAR_WIDTH, this.height, 0xCC0D0D0D);
        // Right border
        context.fill(SIDEBAR_WIDTH - 1, HEADER_HEIGHT, SIDEBAR_WIDTH, this.height, 0xFF1A1A1A);

        String[] tabs = {"▶  Now Playing", "♫  Playlists", "⌕  Search", "⚙  Settings"};
        int tabY = HEADER_HEIGHT + 10;

        for (int i = 0; i < tabs.length; i++) {
            boolean active = currentTab == i;
            boolean hovered = mouseX >= 0 && mouseX < SIDEBAR_WIDTH - 1 &&
                              mouseY >= tabY && mouseY < tabY + 22;

            int bg = active ? SpotiCraftConfig.COLOR_BG_HOVER : (hovered ? 0xCC1A1A1A : 0);
            if (bg != 0) context.fill(0, tabY, SIDEBAR_WIDTH - 1, tabY + 22, bg);

            // Green left accent bar for active tab
            if (active) context.fill(0, tabY, 3, tabY + 22, SpotiCraftConfig.COLOR_ACCENT);

            int textColor = active ? SpotiCraftConfig.COLOR_ACCENT : SpotiCraftConfig.COLOR_TEXT_SECONDARY;
            context.drawText(this.textRenderer, tabs[i], 12, tabY + 7, textColor, false);

            tabY += 26;
        }
    }

    private void drawContent(DrawContext context, int mouseX, int mouseY, float delta) {
        switch (currentTab) {
            case TAB_NOW_PLAYING -> nowPlayingWidget.render(context, mouseX, mouseY, delta, this.textRenderer);
            case TAB_PLAYLISTS   -> playlistWidget.render(context, mouseX, mouseY, delta, this.textRenderer);
            case TAB_SEARCH      -> searchWidget.render(context, mouseX, mouseY, delta, this.textRenderer);
            case TAB_SETTINGS    -> settingsWidget.render(context, mouseX, mouseY, delta, this.textRenderer);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // Sidebar tab clicks
        int tabY = HEADER_HEIGHT + 10;
        for (int i = 0; i < 4; i++) {
            if (mouseX >= 0 && mouseX < SIDEBAR_WIDTH - 1 && mouseY >= tabY && mouseY < tabY + 22) {
                if (currentTab != i) {
                    currentTab = i;
                    refreshCurrentTab();
                }
                return true;
            }
            tabY += 26;
        }

        // Delegate to content widget
        boolean handled = switch (currentTab) {
            case TAB_NOW_PLAYING -> nowPlayingWidget.mouseClicked(mouseX, mouseY, button);
            case TAB_PLAYLISTS   -> playlistWidget.mouseClicked(mouseX, mouseY, button);
            case TAB_SEARCH      -> searchWidget.mouseClicked(mouseX, mouseY, button);
            case TAB_SETTINGS    -> settingsWidget.mouseClicked(mouseX, mouseY, button);
            default -> false;
        };
        if (handled) return true;

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return switch (currentTab) {
            case TAB_PLAYLISTS -> playlistWidget.mouseScrolled(mouseX, mouseY, verticalAmount);
            case TAB_SEARCH    -> searchWidget.mouseScrolled(mouseX, mouseY, verticalAmount);
            default -> super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (currentTab == TAB_SEARCH) {
            if (searchWidget.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (currentTab == TAB_SETTINGS) {
            if (settingsWidget.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (currentTab == TAB_SEARCH) {
            if (searchWidget.charTyped(chr, modifiers)) return true;
        }
        if (currentTab == TAB_SETTINGS) {
            if (settingsWidget.charTyped(chr, modifiers)) return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public void tick() {
        if (statusMessageTick > 0) statusMessageTick--;
        nowPlayingWidget.tick();
        if (currentTab == TAB_SEARCH) searchWidget.tick();
    }

    @Override
    public void close() {
        pollScheduler.shutdownNow();
        SpotifyAPI.getInstance().setErrorListener(null);
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false; // Don't pause the game
    }

    private void refreshCurrentTab() {
        switch (currentTab) {
            case TAB_PLAYLISTS -> playlistWidget.loadPlaylists();
            case TAB_SEARCH    -> { /* search is triggered by user input */ }
            default            -> { }
        }
    }

    public void showStatus(String msg) {
        this.statusMessage = msg;
        this.statusMessageTick = 120;
    }

    // Called by child widgets to open a track list for a playlist
    public void openTrackList(String playlistId, String playlistName) {
        playlistWidget.openPlaylist(playlistId, playlistName);
    }

    public int getSidebarWidth() { return SIDEBAR_WIDTH; }
    public int getHeaderHeight()  { return HEADER_HEIGHT; }
}
