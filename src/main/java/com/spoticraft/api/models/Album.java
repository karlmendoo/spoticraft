package com.spoticraft.api.models;

import java.util.List;

/**
 * Represents a Spotify album.
 */
public class Album {
    public String id;
    public String name;
    public String uri;
    public String imageUrl;
    public List<String> artists;
    public int totalTracks;
    public String releaseDate;

    public Album() {}

    public String getArtistString() {
        if (artists == null || artists.isEmpty()) return "Unknown Artist";
        return String.join(", ", artists);
    }
}
