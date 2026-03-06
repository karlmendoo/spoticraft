package com.spoticraft.api.models;

/**
 * Represents a Spotify playlist.
 */
public class Playlist {
    public String id;
    public String name;
    public String description;
    public String uri;
    public String imageUrl;
    public int trackCount;
    public String ownerName;

    public Playlist() {}

    public Playlist(String id, String name, String description, String uri,
                    String imageUrl, int trackCount, String ownerName) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.uri = uri;
        this.imageUrl = imageUrl;
        this.trackCount = trackCount;
        this.ownerName = ownerName;
    }
}
