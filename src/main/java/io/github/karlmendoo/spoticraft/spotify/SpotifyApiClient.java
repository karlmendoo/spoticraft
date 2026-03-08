package io.github.karlmendoo.spoticraft.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.karlmendoo.spoticraft.spotify.model.LibraryItem;
import io.github.karlmendoo.spoticraft.spotify.model.LibrarySnapshot;
import io.github.karlmendoo.spoticraft.spotify.model.PlaybackSnapshot;
import io.github.karlmendoo.spoticraft.spotify.model.RepeatMode;
import io.github.karlmendoo.spoticraft.spotify.model.SearchSnapshot;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SpotifyApiClient {
    private static final String API_ROOT = "https://api.spotify.com/v1";

    private final SpotifyAuthManager authManager;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public SpotifyApiClient(SpotifyAuthManager authManager, Logger logger) {
        this.authManager = authManager;
        this.logger = logger;
    }

    public PlaybackSnapshot fetchPlayback() throws IOException, InterruptedException, SpotifyApiException {
        JsonObject json = send("GET", API_ROOT + "/me/player", null, true);
        if (json == null || json.isJsonNull()) {
            return PlaybackSnapshot.empty();
        }

        JsonObject device = object(json, "device");
        JsonObject item = object(json, "item");
        JsonObject album = object(item, "album");
        JsonArray artists = array(item, "artists");

        String imageUrl = extractFirstImage(album);
        String trackId = string(item, "id");
        String trackUri = string(item, "uri");
        String title = string(item, "name");
        String artist = artists.isEmpty() ? "Unknown artist" : string(artists.get(0).getAsJsonObject(), "name");
        String albumName = string(album, "name");
        boolean hasDevice = !device.entrySet().isEmpty();
        return new PlaybackSnapshot(
            trackId,
            trackUri,
            title.isBlank() ? "Nothing playing" : title,
            artist.isBlank() ? "Unknown artist" : artist,
            albumName,
            imageUrl,
            json.has("is_playing") && json.get("is_playing").getAsBoolean(),
            integer(json, "progress_ms", 0),
            integer(item, "duration_ms", 1),
            integer(device, "volume_percent", 70),
            json.has("shuffle_state") && json.get("shuffle_state").getAsBoolean(),
            RepeatMode.fromApiValue(string(json, "repeat_state")),
            string(device, "name").isBlank() ? "No active device" : string(device, "name"),
            string(device, "id"),
            hasDevice
        );
    }

    public LibrarySnapshot fetchLibrary() throws IOException, InterruptedException, SpotifyApiException {
        JsonObject playlists = send("GET", API_ROOT + "/me/playlists?limit=12", null, false);
        JsonObject liked = send("GET", API_ROOT + "/me/tracks?limit=12", null, false);
        JsonObject recent = send("GET", API_ROOT + "/me/player/recently-played?limit=12", null, false);
        return new LibrarySnapshot(
            parsePlaylists(array(playlists, "items")),
            parseLiked(array(liked, "items")),
            parseRecent(array(recent, "items"))
        );
    }

    public SearchSnapshot search(String query) throws IOException, InterruptedException, SpotifyApiException {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        JsonObject json = send("GET", API_ROOT + "/search?q=" + encoded + "&type=track,album,artist,playlist&limit=4", null, false);
        return new SearchSnapshot(
            parseTracks(array(object(json, "tracks"), "items")),
            parseAlbums(array(object(json, "albums"), "items")),
            parseArtists(array(object(json, "artists"), "items")),
            parsePlaylists(array(object(json, "playlists"), "items"))
        );
    }

    public void togglePlayPause(PlaybackSnapshot playback) throws IOException, InterruptedException, SpotifyApiException {
        send(playback.isPlaying() ? "PUT" : "PUT", API_ROOT + (playback.isPlaying() ? "/me/player/pause" : "/me/player/play"), null, false);
    }

    public void nextTrack() throws IOException, InterruptedException, SpotifyApiException {
        send("POST", API_ROOT + "/me/player/next", null, false);
    }

    public void previousTrack() throws IOException, InterruptedException, SpotifyApiException {
        send("POST", API_ROOT + "/me/player/previous", null, false);
    }

    public void setShuffle(boolean enabled) throws IOException, InterruptedException, SpotifyApiException {
        send("PUT", API_ROOT + "/me/player/shuffle?state=" + enabled, null, false);
    }

    public void setRepeat(RepeatMode repeatMode) throws IOException, InterruptedException, SpotifyApiException {
        send("PUT", API_ROOT + "/me/player/repeat?state=" + repeatMode.apiValue(), null, false);
    }

    public void setVolume(int volumePercent) throws IOException, InterruptedException, SpotifyApiException {
        send("PUT", API_ROOT + "/me/player/volume?volume_percent=" + volumePercent, null, false);
    }

    public void play(LibraryItem item) throws IOException, InterruptedException, SpotifyApiException {
        JsonObject body = new JsonObject();
        switch (item.kind()) {
            case TRACK -> {
                JsonArray uris = new JsonArray();
                uris.add(item.uri());
                body.add("uris", uris);
            }
            case ALBUM, PLAYLIST -> body.addProperty("context_uri", item.uri());
            case ARTIST -> throw new SpotifyApiException(400, "Artists are shown for discovery. Play one of their tracks or albums.");
        }
        send("PUT", API_ROOT + "/me/player/play", body, false);
    }

    private JsonObject send(String method, String url, JsonObject body, boolean allowNoContent) throws IOException, InterruptedException, SpotifyApiException {
        this.authManager.ensureAccessToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + this.authManager.accessToken())
            .header("Content-Type", "application/json");

        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(this.gson.toJson(body)));
            case "PUT" -> builder.PUT(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(this.gson.toJson(body)));
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        HttpResponse<String> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 204 && allowNoContent) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new SpotifyApiException(response.statusCode(), extractErrorMessage(response.body(), response.statusCode()));
        }
        if (response.body() == null || response.body().isBlank()) {
            return new JsonObject();
        }
        return this.gson.fromJson(response.body(), JsonObject.class);
    }

    private String extractErrorMessage(String body, int status) {
        try {
            JsonObject json = this.gson.fromJson(body, JsonObject.class);
            if (json != null && json.has("error")) {
                JsonElement error = json.get("error");
                if (error.isJsonPrimitive()) {
                    return error.getAsString();
                }
                JsonObject errorObject = error.getAsJsonObject();
                if (errorObject.has("message")) {
                    return errorObject.get("message").getAsString();
                }
            }
        } catch (Exception exception) {
            this.logger.debug("Failed to parse Spotify error response", exception);
        }
        if (status == 404) {
            return "No active Spotify device is available. Start playback on any Spotify device first.";
        }
        if (status == 401) {
            return "Spotify session expired. Reconnect your account.";
        }
        return "Spotify request failed with status " + status + '.';
    }

    private static List<LibraryItem> parsePlaylists(JsonArray items) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject playlist = element.getAsJsonObject();
            result.add(new LibraryItem(
                LibraryItem.Kind.PLAYLIST,
                string(playlist, "id"),
                string(playlist, "uri"),
                string(playlist, "name"),
                ownerName(playlist),
                integer(object(playlist, "tracks"), "total", 0) + " tracks",
                extractFirstImage(playlist),
                true
            ));
        }
        return result;
    }

    private static List<LibraryItem> parseLiked(JsonArray items) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject saved = element.getAsJsonObject();
            JsonObject track = object(saved, "track");
            result.add(toTrackItem(track));
        }
        return result;
    }

    private static List<LibraryItem> parseRecent(JsonArray items) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject history = element.getAsJsonObject();
            JsonObject track = object(history, "track");
            result.add(toTrackItem(track));
        }
        return result;
    }

    private static List<LibraryItem> parseTracks(JsonArray items) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            result.add(toTrackItem(element.getAsJsonObject()));
        }
        return result;
    }

    private static LibraryItem toTrackItem(JsonObject track) {
        JsonObject album = object(track, "album");
        JsonArray artists = array(track, "artists");
        String artist = artists.isEmpty() ? "Unknown artist" : string(artists.get(0).getAsJsonObject(), "name");
        return new LibraryItem(
            LibraryItem.Kind.TRACK,
            string(track, "id"),
            string(track, "uri"),
            string(track, "name"),
            artist,
            string(album, "name"),
            extractFirstImage(album),
            true
        );
    }

    private static List<LibraryItem> parseAlbums(JsonArray items) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject album = element.getAsJsonObject();
            JsonArray artists = array(album, "artists");
            String artist = artists.isEmpty() ? "Unknown artist" : string(artists.get(0).getAsJsonObject(), "name");
            result.add(new LibraryItem(
                LibraryItem.Kind.ALBUM,
                string(album, "id"),
                string(album, "uri"),
                string(album, "name"),
                artist,
                string(album, "album_type"),
                extractFirstImage(album),
                true
            ));
        }
        return result;
    }

    private static List<LibraryItem> parseArtists(JsonArray items) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject artist = element.getAsJsonObject();
            result.add(new LibraryItem(
                LibraryItem.Kind.ARTIST,
                string(artist, "id"),
                string(artist, "uri"),
                string(artist, "name"),
                "Artist",
                integer(artist, "followers", 0) + " followers",
                extractFirstImage(artist),
                false
            ));
        }
        return result;
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return new JsonObject();
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return new JsonArray();
        }
        return parent.getAsJsonArray(key);
    }

    private static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return "";
        }
        return parent.get(key).getAsString();
    }

    private static int integer(JsonObject parent, String key, int fallback) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement element = parent.get(key);
        if (element.isJsonObject() && element.getAsJsonObject().has("total")) {
            return element.getAsJsonObject().get("total").getAsInt();
        }
        return element.getAsInt();
    }

    private static String ownerName(JsonObject playlist) {
        JsonObject owner = object(playlist, "owner");
        String displayName = string(owner, "display_name");
        return displayName.isBlank() ? "Spotify" : displayName;
    }

    private static String extractFirstImage(JsonObject parent) {
        JsonArray images = array(parent, "images");
        if (images.isEmpty()) {
            return "";
        }
        return string(images.get(0).getAsJsonObject(), "url");
    }
}
