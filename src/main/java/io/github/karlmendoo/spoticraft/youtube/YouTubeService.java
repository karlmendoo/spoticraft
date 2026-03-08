package io.github.karlmendoo.spoticraft.youtube;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import io.github.karlmendoo.spoticraft.audio.LavaPlayerAudioEngine;
import io.github.karlmendoo.spoticraft.audio.LavaPlayerAudioEngine.LoadedTrack;
import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import io.github.karlmendoo.spoticraft.youtube.YouTubeApiClient.PlayableMedia;
import io.github.karlmendoo.spoticraft.youtube.YouTubeApiClient.ResolvedPlayback;
import io.github.karlmendoo.spoticraft.youtube.model.LibraryItem;
import io.github.karlmendoo.spoticraft.youtube.model.LibrarySnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.PlaybackSnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.RepeatMode;
import io.github.karlmendoo.spoticraft.youtube.model.SearchSnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.ToastMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class YouTubeService {
    // Accept any positive duration coming from either the YouTube Data API or LavaPlayer metadata and only fall back
    // when the resolved value is missing or non-positive.
    private static final int MIN_VALID_DURATION_MS = 1;
    private static final String EMPTY_QUEUE_STATUS_MESSAGE =
        "Search YouTube or choose a playlist to start in-game playback.";

    private final SpotiCraftConfig config;
    private final YouTubeAuthManager authManager;
    private final YouTubeApiClient apiClient;
    private final LavaPlayerAudioEngine audioEngine;
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
    private volatile List<PlayableMedia> queue = List.of();
    private volatile int queueIndex = -1;

    public YouTubeService(
        SpotiCraftConfig config,
        YouTubeAuthManager authManager,
        YouTubeApiClient apiClient,
        LavaPlayerAudioEngine audioEngine,
        Logger logger
    ) {
        this.config = config;
        this.authManager = authManager;
        this.apiClient = apiClient;
        this.audioEngine = audioEngine;
        this.logger = logger;
        this.library = new LibrarySnapshot(List.of(), List.of(), this.config.recentLibraryItems());
        this.audioEngine.setListener(new LavaPlayerAudioEngine.Listener() {
            @Override
            public void onTrackEnded(AudioTrackEndReason endReason) {
                executor.submit(() -> handleTrackEnded(endReason));
            }

            @Override
            public void onTrackException(String message) {
                executor.submit(() -> {
                    errorMessage = message == null ? "Playback failed." : message;
                    statusMessage = "In-game playback failed.";
                    syncPlaybackSnapshot();
                });
            }
        });
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

    public void tick() {
        this.audioEngine.tick();
    }

    public void shutdown() {
        this.audioEngine.close();
        this.executor.shutdownNow();
    }

    public void clearToast() {
        this.toastMessage = null;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public int renderedProgressMs() {
        return this.audioEngine.hasTrack() ? this.audioEngine.currentPositionMs() : this.playback.progressMs();
    }

    public void refreshAll() {
        submit("Refreshing YouTube data…", () -> {
            this.library = this.apiClient.fetchLibrary(this.config.recentLibraryItems());
            syncPlaybackSnapshot();
            this.statusMessage = (this.config.hasRefreshToken() || this.config.hasAccessToken())
                ? "Connected to YouTube and ready to browse."
                : "Connect YouTube to browse your library.";
        });
    }

    public void refreshPlayback() {
        submit("Syncing playback…", () -> {
            syncPlaybackSnapshot();
            if (!this.audioEngine.hasTrack()) {
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
            if (!this.audioEngine.hasTrack()) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
                return;
            }
            if (this.audioEngine.isPlaying()) {
                this.audioEngine.pause();
                syncPlaybackSnapshot();
                this.statusMessage = "Paused in-game playback.";
                return;
            }
            this.audioEngine.resume();
            syncPlaybackSnapshot();
            this.statusMessage = "Resumed in-game playback.";
        });
    }

    public void stopPlayback() {
        submit("Stopping playback…", () -> {
            this.audioEngine.stop();
            syncPlaybackSnapshot();
            this.statusMessage = "Stopped in-game playback.";
        });
    }

    public void nextTrack() {
        submit("Skipping track…", () -> {
            if (!moveToNext("Playing next YouTube video.")) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
            }
        });
    }

    public void previousTrack() {
        submit("Rewinding track…", () -> {
            if (!this.audioEngine.hasTrack()) {
                this.statusMessage = EMPTY_QUEUE_STATUS_MESSAGE;
                return;
            }
            if (renderedProgressMs() > 3000) {
                this.audioEngine.seekTo(0);
                syncPlaybackSnapshot();
                this.statusMessage = "Restarted current video.";
                return;
            }
            if (!moveToPrevious()) {
                this.audioEngine.seekTo(0);
                syncPlaybackSnapshot();
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
            this.statusMessage = "Repeat mode set to " + nextMode.name() + '.';
        });
    }

    public void setVolume(int volumePercent) {
        submit("Adjusting volume…", () -> {
            int clamped = Math.max(0, Math.min(volumePercent, 100));
            this.audioEngine.setVolume(clamped);
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
                clamped,
                snapshot.shuffleEnabled(),
                snapshot.repeatMode(),
                snapshot.deviceName(),
                snapshot.deviceId(),
                snapshot.hasActiveDevice()
            );
            this.statusMessage = "Volume set to " + clamped + "% for the in-game session.";
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
            startCurrentMedia(0, "Playing in Minecraft with LavaPlayer.");
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

    private void handleTrackEnded(AudioTrackEndReason endReason) {
        if (endReason == AudioTrackEndReason.REPLACED || endReason == AudioTrackEndReason.STOPPED) {
            syncPlaybackSnapshot();
            return;
        }
        if (endReason.mayStartNext && moveToNext("Playing next YouTube video.")) {
            return;
        }
        syncPlaybackSnapshot();
        if (this.playback.hasActiveDevice()) {
            this.playback = new PlaybackSnapshot(
                this.playback.trackId(),
                this.playback.trackUri(),
                this.playback.title(),
                this.playback.artist(),
                this.playback.album(),
                this.playback.imageUrl(),
                false,
                this.playback.durationMs(),
                this.playback.durationMs(),
                this.playback.volumePercent(),
                this.playback.shuffleEnabled(),
                this.playback.repeatMode(),
                this.playback.deviceName(),
                this.playback.deviceId(),
                true
            );
        }
        this.statusMessage = endReason == AudioTrackEndReason.LOAD_FAILED
            ? "Playback ended because the audio stream failed."
            : "Playback finished.";
    }

    private void syncPlaybackSnapshot() {
        if (!this.audioEngine.hasTrack() || this.queue.isEmpty() || this.queueIndex < 0 || this.queueIndex >= this.queue.size()) {
            if (!this.audioEngine.hasTrack()) {
                this.playback = new PlaybackSnapshot(
                    this.playback.trackId(),
                    this.playback.trackUri(),
                    this.playback.title(),
                    this.playback.artist(),
                    this.playback.album(),
                    this.playback.imageUrl(),
                    false,
                    this.playback.progressMs(),
                    this.playback.durationMs(),
                    this.playback.volumePercent(),
                    this.playback.shuffleEnabled(),
                    this.playback.repeatMode(),
                    "In-game player",
                    this.playback.deviceId(),
                    !this.playback.trackId().isBlank()
                );
            }
            return;
        }
        applyPlayback(snapshotFor(currentMedia(), this.audioEngine.isPlaying(), this.audioEngine.currentPositionMs()));
    }

    private boolean moveToNext(String status) {
        if (this.queue.isEmpty()) {
            return false;
        }
        int nextIndex = nextIndex();
        if (nextIndex < 0) {
            return false;
        }
        this.queueIndex = nextIndex;
        try {
            startCurrentMedia(0, status);
            return true;
        } catch (IOException exception) {
            this.logger.error("Failed to start next track", exception);
            this.errorMessage = "Failed to start next track: " + exception.getMessage();
            return false;
        }
    }

    private boolean moveToPrevious() {
        if (this.queue.isEmpty() || this.queueIndex <= 0) {
            return false;
        }
        this.queueIndex--;
        try {
            startCurrentMedia(0, "Playing previous YouTube video.");
            return true;
        } catch (IOException exception) {
            this.logger.error("Failed to start previous track", exception);
            this.errorMessage = "Failed to start previous track: " + exception.getMessage();
            return false;
        }
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

    private void startCurrentMedia(int offsetMs, String status) throws IOException {
        PlayableMedia enriched = enrichCurrentMedia(this.audioEngine.play(
            YouTubeApiClient.watchUrl(currentMedia().videoId(), currentMedia().playlistId(), offsetSeconds(offsetMs)),
            this.playback.volumePercent(),
            offsetMs
        ));
        replaceCurrentMedia(enriched);
        applyPlayback(snapshotFor(enriched, true, this.audioEngine.currentPositionMs()));
        rememberCurrentMedia();
        this.statusMessage = status;
    }

    private PlayableMedia enrichCurrentMedia(LoadedTrack loadedTrack) {
        PlayableMedia media = currentMedia();
        return new PlayableMedia(
            media.videoId(),
            media.playlistId(),
            preferPrimary(loadedTrack.title(), media.title()),
            preferPrimary(loadedTrack.author(), media.creator()),
            media.collectionTitle(),
            preferPrimary(loadedTrack.artworkUrl(), media.imageUrl()),
            loadedTrack.durationMs() > MIN_VALID_DURATION_MS ? loadedTrack.durationMs() : media.durationMs()
        );
    }

    private void replaceCurrentMedia(PlayableMedia media) {
        List<PlayableMedia> updatedQueue = new ArrayList<>(this.queue);
        updatedQueue.set(this.queueIndex, media);
        this.queue = List.copyOf(updatedQueue);
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
            YouTubeApiClient.watchUrl(media.videoId(), media.playlistId(), offsetSeconds(progressMs)),
            media.title(),
            media.creator(),
            media.collectionTitle(),
            media.imageUrl(),
            isPlaying,
            Math.max(0, Math.min(progressMs, media.durationMs())),
            Math.max(1, media.durationMs()),
            snapshot.volumePercent(),
            snapshot.shuffleEnabled(),
            snapshot.repeatMode(),
            "In-game player",
            media.videoId(),
            true
        );
    }

    private void applyPlayback(PlaybackSnapshot snapshot) {
        PlaybackSnapshot previous = this.playback;
        this.playback = snapshot;
        if (!snapshot.trackId().isBlank() && !snapshot.trackId().equals(previous.trackId())) {
            this.toastMessage = new ToastMessage(snapshot.title(), snapshot.artist(), snapshot.imageUrl(), System.currentTimeMillis(), 4500L);
        }
    }

    private static String preferPrimary(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private static int offsetSeconds(int offsetMs) {
        return Math.max(0, offsetMs / 1000);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws IOException, InterruptedException, YouTubeApiException;
    }
}
