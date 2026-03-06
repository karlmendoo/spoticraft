package com.spoticraft.api.models;

/**
 * Represents the current Spotify playback state.
 */
public class PlaybackState {
    public Track currentTrack;
    public boolean isPlaying;
    public boolean shuffleState;
    public String repeatState; // "off", "context", "track"
    public int volumePercent;
    public long progressMs;
    public String deviceName;
    public boolean hasActiveDevice;

    public PlaybackState() {
        this.hasActiveDevice = false;
        this.isPlaying = false;
        this.shuffleState = false;
        this.repeatState = "off";
        this.volumePercent = 50;
    }
}
