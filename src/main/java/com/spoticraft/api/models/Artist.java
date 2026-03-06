package com.spoticraft.api.models;

import java.util.List;

/**
 * Represents a Spotify artist.
 */
public class Artist {
    public String id;
    public String name;
    public String uri;
    public String imageUrl;
    public int followers;
    public List<String> genres;

    public Artist() {}
}
