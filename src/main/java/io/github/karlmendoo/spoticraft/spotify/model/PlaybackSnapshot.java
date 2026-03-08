package io.github.karlmendoo.spoticraft.spotify.model;

public record PlaybackSnapshot(
    String trackId,
    String trackUri,
    String title,
    String artist,
    String album,
    String imageUrl,
    boolean isPlaying,
    int progressMs,
    int durationMs,
    int volumePercent,
    boolean shuffleEnabled,
    RepeatMode repeatMode,
    String deviceName,
    String deviceId,
    boolean hasActiveDevice
) {
    public static PlaybackSnapshot empty() {
        return new PlaybackSnapshot(
            "",
            "",
            "Nothing playing",
            "Connect Spotify to see playback here",
            "",
            "",
            false,
            0,
            1,
            70,
            false,
            RepeatMode.OFF,
            "No active device",
            "",
            false
        );
    }
}
