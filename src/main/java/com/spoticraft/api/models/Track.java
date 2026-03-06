package com.spoticraft.api.models;

import java.util.List;

/**
 * Represents a Spotify track.
 */
public class Track {
    public String id;
    public String name;
    public String uri;
    public List<String> artists;   // artist display names
    public String albumName;
    public String albumArtUrl;     // URL of the album art image
    public long durationMs;        // track duration in milliseconds
    public long progressMs;        // current playback position

    public Track() {}

    public Track(String id, String name, List<String> artists, String albumName,
                 String albumArtUrl, long durationMs, long progressMs) {
        this.id = id;
        this.name = name;
        this.uri = "spotify:track:" + id;
        this.artists = artists;
        this.albumName = albumName;
        this.albumArtUrl = albumArtUrl;
        this.durationMs = durationMs;
        this.progressMs = progressMs;
    }

    /** Formatted artist string (comma-separated). */
    public String getArtistString() {
        if (artists == null || artists.isEmpty()) return "Unknown Artist";
        return String.join(", ", artists);
    }
}
