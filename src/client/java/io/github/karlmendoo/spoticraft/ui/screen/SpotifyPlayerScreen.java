package io.github.karlmendoo.spoticraft.ui.screen;

import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import io.github.karlmendoo.spoticraft.spotify.SpotifyAPI;
import io.github.karlmendoo.spoticraft.spotify.SpotifyPlayer;
import io.github.karlmendoo.spoticraft.ui.widget.SpotiCraftButton;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Main Spotify player screen with playback controls, progress bar, and volume slider.
 * Shows current track info and provides navigation to browse/search screens.
 */
public class SpotifyPlayerScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget clientIdField;
    private boolean isSettingUp = false;

    // Layout constants
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 220;
    private static final int ACCENT_COLOR = 0xFF1DB954; // Spotify green
    private static final int BG_COLOR = 0xE0181818;
    private static final int PANEL_COLOR = 0xE0222222;
    private static final int TEXT_COLOR = 0xFFEEEEEE;
    private static final int DIM_TEXT = 0xFF999999;

    // Volume drag state
    private boolean draggingVolume = false;
    // Progress drag state
    private boolean draggingProgress = false;

    public SpotifyPlayerScreen(Screen parent) {
        super(Text.literal("SpotiCraft"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        SpotiCraftConfig config = SpotiCraftClient.getConfig();
        if (!config.hasClientId()) {
            isSettingUp = true;
            initSetupUI();
            return;
        }

        isSettingUp = false;
        if (!config.isAuthenticated() && !config.hasRefreshToken()) {
            initLoginUI();
            return;
        }

        // Try to ensure valid token and start polling
        if (config.hasRefreshToken() && !config.isAuthenticated()) {
            SpotiCraftClient.getAuthManager().ensureValidToken();
        }
        SpotiCraftClient.getPlayer().startPolling();

        initPlayerUI();
    }

    private void initSetupUI() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        clientIdField = new TextFieldWidget(
                this.textRenderer, centerX - 100, centerY - 10, 200, 20,
                Text.literal("Client ID")
        );
        clientIdField.setMaxLength(64);
        clientIdField.setPlaceholder(Text.literal("Enter Spotify Client ID..."));
        this.addDrawableChild(clientIdField);

        this.addDrawableChild(new SpotiCraftButton(
                centerX - 50, centerY + 20, 100, 20,
                Text.literal("Save & Connect"),
                button -> {
                    String id = clientIdField.getText().trim();
                    if (!id.isEmpty()) {
                        SpotiCraftClient.getConfig().setClientId(id);
                        SpotiCraftClient.getConfig().save();
                        this.clearAndInit();
                    }
                }
        ));
    }

    private void initLoginUI() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.addDrawableChild(new SpotiCraftButton(
                centerX - 60, centerY, 120, 20,
                Text.literal("\u266B Connect Spotify"),
                button -> {
                    button.active = false;
                    button.setMessage(Text.literal("Waiting..."));
                    SpotiCraftClient.getAuthManager().startAuthFlow().thenAccept(success -> {
                        if (success) {
                            this.client.execute(this::clearAndInit);
                        } else {
                            this.client.execute(() -> {
                                button.active = true;
                                button.setMessage(Text.literal("\u266B Connect Spotify"));
                            });
                        }
                    });
                }
        ));
    }

    private void initPlayerUI() {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelBottom = (this.height + PANEL_HEIGHT) / 2;
        int btnY = panelBottom - 30;
        int btnCenterX = this.width / 2;

        // Previous
        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX - 62, btnY, 20, 20, Text.literal("\u23EE"),
                b -> SpotiCraftClient.getApi().previous()
        ));

        // Play/Pause
        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX - 14, btnY, 28, 20, Text.literal(SpotiCraftClient.getPlayer().isPlaying() ? "\u23F8" : "\u25B6"),
                b -> {
                    SpotifyPlayer player = SpotiCraftClient.getPlayer();
                    if (player.isPlaying()) {
                        SpotiCraftClient.getApi().pause();
                    } else {
                        SpotiCraftClient.getApi().play();
                    }
                }
        ));

        // Next
        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX + 42, btnY, 20, 20, Text.literal("\u23ED"),
                b -> SpotiCraftClient.getApi().next()
        ));

        // Shuffle
        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX - 100, btnY, 28, 20,
                Text.literal(SpotiCraftClient.getPlayer().isShuffleState() ? "\u00A7a\u21C4" : "\u21C4"),
                b -> {
                    boolean newState = !SpotiCraftClient.getPlayer().isShuffleState();
                    SpotiCraftClient.getApi().setShuffle(newState);
                }
        ));

        // Repeat
        String repeatText = switch (SpotiCraftClient.getPlayer().getRepeatState()) {
            case "track" -> "\u00A7a\u21BB\u00B9";
            case "context" -> "\u00A7a\u21BB";
            default -> "\u21BB";
        };
        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX + 72, btnY, 28, 20,
                Text.literal(repeatText),
                b -> {
                    String current = SpotiCraftClient.getPlayer().getRepeatState();
                    String next = switch (current) {
                        case "off" -> "context";
                        case "context" -> "track";
                        default -> "off";
                    };
                    SpotiCraftClient.getApi().setRepeat(next);
                }
        ));

        // Navigation buttons at bottom
        int navY = panelBottom + 8;

        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX - 80, navY, 72, 16,
                Text.literal("Browse"),
                b -> this.client.setScreen(new SpotifyBrowseScreen(this))
        ));

        this.addDrawableChild(new SpotiCraftButton(
                btnCenterX - 2, navY, 72, 16,
                Text.literal("Search"),
                b -> this.client.setScreen(new SpotifySearchScreen(this))
        ));

        // Disconnect button
        this.addDrawableChild(new SpotiCraftButton(
                panelX + PANEL_WIDTH - 52, (this.height - PANEL_HEIGHT) / 2 + 4, 48, 12,
                Text.literal("\u00A77Logout"),
                b -> {
                    SpotiCraftClient.getPlayer().stopPolling();
                    SpotiCraftClient.getAuthManager().logout();
                    this.clearAndInit();
                }
        ));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (this.client == null) return;

        // Semi-transparent background
        context.fill(0, 0, this.width, this.height, 0xA0000000);

        if (isSettingUp) {
            renderSetupScreen(context, mouseX, mouseY);
        } else if (!SpotiCraftClient.getConfig().isAuthenticated()
                && !SpotiCraftClient.getConfig().hasRefreshToken()) {
            renderLoginScreen(context, mouseX, mouseY);
        } else {
            renderPlayerScreen(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderSetupScreen(DrawContext context, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelX = centerX - 130;
        int panelY = centerY - 55;

        // Panel background
        context.fill(panelX, panelY, panelX + 260, panelY + 120, PANEL_COLOR);
        drawBorder(context, panelX, panelY, 260, 120, ACCENT_COLOR);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A7l\u00A7aSpotiCraft Setup"), centerX, panelY + 8, TEXT_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A77Enter your Spotify app Client ID:"), centerX, panelY + 24, DIM_TEXT);
    }

    private void renderLoginScreen(DrawContext context, int mouseX, int mouseY) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelX = centerX - 100;
        int panelY = centerY - 40;

        context.fill(panelX, panelY, panelX + 200, panelY + 70, PANEL_COLOR);
        drawBorder(context, panelX, panelY, 200, 70, ACCENT_COLOR);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A7l\u00A7aSpotiCraft"), centerX, panelY + 10, TEXT_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A77Click below to log in"), centerX, panelY + 24, DIM_TEXT);
    }

    private void renderPlayerScreen(DrawContext context, int mouseX, int mouseY) {
        SpotifyPlayer player = SpotiCraftClient.getPlayer();
        SpotifyAPI.TrackInfo track = player.getCurrentTrack();

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Panel background with accent border
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, PANEL_COLOR);
        drawBorder(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, ACCENT_COLOR);

        // Header
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A7l\u00A7aSpotiCraft"), this.width / 2, panelY + 6, TEXT_COLOR);

        if (!player.hasActiveDevice()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("\u00A77No active Spotify device found"),
                    this.width / 2, this.height / 2 - 5, DIM_TEXT);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("\u00A78Open Spotify on any device to start"),
                    this.width / 2, this.height / 2 + 10, DIM_TEXT);
            return;
        }

        if (track == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("\u00A77Loading..."),
                    this.width / 2, this.height / 2, DIM_TEXT);
            return;
        }

        // Track info
        int infoY = panelY + 26;
        String trackName = track.name();
        if (trackName.length() > 40) trackName = trackName.substring(0, 37) + "...";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A7f\u00A7l" + trackName), this.width / 2, infoY, TEXT_COLOR);

        String artist = track.artist();
        if (artist.length() > 45) artist = artist.substring(0, 42) + "...";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A77" + artist), this.width / 2, infoY + 14, DIM_TEXT);

        String album = track.album();
        if (album.length() > 45) album = album.substring(0, 42) + "...";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("\u00A78" + album), this.width / 2, infoY + 26, DIM_TEXT);

        // Progress bar
        renderProgressBar(context, mouseX, panelX, panelY, player, track);

        // Volume bar
        renderVolumeBar(context, mouseX, panelX, panelY, player);
    }

    private void renderProgressBar(DrawContext context, int mouseX, int panelX, int panelY,
                                   SpotifyPlayer player, SpotifyAPI.TrackInfo track) {
        int barX = panelX + 30;
        int barY = panelY + PANEL_HEIGHT - 58;
        int barWidth = PANEL_WIDTH - 60;
        int barHeight = 4;

        // Background
        context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF444444);

        // Progress fill
        long progress = player.getEstimatedProgressMs();
        long duration = track.durationMs();
        if (duration > 0) {
            float pct = MathHelper.clamp((float) progress / duration, 0, 1);
            int fillWidth = (int) (barWidth * pct);
            context.fill(barX, barY, barX + fillWidth, barY + barHeight, ACCENT_COLOR);

            // Scrub handle
            int handleX = barX + fillWidth;
            context.fill(handleX - 2, barY - 2, handleX + 2, barY + barHeight + 2, 0xFFFFFFFF);
        }

        // Time labels
        String currentTime = formatTime(progress);
        String totalTime = formatTime(duration);
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + currentTime), barX, barY + 7, DIM_TEXT);
        int totalWidth = this.textRenderer.getWidth(totalTime);
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78" + totalTime), barX + barWidth - totalWidth, barY + 7, DIM_TEXT);
    }

    private void renderVolumeBar(DrawContext context, int mouseX, int panelX, int panelY, SpotifyPlayer player) {
        int barX = panelX + PANEL_WIDTH - 90;
        int barY = panelY + PANEL_HEIGHT - 78;
        int barWidth = 60;
        int barHeight = 3;

        // Label
        context.drawTextWithShadow(this.textRenderer, Text.literal("\u00A78\u266A"), barX - 10, barY - 2, DIM_TEXT);

        // Background
        context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF444444);

        // Volume fill
        float pct = player.getVolume() / 100f;
        int fillWidth = (int) (barWidth * pct);
        context.fill(barX, barY, barX + fillWidth, barY + barHeight, ACCENT_COLOR);

        // Handle
        int handleX = barX + fillWidth;
        context.fill(handleX - 1, barY - 2, handleX + 1, barY + barHeight + 2, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !isSettingUp) {
            SpotifyPlayer player = SpotiCraftClient.getPlayer();
            SpotifyAPI.TrackInfo track = player.getCurrentTrack();

            int panelX = (this.width - PANEL_WIDTH) / 2;
            int panelY = (this.height - PANEL_HEIGHT) / 2;

            // Check progress bar click
            if (track != null && track.durationMs() > 0) {
                int barX = panelX + 30;
                int barY = panelY + PANEL_HEIGHT - 58;
                int barWidth = PANEL_WIDTH - 60;
                if (mouseX >= barX && mouseX <= barX + barWidth && mouseY >= barY - 4 && mouseY <= barY + 8) {
                    draggingProgress = true;
                    handleProgressClick(mouseX, barX, barWidth, track.durationMs());
                    return true;
                }
            }

            // Check volume bar click
            int volBarX = panelX + PANEL_WIDTH - 90;
            int volBarY = panelY + PANEL_HEIGHT - 78;
            int volBarWidth = 60;
            if (mouseX >= volBarX && mouseX <= volBarX + volBarWidth && mouseY >= volBarY - 4 && mouseY <= volBarY + 7) {
                draggingVolume = true;
                handleVolumeClick(mouseX, volBarX, volBarWidth);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0) {
            int panelX = (this.width - PANEL_WIDTH) / 2;

            if (draggingProgress) {
                SpotifyAPI.TrackInfo track = SpotiCraftClient.getPlayer().getCurrentTrack();
                if (track != null) {
                    handleProgressClick(mouseX, panelX + 30, PANEL_WIDTH - 60, track.durationMs());
                }
                return true;
            }

            if (draggingVolume) {
                handleVolumeClick(mouseX, panelX + PANEL_WIDTH - 90, 60);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingVolume = false;
        draggingProgress = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void handleProgressClick(double mouseX, int barX, int barWidth, long durationMs) {
        float pct = MathHelper.clamp((float) (mouseX - barX) / barWidth, 0, 1);
        long seekPos = (long) (durationMs * pct);
        SpotiCraftClient.getApi().seek(seekPos);
    }

    private void handleVolumeClick(double mouseX, int barX, int barWidth) {
        float pct = MathHelper.clamp((float) (mouseX - barX) / barWidth, 0, 1);
        int vol = (int) (100 * pct);
        SpotiCraftClient.getApi().setVolume(vol);
    }

    @Override
    public void close() {
        SpotiCraftClient.getPlayer().stopPolling();
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private void drawBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);           // top
        context.fill(x, y + h - 1, x + w, y + h, color);   // bottom
        context.fill(x, y, x + 1, y + h, color);            // left
        context.fill(x + w - 1, y, x + w, y + h, color);   // right
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}
