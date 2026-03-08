package io.github.karlmendoo.spoticraft.spotify;

import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import io.github.karlmendoo.spoticraft.spotify.model.LibraryItem;
import io.github.karlmendoo.spoticraft.spotify.model.LibrarySnapshot;
import io.github.karlmendoo.spoticraft.spotify.model.PlaybackSnapshot;
import io.github.karlmendoo.spoticraft.spotify.model.RepeatMode;
import io.github.karlmendoo.spoticraft.spotify.model.SearchSnapshot;
import io.github.karlmendoo.spoticraft.spotify.model.ToastMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SpotifyService {
    private static final String NO_ACTIVE_DEVICE_STATUS_MESSAGE =
        "Open Spotify on your phone, desktop, or web player and start playback once before using SpotiCraft controls.";

    private final SpotiCraftConfig config;
    private final SpotifyAuthManager authManager;
    private final SpotifyApiClient apiClient;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spoticraft-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile PlaybackSnapshot playback = PlaybackSnapshot.empty();
    private volatile LibrarySnapshot library = LibrarySnapshot.empty();
    private volatile SearchSnapshot search = SearchSnapshot.empty();
    private volatile String statusMessage = "Open the config file, add your Spotify app credentials, then click Connect.";
    private volatile String errorMessage = "";
    private volatile boolean busy;
    private volatile ToastMessage toastMessage;
    private volatile long playbackUpdatedAtMs;

    public SpotifyService(SpotiCraftConfig config, SpotifyAuthManager authManager, SpotifyApiClient apiClient, Logger logger) {
        this.config = config;
        this.authManager = authManager;
        this.apiClient = apiClient;
        this.logger = logger;
        if (config.hasRefreshToken()) {
            this.statusMessage = "Saved Spotify session found. Refresh to sync playback.";
        }
    }

    public PlaybackSnapshot playback() {
        return this.playback;
    }

    public LibrarySnapshot library() {
        return this.library;
    }

    public SearchSnapshot search() {
        return this.search;
    }

    public String statusMessage() {
        return this.statusMessage;
    }

    public String errorMessage() {
        return this.errorMessage;
    }

    public boolean busy() {
        return this.busy;
    }

    public ToastMessage toastMessage() {
        return this.toastMessage;
    }

    public SpotiCraftConfig config() {
        return this.config;
    }

    public URI startAuthorizationFlow() {
        URI uri = this.authManager.startAuthorization();
        this.errorMessage = "";
        this.statusMessage = "Waiting for Spotify approval in your browser. The link has been copied to your clipboard.";
        return uri;
    }

    public void clearToast() {
        this.toastMessage = null;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public int renderedProgressMs() {
        PlaybackSnapshot snapshot = this.playback;
        if (!snapshot.isPlaying()) {
            return snapshot.progressMs();
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - this.playbackUpdatedAtMs);
        return (int) Math.min(snapshot.durationMs(), snapshot.progressMs() + elapsed);
    }

    public void refreshAll() {
        submit("Refreshing Spotify data…", () -> {
            PlaybackSnapshot updatedPlayback = this.apiClient.fetchPlayback();
            LibrarySnapshot updatedLibrary = this.apiClient.fetchLibrary();
            applyPlayback(updatedPlayback);
            this.library = updatedLibrary;
            this.statusMessage = updatedPlayback.hasActiveDevice()
                ? "Connected to " + updatedPlayback.deviceName()
                : "Connected, but Spotify has no active playback device right now.";
        });
    }

    public void refreshPlayback() {
        submit("Syncing playback…", () -> applyPlayback(this.apiClient.fetchPlayback()));
    }

    public void search(String query) {
        if (query == null || query.isBlank()) {
            this.search = SearchSnapshot.empty();
            this.statusMessage = "Search cleared.";
            return;
        }
        submit("Searching Spotify…", () -> {
            this.search = this.apiClient.search(query);
            this.statusMessage = "Showing results for ‘" + query + "’.";
        });
    }

    public void togglePlayPause() {
        submit("Updating playback…", () -> {
            this.apiClient.togglePlayPause(this.playback);
            applyPlayback(this.apiClient.fetchPlayback());
        });
    }

    public void nextTrack() {
        submit("Skipping track…", () -> {
            this.apiClient.nextTrack();
            applyPlayback(this.apiClient.fetchPlayback());
        });
    }

    public void previousTrack() {
        submit("Rewinding track…", () -> {
            this.apiClient.previousTrack();
            applyPlayback(this.apiClient.fetchPlayback());
        });
    }

    public void toggleShuffle() {
        submit("Toggling shuffle…", () -> {
            this.apiClient.setShuffle(!this.playback.shuffleEnabled());
            applyPlayback(this.apiClient.fetchPlayback());
        });
    }

    public void cycleRepeat() {
        submit("Cycling repeat mode…", () -> {
            RepeatMode nextMode = this.playback.repeatMode().next();
            this.apiClient.setRepeat(nextMode);
            applyPlayback(this.apiClient.fetchPlayback());
        });
    }

    public void setVolume(int volumePercent) {
        submit("Adjusting volume…", () -> {
            this.apiClient.setVolume(volumePercent);
            applyPlayback(this.apiClient.fetchPlayback());
        });
    }

    public void play(LibraryItem item) {
        submit("Starting playback…", () -> {
            PlaybackSnapshot currentPlayback = this.playback.hasActiveDevice()
                ? this.playback
                : this.apiClient.fetchPlayback();
            applyPlayback(currentPlayback);
            if (!currentPlayback.hasActiveDevice()) {
                this.statusMessage = NO_ACTIVE_DEVICE_STATUS_MESSAGE;
                return;
            }
            this.apiClient.play(item);
            applyPlayback(this.apiClient.fetchPlayback());
            this.statusMessage = "Queued " + item.title() + ".";
        });
    }

    private void submit(String busyMessage, CheckedRunnable action) {
        this.busy = true;
        this.errorMessage = "";
        this.statusMessage = busyMessage;
        this.executor.submit(() -> {
            try {
                action.run();
            } catch (SpotifyApiException exception) {
                handleSpotifyException(exception);
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.logger.error("SpotiCraft request failed", exception);
                this.errorMessage = "Spotify request failed: " + exception.getMessage();
            } catch (RuntimeException exception) {
                this.logger.error("SpotiCraft request failed", exception);
                this.errorMessage = exception.getMessage();
            } finally {
                this.busy = false;
            }
        });
    }

    private void handleSpotifyException(SpotifyApiException exception) {
        if (exception.isAuthFailure()) {
            this.config.clearTokens();
            this.config.save();
        }
        this.errorMessage = exception.getMessage();
    }

    private void applyPlayback(PlaybackSnapshot snapshot) {
        PlaybackSnapshot previous = this.playback;
        this.playback = snapshot;
        this.playbackUpdatedAtMs = System.currentTimeMillis();
        if (!snapshot.trackId().isBlank() && !snapshot.trackId().equals(previous.trackId())) {
            this.toastMessage = new ToastMessage(snapshot.title(), snapshot.artist(), snapshot.imageUrl(), System.currentTimeMillis(), 4500L);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws IOException, InterruptedException, SpotifyApiException;
    }
}
