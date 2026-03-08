package io.github.karlmendoo.spoticraft.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.karlmendoo.spoticraft.SpotiCraftClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client for the Spotify Web API.
 * Handles all API requests including playback control, browsing, and search.
 */
public class SpotifyAPI {
    private static final String BASE_URL = "https://api.spotify.com/v1";
    private final SpotifyAuthManager authManager;
    private final HttpClient httpClient;

    public SpotifyAPI(SpotifyAuthManager authManager) {
        this.authManager = authManager;
        this.httpClient = HttpClient.newHttpClient();
    }

    // ===== Playback Control =====

    public CompletableFuture<JsonObject> getCurrentPlayback() {
        return getAsync("/me/player");
    }

    public CompletableFuture<Boolean> play() {
        return putAsync("/me/player/play", "");
    }

    public CompletableFuture<Boolean> pause() {
        return putAsync("/me/player/pause", "");
    }

    public CompletableFuture<Boolean> next() {
        return postAsync("/me/player/next", "");
    }

    public CompletableFuture<Boolean> previous() {
        return postAsync("/me/player/previous", "");
    }

    public CompletableFuture<Boolean> seek(long positionMs) {
        return putAsync("/me/player/seek?position_ms=" + positionMs, "");
    }

    public CompletableFuture<Boolean> setVolume(int volumePercent) {
        return putAsync("/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, volumePercent)), "");
    }

    public CompletableFuture<Boolean> setShuffle(boolean state) {
        return putAsync("/me/player/shuffle?state=" + state, "");
    }

    public CompletableFuture<Boolean> setRepeat(String state) {
        // state: "track", "context", or "off"
        return putAsync("/me/player/repeat?state=" + state, "");
    }

    public CompletableFuture<Boolean> playContext(String contextUri) {
        String body = "{\"context_uri\":\"" + contextUri + "\"}";
        return putAsync("/me/player/play", body);
    }

    public CompletableFuture<Boolean> playTrack(String trackUri) {
        String body = "{\"uris\":[\"" + trackUri + "\"]}";
        return putAsync("/me/player/play", body);
    }

    // ===== Library & Browse =====

    public CompletableFuture<JsonObject> getUserPlaylists(int limit, int offset) {
        return getAsync("/me/playlists?limit=" + limit + "&offset=" + offset);
    }

    public CompletableFuture<JsonObject> getLikedSongs(int limit, int offset) {
        return getAsync("/me/tracks?limit=" + limit + "&offset=" + offset);
    }

    public CompletableFuture<JsonObject> getRecentlyPlayed(int limit) {
        return getAsync("/me/player/recently-played?limit=" + limit);
    }

    public CompletableFuture<JsonObject> getPlaylistTracks(String playlistId, int limit, int offset) {
        return getAsync("/playlists/" + playlistId + "/tracks?limit=" + limit + "&offset=" + offset);
    }

    // ===== Search =====

    public CompletableFuture<JsonObject> search(String query, String types, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return getAsync("/search?q=" + encoded + "&type=" + types + "&limit=" + limit);
    }

    // ===== HTTP Helpers =====

    private CompletableFuture<JsonObject> getAsync(String endpoint) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = authManager.getValidAccessToken();
                if (token == null) return null;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + endpoint))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 && !response.body().isEmpty()) {
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                } else if (response.statusCode() == 204) {
                    return new JsonObject(); // No content (e.g., no active device)
                } else if (response.statusCode() == 401) {
                    // Token expired, try refresh
                    if (authManager.refreshAccessToken()) {
                        return getAsync(endpoint).join(); // Retry once
                    }
                }
                SpotiCraftClient.LOGGER.debug("API GET {} returned {}", endpoint, response.statusCode());
                return null;
            } catch (Exception e) {
                SpotiCraftClient.LOGGER.error("API GET error: {}", endpoint, e);
                return null;
            }
        });
    }

    private CompletableFuture<Boolean> putAsync(String endpoint, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = authManager.getValidAccessToken();
                if (token == null) return false;

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + endpoint))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json");

                if (body.isEmpty()) {
                    builder.PUT(HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.PUT(HttpRequest.BodyPublishers.ofString(body));
                }

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 || response.statusCode() == 204 || response.statusCode() == 202;
            } catch (Exception e) {
                SpotiCraftClient.LOGGER.error("API PUT error: {}", endpoint, e);
                return false;
            }
        });
    }

    private CompletableFuture<Boolean> postAsync(String endpoint, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = authManager.getValidAccessToken();
                if (token == null) return false;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + endpoint))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 || response.statusCode() == 204 || response.statusCode() == 202;
            } catch (Exception e) {
                SpotiCraftClient.LOGGER.error("API POST error: {}", endpoint, e);
                return false;
            }
        });
    }

    // ===== Parsing Helpers =====

    /**
     * Parses a track JSON object into a TrackInfo record.
     */
    public static TrackInfo parseTrack(JsonObject trackObj) {
        if (trackObj == null) return null;
        try {
            String name = trackObj.has("name") ? trackObj.get("name").getAsString() : "Unknown";
            String uri = trackObj.has("uri") ? trackObj.get("uri").getAsString() : "";

            StringBuilder artists = new StringBuilder();
            if (trackObj.has("artists")) {
                JsonArray arr = trackObj.getAsJsonArray("artists");
                for (int i = 0; i < arr.size(); i++) {
                    if (i > 0) artists.append(", ");
                    artists.append(arr.get(i).getAsJsonObject().get("name").getAsString());
                }
            }

            String albumName = "";
            String albumArtUrl = "";
            if (trackObj.has("album")) {
                JsonObject album = trackObj.getAsJsonObject("album");
                albumName = album.has("name") ? album.get("name").getAsString() : "";
                if (album.has("images")) {
                    JsonArray images = album.getAsJsonArray("images");
                    if (!images.isEmpty()) {
                        // Use the smallest image (last in array) for performance
                        albumArtUrl = images.get(images.size() - 1).getAsJsonObject().get("url").getAsString();
                    }
                }
            }

            long durationMs = trackObj.has("duration_ms") ? trackObj.get("duration_ms").getAsLong() : 0;

            return new TrackInfo(name, artists.toString(), albumName, albumArtUrl, uri, durationMs);
        } catch (Exception e) {
            SpotiCraftClient.LOGGER.error("Failed to parse track", e);
            return null;
        }
    }

    public static List<PlaylistInfo> parsePlaylists(JsonObject response) {
        List<PlaylistInfo> playlists = new ArrayList<>();
        if (response == null || !response.has("items")) return playlists;
        for (JsonElement item : response.getAsJsonArray("items")) {
            JsonObject obj = item.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "Unknown";
            String id = obj.has("id") ? obj.get("id").getAsString() : "";
            String uri = obj.has("uri") ? obj.get("uri").getAsString() : "";
            int totalTracks = 0;
            if (obj.has("tracks") && obj.getAsJsonObject("tracks").has("total")) {
                totalTracks = obj.getAsJsonObject("tracks").get("total").getAsInt();
            }
            String imageUrl = "";
            if (obj.has("images") && !obj.getAsJsonArray("images").isEmpty()) {
                imageUrl = obj.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
            }
            playlists.add(new PlaylistInfo(name, id, uri, totalTracks, imageUrl));
        }
        return playlists;
    }

    // ===== Data Records =====

    public record TrackInfo(String name, String artist, String album, String albumArtUrl, String uri, long durationMs) {}
    public record PlaylistInfo(String name, String id, String uri, int totalTracks, String imageUrl) {}
}
