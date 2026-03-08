package io.github.karlmendoo.spoticraft.youtube;

import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import io.github.karlmendoo.spoticraft.youtube.YouTubeApiClient.PlayableMedia;
import io.github.karlmendoo.spoticraft.youtube.YouTubeApiClient.ResolvedPlayback;
import io.github.karlmendoo.spoticraft.youtube.model.LibraryItem;
import io.github.karlmendoo.spoticraft.youtube.model.LibrarySnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.PlaybackSnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.RepeatMode;
import io.github.karlmendoo.spoticraft.youtube.model.SearchSnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.ToastMessage;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class YouTubeService {
    private static final String EMPTY_QUEUE_STATUS_MESSAGE =
        "Search YouTube or choose a playlist to open playback in your browser.";

    private final SpotiCraftConfig config;
    private final YouTubeAuthManager authManager;
    private final YouTubeApiClient apiClient;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spoticraft-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile PlaybackSnapshot playback = PlaybackSnapshot.empty();
    private volatile LibrarySnapshot library = LibrarySnapshot.empty();
    private volatile SearchSnapshot search = SearchSnapshot.empty();
    private volatile String statusMessage = "Open the config file, add your Google OAuth client ID, then click Connect.";
    private volatile String errorMessage = "";
    private volatile boolean busy;
    private volatile ToastMessage toastMessage;
    private volatile long playbackUpdatedAtMs;
    private volatile List<PlayableMedia> queue = List.of();
    private volatile int queueIndex = -1;

    public YouTubeService(SpotiCraftConfig config, YouTubeAuthManager authManager, YouTubeApiClient apiClient, Logger logger) {
        this.config = config;
        this.authManager = authManager;
        this.apiClient = apiClient;
        this.logger = logger;
        this.library = new LibrarySnapshot(List.of(), List.of(), this.config.recentLibraryItems());
        if (config.hasRefreshToken() || config.hasAccessToken()) {
            this.statusMessage = "Saved YouTube session found. Refresh to sync your library.";
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
        this.statusMessage = "Waiting for YouTube approval in your browser. The link has been copied to your clipboard.";
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
        submit("Refreshing YouTube data…", () -> {
            refreshLocalPlaybackState();
            this.library = this.apiClient.fetchLibrary(this.config.recentLibraryItems());
            this.statusMessage = (this.config.hasRefreshToken() || this.config.hasAccessToken())
                ? "Connected to YouTube and ready to browse."
                : "Connect YouTube to browse your library.";
        });
    }

    public void refreshPlayback() {
        submit("Syncing playback…", () -> {
            refreshLocalPlaybackState();
            if (!this.playback.hasActiveDevice()) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
            }
        });
    }

    public void search(String query) {
        if (query == null || query.isBlank()) {
            this.search = SearchSnapshot.empty();
            this.statusMessage = "Search cleared.";
            return;
        }
        submit("Searching YouTube…", () -> {
            this.search = this.apiClient.search(query);
            this.statusMessage = "Showing YouTube results for ‘" + query + "’.";
        });
    }

    public void togglePlayPause() {
        submit("Updating playback…", () -> {
            if (!this.playback.hasActiveDevice()) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
                return;
            }
            if (this.playback.isPlaying()) {
                applyPlayback(snapshotFor(currentMedia(), false, renderedProgressMs()));
                this.statusMessage = "Paused in SpotiCraft. Pause the browser tab as needed.";
                return;
            }
            applyPlayback(snapshotFor(currentMedia(), true, this.playback.progressMs()));
            openCurrentMedia(this.playback.progressMs());
            this.statusMessage = "Resumed YouTube playback in your browser.";
        });
    }

    public void nextTrack() {
        submit("Skipping track…", () -> {
            if (!moveToNext()) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
            }
        });
    }

    public void previousTrack() {
        submit("Rewinding track…", () -> {
            if (!this.playback.hasActiveDevice()) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
                return;
            }
            if (renderedProgressMs() > 3000) {
                applyPlayback(snapshotFor(currentMedia(), this.playback.isPlaying(), 0));
                openCurrentMedia(0);
                this.statusMessage = "Restarted current video.";
                return;
            }
            if (!moveToPrevious()) {
                applyPlayback(snapshotFor(currentMedia(), this.playback.isPlaying(), 0));
                openCurrentMedia(0);
                this.statusMessage = "Restarted current video.";
            }
        });
    }

    public void toggleShuffle() {
        submit("Toggling shuffle…", () -> {
            PlaybackSnapshot snapshot = this.playback;
            this.playback = new PlaybackSnapshot(
                snapshot.trackId(),
                snapshot.trackUri(),
                snapshot.title(),
                snapshot.artist(),
                snapshot.album(),
                snapshot.imageUrl(),
                snapshot.isPlaying(),
                renderedProgressMs(),
                snapshot.durationMs(),
                snapshot.volumePercent(),
                !snapshot.shuffleEnabled(),
                snapshot.repeatMode(),
                snapshot.deviceName(),
                snapshot.deviceId(),
                snapshot.hasActiveDevice()
            );
            this.playbackUpdatedAtMs = System.currentTimeMillis();
            this.statusMessage = this.playback.shuffleEnabled() ? "Shuffle enabled for the local queue." : "Shuffle disabled.";
        });
    }

    public void cycleRepeat() {
        submit("Cycling repeat mode…", () -> {
            PlaybackSnapshot snapshot = this.playback;
            RepeatMode nextMode = snapshot.repeatMode().next();
            this.playback = new PlaybackSnapshot(
                snapshot.trackId(),
                snapshot.trackUri(),
                snapshot.title(),
                snapshot.artist(),
                snapshot.album(),
                snapshot.imageUrl(),
                snapshot.isPlaying(),
                renderedProgressMs(),
                snapshot.durationMs(),
                snapshot.volumePercent(),
                snapshot.shuffleEnabled(),
                nextMode,
                snapshot.deviceName(),
                snapshot.deviceId(),
                snapshot.hasActiveDevice()
            );
            this.playbackUpdatedAtMs = System.currentTimeMillis();
            this.statusMessage = "Repeat mode set to " + nextMode.name() + '.';
        });
    }

    public void setVolume(int volumePercent) {
        submit("Adjusting volume…", () -> {
            PlaybackSnapshot snapshot = this.playback;
            this.playback = new PlaybackSnapshot(
                snapshot.trackId(),
                snapshot.trackUri(),
                snapshot.title(),
                snapshot.artist(),
                snapshot.album(),
                snapshot.imageUrl(),
                snapshot.isPlaying(),
                renderedProgressMs(),
                snapshot.durationMs(),
                volumePercent,
                snapshot.shuffleEnabled(),
                snapshot.repeatMode(),
                snapshot.deviceName(),
                snapshot.deviceId(),
                snapshot.hasActiveDevice()
            );
            this.playbackUpdatedAtMs = System.currentTimeMillis();
            this.statusMessage = "Volume set to " + volumePercent + "% for the in-game session.";
        });
    }

    public void play(LibraryItem item) {
        submit("Starting playback…", () -> {
            ResolvedPlayback resolvedPlayback = this.apiClient.resolvePlayback(item);
            if (resolvedPlayback.items().isEmpty()) {
                throw new YouTubeApiException(404, "No playable YouTube videos were found for that selection.");
            }
            this.queue = List.copyOf(resolvedPlayback.items());
            this.queueIndex = Math.max(0, Math.min(resolvedPlayback.startIndex(), this.queue.size() - 1));
            applyPlayback(snapshotFor(currentMedia(), true, 0));
            rememberCurrentMedia();
            openCurrentMedia(0);
            this.statusMessage = "Opened \"" + this.playback.title() + "\" on YouTube in your browser.";
        });
    }

    private void submit(String busyMessage, CheckedRunnable action) {
        this.busy = true;
        this.errorMessage = "";
        this.statusMessage = busyMessage;
        this.executor.submit(() -> {
            try {
                action.run();
            } catch (YouTubeApiException exception) {
                handleYouTubeException(exception);
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.logger.error("SpotiCraft request failed", exception);
                this.errorMessage = "YouTube request failed: " + exception.getMessage();
            } catch (RuntimeException exception) {
                this.logger.error("SpotiCraft request failed", exception);
                this.errorMessage = exception.getMessage();
            } finally {
                this.busy = false;
            }
        });
    }

    private void handleYouTubeException(YouTubeApiException exception) {
        if (exception.isAuthFailure()) {
            this.config.clearTokens();
            this.config.save();
        }
        this.errorMessage = exception.getMessage();
    }

    private void refreshLocalPlaybackState() {
        if (!this.playback.hasActiveDevice() || !this.playback.isPlaying()) {
            return;
        }
        if (renderedProgressMs() < this.playback.durationMs()) {
            this.playback = snapshotFor(currentMedia(), true, renderedProgressMs());
            this.playbackUpdatedAtMs = System.currentTimeMillis();
            return;
        }
        if (!moveToNext()) {
            applyPlayback(snapshotFor(currentMedia(), false, this.playback.durationMs()));
        }
    }

    private boolean moveToNext() {
        if (this.queue.isEmpty()) {
            return false;
        }
        int nextIndex = nextIndex();
        if (nextIndex < 0) {
            return false;
        }
        this.queueIndex = nextIndex;
        applyPlayback(snapshotFor(currentMedia(), true, 0));
        rememberCurrentMedia();
        openCurrentMedia(0);
        this.statusMessage = "Playing next YouTube video.";
        return true;
    }

    private boolean moveToPrevious() {
        if (this.queue.isEmpty() || this.queueIndex <= 0) {
            return false;
        }
        this.queueIndex--;
        applyPlayback(snapshotFor(currentMedia(), true, 0));
        rememberCurrentMedia();
        openCurrentMedia(0);
        this.statusMessage = "Playing previous YouTube video.";
        return true;
    }

    private int nextIndex() {
        if (this.queue.isEmpty()) {
            return -1;
        }
        if (this.playback.repeatMode() == RepeatMode.TRACK) {
            return this.queueIndex;
        }
        if (this.playback.shuffleEnabled() && this.queue.size() > 1) {
            int candidate = this.queueIndex;
            while (candidate == this.queueIndex) {
                candidate = ThreadLocalRandom.current().nextInt(this.queue.size());
            }
            return candidate;
        }
        int candidate = this.queueIndex + 1;
        if (candidate < this.queue.size()) {
            return candidate;
        }
        return this.playback.repeatMode() == RepeatMode.CONTEXT ? 0 : -1;
    }

    private void rememberCurrentMedia() {
        PlayableMedia currentMedia = currentMedia();
        this.config.rememberRecentItem(currentMedia.asLibraryItem());
        this.library = new LibrarySnapshot(this.library.playlists(), this.library.likedTracks(), this.config.recentLibraryItems());
    }

    private void openCurrentMedia(int offsetMs) {
        PlayableMedia media = currentMedia();
        Util.getOperatingSystem().open(URI.create(YouTubeApiClient.watchUrl(media.videoId(), media.playlistId(), Math.max(0, offsetMs / 1000))));
    }

    private PlayableMedia currentMedia() {
        if (this.queue.isEmpty() || this.queueIndex < 0 || this.queueIndex >= this.queue.size()) {
            throw new IllegalStateException(EMPTY_QUEUE_STATUS_MESSAGE);
        }
        return this.queue.get(this.queueIndex);
    }

    private PlaybackSnapshot snapshotFor(PlayableMedia media, boolean isPlaying, int progressMs) {
        PlaybackSnapshot snapshot = this.playback;
        return new PlaybackSnapshot(
            media.videoId(),
            YouTubeApiClient.watchUrl(media.videoId(), media.playlistId(), Math.max(0, progressMs / 1000)),
            media.title(),
            media.creator(),
            media.collectionTitle(),
            media.imageUrl(),
            isPlaying,
            Math.max(0, Math.min(progressMs, media.durationMs())),
            media.durationMs(),
            snapshot.volumePercent(),
            snapshot.shuffleEnabled(),
            snapshot.repeatMode(),
            "Browser playback",
            media.videoId(),
            true
        );
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
        void run() throws IOException, InterruptedException, YouTubeApiException;
    }
}
