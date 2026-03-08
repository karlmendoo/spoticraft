package io.github.karlmendoo.spoticraft.youtube.model;

public record PlaybackSnapshot(
    String trackId,
    String trackUri,
    String title,
    String artist,
    String album,
    String imageUrl,
    boolean isPlaying,
    PlaybackState state,
    int progressMs,
    int durationMs,
    int volumePercent,
    boolean shuffleEnabled,
    RepeatMode repeatMode,
    String deviceName,
    String deviceId,
    boolean hasActiveDevice,
    int queueIndex,
    int queueSize
) {
    public static PlaybackSnapshot empty() {
        return new PlaybackSnapshot(
            "",
            "",
            "Nothing playing",
            "Connect YouTube to browse and play media",
            "",
            "",
            false,
            PlaybackState.IDLE,
            0,
            1,
            70,
            false,
            RepeatMode.OFF,
            "In-game player",
            "",
            false,
            -1,
            0
        );
    }
}
