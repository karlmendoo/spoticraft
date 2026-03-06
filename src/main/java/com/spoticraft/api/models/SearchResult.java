package com.spoticraft.api.models;

import java.util.List;

/**
 * Aggregated search result from the Spotify search endpoint.
 */
public class SearchResult {
    public List<Track> tracks;
    public List<Album> albums;
    public List<Artist> artists;
    public List<Playlist> playlists;

    public SearchResult() {}
}
