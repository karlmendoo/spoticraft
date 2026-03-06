package com.spoticraft.ui;

import com.spoticraft.SpotiCraftClient;
import com.spoticraft.api.SpotifyAPI;
import com.spoticraft.api.models.PlaybackState;
import com.spoticraft.api.models.Track;
import com.spoticraft.config.SpotiCraftConfig;
import com.spoticraft.util.AlbumArtCache;
import com.spoticraft.util.TimeFormatter;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Displays the current playing track with album art, controls, and progress bar.
 */
public class NowPlayingWidget {

    private final int x, y, w, h;

    // Progress bar drag state
    private boolean draggingProgress = false;

    // Scroll offset for long titles
    private float titleScrollOffset = 0;
    private int titleScrollTick = 0;
    private static final int SCROLL_DELAY = 60;

    public NowPlayingWidget(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer tr) {
        PlaybackState state = SpotifyAPI.getInstance().getPlaybackState();

        int cx = x + w / 2;
        int padding = 16;

        if (!state.hasActiveDevice) {
            // No active device message
            context.fill(x, y, x + w, y + h, SpotiCraftConfig.COLOR_BG);
            String msg = "No active Spotify device.";
            String sub = "Open Spotify on any device and start playing.";
            context.drawCenteredTextWithShadow(tr, msg, cx, y + h / 2 - 10, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
            context.drawCenteredTextWithShadow(tr, sub, cx, y + h / 2 + 4, 0xFF666666);
            return;
        }

        Track track = state.currentTrack;

        // Background panel
        context.fill(x, y, x + w, y + h, SpotiCraftConfig.COLOR_BG);

        // ── Album Art ─────────────────────────────────────────────────────────
        int artSize = Math.min(w / 3, 120);
        int artX = x + padding;
        int artY = y + padding;

        if (track != null && track.albumArtUrl != null && !track.albumArtUrl.isBlank()) {
            Identifier artTex = AlbumArtCache.getInstance().get(track.albumArtUrl);
            if (artTex != null) {
                context.drawTexture(artTex, artX, artY, 0, 0, artSize, artSize, artSize, artSize);
                // Subtle border
                drawBorder(context, artX, artY, artSize, artSize, 0xFF333333);
            } else {
                // Placeholder while loading
                context.fill(artX, artY, artX + artSize, artY + artSize, 0xFF1A1A1A);
                context.drawCenteredTextWithShadow(tr, "♫", artX + artSize / 2, artY + artSize / 2 - 4, SpotiCraftConfig.COLOR_ACCENT);
            }
        } else {
            context.fill(artX, artY, artX + artSize, artY + artSize, 0xFF1A1A1A);
            context.drawCenteredTextWithShadow(tr, "♫", artX + artSize / 2, artY + artSize / 2 - 4, SpotiCraftConfig.COLOR_ACCENT);
        }

        // ── Track Info ────────────────────────────────────────────────────────
        int infoX = artX + artSize + padding;
        int infoW = w - artSize - padding * 3;
        int infoY = artY;

        if (track != null) {
            // Song title (with scroll if too long)
            String title = track.name != null ? track.name : "Unknown Track";
            renderScrollingText(context, tr, title, infoX, infoY, infoW,
                    SpotiCraftConfig.COLOR_TEXT, titleScrollOffset);

            // Artist
            String artist = track.getArtistString();
            context.drawText(tr, truncate(tr, artist, infoW), infoX, infoY + 14, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);

            // Album
            String album = track.albumName != null ? track.albumName : "";
            context.drawText(tr, truncate(tr, album, infoW), infoX, infoY + 26, 0xFF666666, false);

            // Shuffle / Repeat status
            String shuffleStr = state.shuffleState ? "⇄ On" : "⇄ Off";
            int shuffleColor = state.shuffleState ? SpotiCraftConfig.COLOR_ACCENT : 0xFF555555;
            context.drawText(tr, shuffleStr, infoX, infoY + 42, shuffleColor, false);

            String repeatStr = switch (state.repeatState) {
                case "context" -> "↻ Context";
                case "track"   -> "↻ Track";
                default        -> "↻ Off";
            };
            int repeatColor = "off".equals(state.repeatState) ? 0xFF555555 : SpotiCraftConfig.COLOR_ACCENT;
            context.drawText(tr, repeatStr, infoX + 60, infoY + 42, repeatColor, false);
        } else {
            context.drawCenteredTextWithShadow(tr, "Nothing playing", cx, artY + artSize / 2, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
        }

        // ── Progress Bar ──────────────────────────────────────────────────────
        int barY = artY + artSize + 16;
        int barX = x + padding;
        int barW = w - padding * 2;
        int barH = 4;

        if (track != null) {
            long progress = state.progressMs;
            long duration = track.durationMs;
            float fraction = duration > 0 ? (float) progress / duration : 0;

            // Background track
            context.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            // Filled portion (Spotify green)
            int fillW = (int) (barW * fraction);
            context.fill(barX, barY, barX + fillW, barY + barH, SpotiCraftConfig.COLOR_ACCENT);
            // Playhead knob
            int knobX = barX + fillW - 4;
            context.fill(knobX, barY - 3, knobX + 8, barY + barH + 3, SpotiCraftConfig.COLOR_TEXT);

            // Hover highlight
            if (mouseX >= barX && mouseX <= barX + barW && mouseY >= barY - 4 && mouseY <= barY + barH + 4) {
                context.fill(barX, barY - 1, barX + barW, barY + barH + 1, 0x221DB954);
            }

            // Time display
            String timeStr = TimeFormatter.formatProgress(progress, duration);
            context.drawCenteredTextWithShadow(tr, timeStr, cx, barY + barH + 6, SpotiCraftConfig.COLOR_TEXT_SECONDARY);
        }

        // ── Playback Controls ─────────────────────────────────────────────────
        int ctrlY = barY + barH + 26;
        int ctrlSpacing = 36;
        int ctrlCenterX = cx;

        // Controls: ⏮  ⏯  ⏭  ⇄  ↻
        String[] buttons   = {"⏮", state.isPlaying ? "⏸" : "▶", "⏭", "⇄", "↻"};
        int[]    offsets   = {-ctrlSpacing * 2, -ctrlSpacing, 0, ctrlSpacing, ctrlSpacing * 2};
        int[]    colors    = {
            SpotiCraftConfig.COLOR_TEXT_SECONDARY,
            SpotiCraftConfig.COLOR_TEXT,
            SpotiCraftConfig.COLOR_TEXT_SECONDARY,
            state.shuffleState ? SpotiCraftConfig.COLOR_ACCENT : SpotiCraftConfig.COLOR_TEXT_SECONDARY,
            "off".equals(state.repeatState) ? SpotiCraftConfig.COLOR_TEXT_SECONDARY : SpotiCraftConfig.COLOR_ACCENT
        };

        for (int i = 0; i < buttons.length; i++) {
            int bx = ctrlCenterX + offsets[i];
            boolean hovered = mouseX >= bx - 10 && mouseX <= bx + 10 &&
                              mouseY >= ctrlY - 6 && mouseY <= ctrlY + 14;
            if (hovered) context.fill(bx - 10, ctrlY - 6, bx + 10, ctrlY + 14, 0x331DB954);
            context.drawCenteredTextWithShadow(tr, buttons[i], bx, ctrlY, colors[i]);
        }

        // ── Volume Slider ─────────────────────────────────────────────────────
        int volY = ctrlY + 28;
        int volX = x + padding + 20;
        int volW = w - padding * 2 - 60;

        context.drawText(tr, "🔊", x + padding, volY + 1, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);
        context.fill(volX, volY + 3, volX + volW, volY + 7, 0xFF333333);
        int volFill = (int) (volW * (state.volumePercent / 100.0f));
        context.fill(volX, volY + 3, volX + volFill, volY + 7, SpotiCraftConfig.COLOR_ACCENT);
        context.fill(volX + volFill - 4, volY, volX + volFill + 4, volY + 10, SpotiCraftConfig.COLOR_TEXT);
        context.drawText(tr, state.volumePercent + "%", volX + volW + 6, volY + 1,
                SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);
    }

    /** Handle progress bar / button clicks. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        PlaybackState state = SpotifyAPI.getInstance().getPlaybackState();
        int padding = 16;
        int artSize = Math.min(w / 3, 120);
        int barY = y + padding + artSize + 16;
        int barX = x + padding;
        int barW = w - padding * 2;

        // Progress bar click
        if (mouseY >= barY - 4 && mouseY <= barY + 8 && mouseX >= barX && mouseX <= barX + barW) {
            Track track = state.currentTrack;
            if (track != null && track.durationMs > 0) {
                float fraction = (float) (mouseX - barX) / barW;
                long seekMs = (long) (fraction * track.durationMs);
                SpotifyAPI.getInstance().seekTo(seekMs);
            }
            return true;
        }

        // Playback control clicks
        int ctrlY = barY + 4 + 26;
        int ctrlSpacing = 36;
        int cx = x + w / 2;
        int[] offsets = {-ctrlSpacing * 2, -ctrlSpacing, 0, ctrlSpacing, ctrlSpacing * 2};

        for (int i = 0; i < offsets.length; i++) {
            int bx = cx + offsets[i];
            if (mouseX >= bx - 10 && mouseX <= bx + 10 && mouseY >= ctrlY - 6 && mouseY <= ctrlY + 14) {
                handleControlClick(i, state);
                return true;
            }
        }

        // Volume slider click
        int volY = ctrlY + 28;
        int volX = x + padding + 20;
        int volW = w - padding * 2 - 60;
        if (mouseY >= volY - 2 && mouseY <= volY + 12 && mouseX >= volX && mouseX <= volX + volW) {
            float fraction = (float) (mouseX - volX) / volW;
            int vol = Math.round(fraction * 100);
            SpotifyAPI.getInstance().setVolume(vol);
            return true;
        }

        return false;
    }

    private void handleControlClick(int index, PlaybackState state) {
        switch (index) {
            case 0 -> SpotifyAPI.getInstance().skipToPrevious();
            case 1 -> SpotifyAPI.getInstance().togglePlayPause();
            case 2 -> SpotifyAPI.getInstance().skipToNext();
            case 3 -> SpotifyAPI.getInstance().toggleShuffle();
            case 4 -> SpotifyAPI.getInstance().cycleRepeat();
        }
    }

    public void tick() {
        // Animate scrolling title
        titleScrollTick++;
        PlaybackState state = SpotifyAPI.getInstance().getPlaybackState();
        if (state.currentTrack != null) {
            String title = state.currentTrack.name;
            if (title != null && title.length() > 22) {
                if (titleScrollTick > SCROLL_DELAY) {
                    titleScrollOffset += 0.5f;
                }
            } else {
                titleScrollOffset = 0;
                titleScrollTick = 0;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void renderScrollingText(DrawContext ctx, TextRenderer tr, String text, int textX,
                                     int textY, int maxW, int color, float scrollOffset) {
        int textW = tr.getWidth(text);
        if (textW <= maxW) {
            ctx.drawText(tr, text, textX, textY, color, false);
        } else {
            // Clip and offset
            ctx.enableScissor(textX, textY - 1, textX + maxW, textY + tr.fontHeight + 1);
            ctx.drawText(tr, text, textX - (int) scrollOffset, textY, color, false);
            ctx.disableScissor();
        }
    }

    private String truncate(TextRenderer tr, String text, int maxW) {
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
