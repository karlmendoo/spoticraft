package com.spoticraft.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spoticraft.SpotiCraftMod;
import com.spoticraft.api.models.*;
import com.spoticraft.util.HttpHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * All Spotify Web API REST calls.
 * Every method returns a CompletableFuture and executes on background threads.
 * The game/render thread is never blocked.
 */
public class SpotifyAPI {

    private static final String BASE_URL = "https://api.spotify.com/v1";
    private static final SpotifyAPI INSTANCE = new SpotifyAPI();

    // Thread-safe current playback state
    private final AtomicReference<PlaybackState> playbackState = new AtomicReference<>(new PlaybackState());

    // Callbacks for UI updates
    private volatile Consumer<PlaybackState> playbackStateListener;
    private volatile Consumer<String> errorListener;

    private SpotifyAPI() {}

    public static SpotifyAPI getInstance() {
        return INSTANCE;
    }

    public PlaybackState getPlaybackState() {
        return playbackState.get();
    }

    public void setPlaybackStateListener(Consumer<PlaybackState> listener) {
        this.playbackStateListener = listener;
    }

    public void setErrorListener(Consumer<String> listener) {
        this.errorListener = listener;
    }

    // ── Helper: run an authenticated API call with auto-retry on token expiry ─

    private CompletableFuture<String> authedGet(String url) {
        return SpotifyAuth.getInstance().getValidToken()
                .thenCompose(token -> HttpHelper.getAsync(url, token))
                .exceptionally(e -> {
                    handleApiError(e);
                    return null;
                });
    }

    private CompletableFuture<String> authedPost(String url, String body) {
        return SpotifyAuth.getInstance().getValidToken()
                .thenCompose(token -> HttpHelper.postAsync(url, token, body))
                .exceptionally(e -> {
                    handleApiError(e);
                    return null;
                });
    }

    private CompletableFuture<String> authedPut(String url, String body) {
        return SpotifyAuth.getInstance().getValidToken()
                .thenCompose(token -> HttpHelper.putAsync(url, token, body))
                .exceptionally(e -> {
                    handleApiError(e);
                    return null;
                });
    }

    private void handleApiError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) msg = e.getCause() != null ? e.getCause().getMessage() : "Unknown error";

        if (msg.contains("TOKEN_EXPIRED")) {
            SpotiCraftMod.LOGGER.warn("Token expired during API call, refreshing...");
        } else if (msg.contains("NO_PREMIUM")) {
            notify("Playback control requires Spotify Premium.");
        } else if (msg.contains("NO_DEVICE")) {
            notify("No active Spotify device found. Open Spotify on a device first.");
        } else if (msg.contains("RATE_LIMITED")) {
            notify("Rate limited by Spotify. Please wait a moment.");
        } else if (msg.contains("Not authenticated")) {
            notify("Not connected to Spotify. Please authenticate in Settings.");
        } else {
            SpotiCraftMod.LOGGER.warn("Spotify API error: {}", msg);
        }
    }

    private void notify(String message) {
        if (errorListener != null) {
            errorListener.accept(message);
        }
    }

    // ── Playback State ────────────────────────────────────────────────────────

    /**
     * Fetch the current playback state from Spotify.
     * This is polled periodically to keep the UI in sync.
     */
    public CompletableFuture<PlaybackState> fetchPlaybackState() {
        return authedGet(BASE_URL + "/me/player")
                .thenApply(json -> {
                    if (json == null || json.isBlank()) {
                        PlaybackState empty = new PlaybackState();
                        playbackState.set(empty);
                        return empty;
                    }
                    PlaybackState state = parsePlaybackState(json);
                    PlaybackState old = playbackState.getAndSet(state);

                    // Notify listener if state changed
                    if (playbackStateListener != null) {
                        playbackStateListener.accept(state);
                    }

                    return state;
                });
    }

    private PlaybackState parsePlaybackState(String json) {
        PlaybackState state = new PlaybackState();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            state.hasActiveDevice = true;
            state.isPlaying = obj.has("is_playing") && obj.get("is_playing").getAsBoolean();
            state.shuffleState = obj.has("shuffle_state") && obj.get("shuffle_state").getAsBoolean();
            state.repeatState = obj.has("repeat_state") ? obj.get("repeat_state").getAsString() : "off";
            state.progressMs = obj.has("progress_ms") ? obj.get("progress_ms").getAsLong() : 0;

            if (obj.has("device") && !obj.get("device").isJsonNull()) {
                JsonObject device = obj.getAsJsonObject("device");
                state.deviceName = device.has("name") ? device.get("name").getAsString() : "Unknown";
                state.volumePercent = device.has("volume_percent") ? device.get("volume_percent").getAsInt() : 50;
            }

            if (obj.has("item") && !obj.get("item").isJsonNull()) {
                state.currentTrack = parseTrack(obj.getAsJsonObject("item"));
                if (state.currentTrack != null) {
                    state.currentTrack.progressMs = state.progressMs;
                }
            }
        } catch (Exception e) {
            SpotiCraftMod.LOGGER.warn("Failed to parse playback state: {}", e.getMessage());
        }
        return state;
    }

    private Track parseTrack(JsonObject item) {
        try {
            String id = item.get("id").getAsString();
            String name = item.get("name").getAsString();
            long duration = item.get("duration_ms").getAsLong();

            List<String> artists = new ArrayList<>();
            if (item.has("artists")) {
                for (JsonElement a : item.getAsJsonArray("artists")) {
                    artists.add(a.getAsJsonObject().get("name").getAsString());
                }
            }

            String albumName = "";
            String albumArtUrl = "";
            if (item.has("album") && !item.get("album").isJsonNull()) {
                JsonObject album = item.getAsJsonObject("album");
                albumName = album.has("name") ? album.get("name").getAsString() : "";
                if (album.has("images")) {
                    JsonArray images = album.getAsJsonArray("images");
                    if (!images.isEmpty()) {
                        // Pick medium size (index 1) or first available
                        int idx = images.size() > 1 ? 1 : 0;
                        albumArtUrl = images.get(idx).getAsJsonObject().get("url").getAsString();
                    }
                }
            }

            return new Track(id, name, artists, albumName, albumArtUrl, duration, 0);
        } catch (Exception e) {
            SpotiCraftMod.LOGGER.warn("Failed to parse track: {}", e.getMessage());
            return null;
        }
    }

    // ── Playback Controls ─────────────────────────────────────────────────────

    /** Toggle play/pause. */
    public CompletableFuture<Void> togglePlayPause() {
        PlaybackState state = playbackState.get();
        if (state.isPlaying) {
            return pause();
        } else {
            return resume();
        }
    }

    /** Pause playback. */
    public CompletableFuture<Void> pause() {
        return authedPut(BASE_URL + "/me/player/pause", null).thenApply(r -> null);
    }

    /** Resume playback. */
    public CompletableFuture<Void> resume() {
        return authedPut(BASE_URL + "/me/player/play", null).thenApply(r -> null);
    }

    /** Skip to next track. */
    public CompletableFuture<Void> skipToNext() {
        return authedPost(BASE_URL + "/me/player/next", null).thenApply(r -> null);
    }

    /** Go to previous track. */
    public CompletableFuture<Void> skipToPrevious() {
        return authedPost(BASE_URL + "/me/player/previous", null).thenApply(r -> null);
    }

    /**
     * Seek to position in current track.
     * @param positionMs position in milliseconds
     */
    public CompletableFuture<Void> seekTo(long positionMs) {
        String url = BASE_URL + "/me/player/seek?position_ms=" + positionMs;
        return authedPut(url, null).thenApply(r -> null);
    }

    /**
     * Set volume.
     * @param volumePercent 0-100
     */
    public CompletableFuture<Void> setVolume(int volumePercent) {
        int clamped = Math.max(0, Math.min(100, volumePercent));
        String url = BASE_URL + "/me/player/volume?volume_percent=" + clamped;
        return authedPut(url, null).thenApply(r -> null);
    }

    /**
     * Adjust volume by a delta (positive = up, negative = down).
     */
    public CompletableFuture<Void> adjustVolume(int delta) {
        int current = playbackState.get().volumePercent;
        return setVolume(current + delta);
    }

    /**
     * Toggle shuffle on/off.
     */
    public CompletableFuture<Void> toggleShuffle() {
        boolean newState = !playbackState.get().shuffleState;
        String url = BASE_URL + "/me/player/shuffle?state=" + newState;
        return authedPut(url, null).thenApply(r -> null);
    }

    /**
     * Cycle repeat mode: off → context → track → off.
     */
    public CompletableFuture<Void> cycleRepeat() {
        String current = playbackState.get().repeatState;
        String next = switch (current) {
            case "off" -> "context";
            case "context" -> "track";
            default -> "off";
        };
        String url = BASE_URL + "/me/player/repeat?state=" + next;
        return authedPut(url, null).thenApply(r -> null);
    }

    /**
     * Play a specific Spotify URI (track, album, playlist, artist).
     */
    public CompletableFuture<Void> playUri(String uri) {
        String body;
        if (uri.startsWith("spotify:track:")) {
            body = "{\"uris\":[\"" + uri + "\"]}";
        } else {
            body = "{\"context_uri\":\"" + uri + "\"}";
        }
        return authedPut(BASE_URL + "/me/player/play", body).thenApply(r -> null);
    }

    // ── Library ───────────────────────────────────────────────────────────────

    /**
     * Get the current user's playlists (paginated).
     */
    public CompletableFuture<List<Playlist>> getUserPlaylists(int limit, int offset) {
        String url = BASE_URL + "/me/playlists?limit=" + limit + "&offset=" + offset;
        return authedGet(url).thenApply(json -> {
            if (json == null) return new ArrayList<>();
            return parsePlaylists(json);
        });
    }

    private List<Playlist> parsePlaylists(String json) {
        List<Playlist> result = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray items = obj.getAsJsonArray("items");
            for (JsonElement el : items) {
                JsonObject p = el.getAsJsonObject();
                String id = p.get("id").getAsString();
                String name = p.get("name").getAsString();
                String desc = p.has("description") ? p.get("description").getAsString() : "";
                String uri = p.get("uri").getAsString();
                String imageUrl = "";
                if (p.has("images") && p.getAsJsonArray("images").size() > 0) {
                    imageUrl = p.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
                }
                int trackCount = 0;
                if (p.has("tracks") && !p.get("tracks").isJsonNull()) {
                    trackCount = p.getAsJsonObject("tracks").get("total").getAsInt();
                }
                String owner = "";
                if (p.has("owner") && !p.get("owner").isJsonNull()) {
                    owner = p.getAsJsonObject("owner").get("display_name").getAsString();
                }
                result.add(new Playlist(id, name, desc, uri, imageUrl, trackCount, owner));
            }
        } catch (Exception e) {
            SpotiCraftMod.LOGGER.warn("Failed to parse playlists: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Get tracks in a playlist.
     */
    public CompletableFuture<List<Track>> getPlaylistTracks(String playlistId, int limit, int offset) {
        String url = BASE_URL + "/playlists/" + playlistId + "/tracks?limit=" + limit + "&offset=" + offset;
        return authedGet(url).thenApply(json -> {
            if (json == null) return new ArrayList<>();
            return parsePlaylistTracks(json);
        });
    }

    private List<Track> parsePlaylistTracks(String json) {
        List<Track> result = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray items = obj.getAsJsonArray("items");
            for (JsonElement el : items) {
                JsonObject item = el.getAsJsonObject();
                if (!item.has("track") || item.get("track").isJsonNull()) continue;
                JsonObject track = item.getAsJsonObject("track");
                if (track.isJsonNull() || !track.has("id")) continue;
                Track t = parseTrack(track);
                if (t != null) result.add(t);
            }
        } catch (Exception e) {
            SpotiCraftMod.LOGGER.warn("Failed to parse playlist tracks: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Get user's saved/liked tracks.
     */
    public CompletableFuture<List<Track>> getSavedTracks(int limit, int offset) {
        String url = BASE_URL + "/me/tracks?limit=" + limit + "&offset=" + offset;
        return authedGet(url).thenApply(json -> {
            if (json == null) return new ArrayList<>();
            return parsePlaylistTracks(json); // same structure as playlist items
        });
    }

    /**
     * Get user's recently played tracks.
     */
    public CompletableFuture<List<Track>> getRecentlyPlayed(int limit) {
        String url = BASE_URL + "/me/player/recently-played?limit=" + limit;
        return authedGet(url).thenApply(json -> {
            if (json == null) return new ArrayList<>();
            List<Track> result = new ArrayList<>();
            try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                JsonArray items = obj.getAsJsonArray("items");
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    if (!item.has("track") || item.get("track").isJsonNull()) continue;
                    Track t = parseTrack(item.getAsJsonObject("track"));
                    if (t != null) result.add(t);
                }
            } catch (Exception e) {
                SpotiCraftMod.LOGGER.warn("Failed to parse recently played: {}", e.getMessage());
            }
            return result;
        });
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Search Spotify for tracks, albums, artists, and playlists.
     */
    public CompletableFuture<SearchResult> search(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BASE_URL + "/search?q=" + encoded + "&type=track,album,artist,playlist&limit=" + limit;

        return authedGet(url).thenApply(json -> {
            if (json == null) return new SearchResult();
            return parseSearchResult(json);
        });
    }

    private SearchResult parseSearchResult(String json) {
        SearchResult result = new SearchResult();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            // Tracks
            if (obj.has("tracks") && !obj.get("tracks").isJsonNull()) {
                result.tracks = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonObject("tracks").getAsJsonArray("items")) {
                    Track t = parseTrack(el.getAsJsonObject());
                    if (t != null) result.tracks.add(t);
                }
            }

            // Albums
            if (obj.has("albums") && !obj.get("albums").isJsonNull()) {
                result.albums = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonObject("albums").getAsJsonArray("items")) {
                    JsonObject a = el.getAsJsonObject();
                    Album album = new Album();
                    album.id = a.get("id").getAsString();
                    album.name = a.get("name").getAsString();
                    album.uri = a.get("uri").getAsString();
                    album.totalTracks = a.has("total_tracks") ? a.get("total_tracks").getAsInt() : 0;
                    album.releaseDate = a.has("release_date") ? a.get("release_date").getAsString() : "";
                    album.artists = new ArrayList<>();
                    if (a.has("artists")) {
                        for (JsonElement ar : a.getAsJsonArray("artists")) {
                            album.artists.add(ar.getAsJsonObject().get("name").getAsString());
                        }
                    }
                    if (a.has("images") && a.getAsJsonArray("images").size() > 0) {
                        album.imageUrl = a.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
                    }
                    result.albums.add(album);
                }
            }

            // Artists
            if (obj.has("artists") && !obj.get("artists").isJsonNull()) {
                result.artists = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonObject("artists").getAsJsonArray("items")) {
                    JsonObject a = el.getAsJsonObject();
                    Artist artist = new Artist();
                    artist.id = a.get("id").getAsString();
                    artist.name = a.get("name").getAsString();
                    artist.uri = a.get("uri").getAsString();
                    if (a.has("followers") && !a.get("followers").isJsonNull()) {
                        artist.followers = a.getAsJsonObject("followers").get("total").getAsInt();
                    }
                    if (a.has("images") && a.getAsJsonArray("images").size() > 0) {
                        artist.imageUrl = a.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
                    }
                    result.artists.add(artist);
                }
            }

            // Playlists
            if (obj.has("playlists") && !obj.get("playlists").isJsonNull()) {
                result.playlists = parsePlaylists(
                        "{\"items\":" + obj.getAsJsonObject("playlists").getAsJsonArray("items").toString() + "}");
            }

        } catch (Exception e) {
            SpotiCraftMod.LOGGER.warn("Failed to parse search results: {}", e.getMessage());
        }
        return result;
    }
}
