package io.github.karlmendoo.spoticraft.spotify;

import com.google.gson.JsonObject;
import io.github.karlmendoo.spoticraft.SpotiCraftClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages Spotify playback state by polling the Spotify API.
 * Tracks current song, playback position, and state changes.
 */
public class SpotifyPlayer {
    private final SpotifyAPI api;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SpotiCraft-Player");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> pollTask;
    private volatile SpotifyAPI.TrackInfo currentTrack;
    private volatile boolean isPlaying;
    private volatile long progressMs;
    private volatile long lastPollTime;
    private volatile int volume;
    private volatile boolean shuffleState;
    private volatile String repeatState = "off";
    private volatile boolean hasActiveDevice;
    private volatile String lastTrackUri = "";

    // Listener for track changes
    private volatile Runnable onTrackChange;

    public SpotifyPlayer(SpotifyAPI api) {
        this.api = api;
    }

    /**
     * Starts polling the Spotify API for playback state updates.
     */
    public void startPolling() {
        stopPolling();
        pollTask = scheduler.scheduleAtFixedRate(this::pollPlaybackState, 0, 2, TimeUnit.SECONDS);
    }

    /**
     * Stops polling the Spotify API.
     */
    public void stopPolling() {
        if (pollTask != null && !pollTask.isCancelled()) {
            pollTask.cancel(false);
        }
    }

    /**
     * Polls the current playback state from Spotify.
     */
    private void pollPlaybackState() {
        try {
            JsonObject playback = api.getCurrentPlayback().join();
            if (playback == null || !playback.has("item")) {
                hasActiveDevice = false;
                return;
            }

            hasActiveDevice = true;
            isPlaying = playback.has("is_playing") && playback.get("is_playing").getAsBoolean();
            progressMs = playback.has("progress_ms") ? playback.get("progress_ms").getAsLong() : 0;
            lastPollTime = System.currentTimeMillis();

            if (playback.has("device") && playback.getAsJsonObject("device").has("volume_percent")) {
                volume = playback.getAsJsonObject("device").get("volume_percent").getAsInt();
            }

            shuffleState = playback.has("shuffle_state") && playback.get("shuffle_state").getAsBoolean();
            repeatState = playback.has("repeat_state") ? playback.get("repeat_state").getAsString() : "off";

            SpotifyAPI.TrackInfo newTrack = SpotifyAPI.parseTrack(playback.getAsJsonObject("item"));
            if (newTrack != null) {
                String newUri = newTrack.uri();
                if (!newUri.equals(lastTrackUri)) {
                    lastTrackUri = newUri;
                    currentTrack = newTrack;
                    if (onTrackChange != null) {
                        onTrackChange.run();
                    }
                } else {
                    currentTrack = newTrack;
                }
            }
        } catch (Exception e) {
            SpotiCraftClient.LOGGER.debug("Playback poll error", e);
        }
    }

    /**
     * Returns estimated current progress, accounting for time since last poll.
     */
    public long getEstimatedProgressMs() {
        if (!isPlaying || lastPollTime == 0) return progressMs;
        long elapsed = System.currentTimeMillis() - lastPollTime;
        long estimated = progressMs + elapsed;
        if (currentTrack != null && estimated > currentTrack.durationMs()) {
            return currentTrack.durationMs();
        }
        return estimated;
    }

    // Getters
    public SpotifyAPI.TrackInfo getCurrentTrack() { return currentTrack; }
    public boolean isPlaying() { return isPlaying; }
    public long getProgressMs() { return progressMs; }
    public int getVolume() { return volume; }
    public boolean isShuffleState() { return shuffleState; }
    public String getRepeatState() { return repeatState; }
    public boolean hasActiveDevice() { return hasActiveDevice; }

    public void setOnTrackChange(Runnable listener) { this.onTrackChange = listener; }
}
