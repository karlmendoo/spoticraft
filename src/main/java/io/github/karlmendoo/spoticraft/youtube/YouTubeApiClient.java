package io.github.karlmendoo.spoticraft.youtube;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.karlmendoo.spoticraft.youtube.model.LibraryItem;
import io.github.karlmendoo.spoticraft.youtube.model.LibrarySnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.SearchSnapshot;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class YouTubeApiClient {
    private static final String API_ROOT = "https://www.googleapis.com/youtube/v3";

    private final YouTubeAuthManager authManager;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    public YouTubeApiClient(YouTubeAuthManager authManager, Logger logger) {
        this.authManager = authManager;
        this.logger = logger;
    }

    public LibrarySnapshot fetchLibrary(List<LibraryItem> recentlyPlayed) throws IOException, InterruptedException, YouTubeApiException {
        return new LibrarySnapshot(fetchMyPlaylists(), fetchLikedVideos(), recentlyPlayed);
    }

    public SearchSnapshot search(String query) throws IOException, InterruptedException, YouTubeApiException {
        List<LibraryItem> tracks = searchVideos(query, 4);
        List<LibraryItem> playlists = searchPlaylists(query, 6);
        List<LibraryItem> artists = searchChannels(query, 4);
        List<LibraryItem> collections = deriveCollections(playlists);
        return new SearchSnapshot(tracks, collections, artists, limit(playlists, 4));
    }

    public ResolvedPlayback resolvePlayback(LibraryItem item) throws IOException, InterruptedException, YouTubeApiException {
        return switch (item.kind()) {
            case TRACK -> new ResolvedPlayback(fetchVideosByIds(List.of(item.id()), null, item.detail(), item.title()), 0);
            case ALBUM, PLAYLIST -> new ResolvedPlayback(fetchPlaylistVideos(item.id(), item.title()), 0);
            case ARTIST -> throw new YouTubeApiException(400, "Channels are shown for discovery. Play a video or playlist instead.");
        };
    }

    private List<LibraryItem> fetchMyPlaylists() throws IOException, InterruptedException, YouTubeApiException {
        JsonObject json = send("/playlists", Map.of(
            "part", "snippet,contentDetails",
            "mine", "true",
            "maxResults", "12"
        ));
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            JsonObject playlist = element.getAsJsonObject();
            JsonObject snippet = object(playlist, "snippet");
            JsonObject contentDetails = object(playlist, "contentDetails");
            result.add(new LibraryItem(
                LibraryItem.Kind.PLAYLIST,
                string(playlist, "id"),
                playlistUrl(string(playlist, "id")),
                defaultIfBlank(string(snippet, "title"), "Untitled playlist"),
                defaultIfBlank(string(snippet, "channelTitle"), "YouTube"),
                integer(contentDetails, "itemCount", 0) + " videos",
                thumbnailUrl(snippet),
                true
            ));
        }
        return result;
    }

    private List<LibraryItem> fetchLikedVideos() throws IOException, InterruptedException, YouTubeApiException {
        JsonObject json = send("/videos", Map.of(
            "part", "snippet,contentDetails",
            "myRating", "like",
            "maxResults", "12"
        ));
        return toTrackItems(array(json, "items"), null, "Liked videos");
    }

    private List<LibraryItem> searchVideos(String query, int limit) throws IOException, InterruptedException, YouTubeApiException {
        JsonObject json = send("/search", Map.of(
            "part", "snippet",
            "q", query,
            "type", "video",
            "maxResults", Integer.toString(limit)
        ));
        List<String> ids = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            JsonObject id = object(element.getAsJsonObject(), "id");
            String videoId = string(id, "videoId");
            if (!videoId.isBlank()) {
                ids.add(videoId);
            }
        }
        return toTrackItems(fetchVideosJson(ids), null, "YouTube");
    }

    private List<LibraryItem> searchPlaylists(String query, int limit) throws IOException, InterruptedException, YouTubeApiException {
        JsonObject json = send("/search", Map.of(
            "part", "snippet",
            "q", query,
            "type", "playlist",
            "maxResults", Integer.toString(limit)
        ));
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject id = object(item, "id");
            JsonObject snippet = object(item, "snippet");
            String playlistId = string(id, "playlistId");
            if (playlistId.isBlank()) {
                continue;
            }
            result.add(new LibraryItem(
                LibraryItem.Kind.PLAYLIST,
                playlistId,
                playlistUrl(playlistId),
                defaultIfBlank(string(snippet, "title"), "Untitled playlist"),
                defaultIfBlank(string(snippet, "channelTitle"), "YouTube"),
                "Playlist",
                thumbnailUrl(snippet),
                true
            ));
        }
        return result;
    }

    private List<LibraryItem> searchChannels(String query, int limit) throws IOException, InterruptedException, YouTubeApiException {
        JsonObject json = send("/search", Map.of(
            "part", "snippet",
            "q", query,
            "type", "channel",
            "maxResults", Integer.toString(limit)
        ));
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject id = object(item, "id");
            JsonObject snippet = object(item, "snippet");
            String channelId = string(id, "channelId");
            if (channelId.isBlank()) {
                continue;
            }
            result.add(new LibraryItem(
                LibraryItem.Kind.ARTIST,
                channelId,
                channelUrl(channelId),
                defaultIfBlank(string(snippet, "title"), "Unnamed channel"),
                "Channel",
                "Open videos or playlists from search results",
                thumbnailUrl(snippet),
                false
            ));
        }
        return result;
    }

    private List<PlayableMedia> fetchPlaylistVideos(String playlistId, String collectionTitle) throws IOException, InterruptedException, YouTubeApiException {
        JsonObject json = send("/playlistItems", Map.of(
            "part", "snippet,contentDetails",
            "playlistId", playlistId,
            "maxResults", "12"
        ));
        List<String> ids = new ArrayList<>();
        for (JsonElement element : array(json, "items")) {
            JsonObject snippet = object(element.getAsJsonObject(), "snippet");
            JsonObject resourceId = object(snippet, "resourceId");
            String videoId = string(resourceId, "videoId");
            if (!videoId.isBlank()) {
                ids.add(videoId);
            }
        }
        return fetchVideosByIds(ids, playlistId, collectionTitle, collectionTitle);
    }

    private List<PlayableMedia> fetchVideosByIds(List<String> ids, String playlistId, String collectionTitle, String fallbackCollectionTitle)
        throws IOException, InterruptedException, YouTubeApiException {
        JsonArray items = fetchVideosJson(ids);
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            byId.put(string(item, "id"), item);
        }
        List<PlayableMedia> result = new ArrayList<>();
        for (String id : ids) {
            JsonObject item = byId.get(id);
            if (item == null) {
                continue;
            }
            JsonObject snippet = object(item, "snippet");
            JsonObject contentDetails = object(item, "contentDetails");
            String resolvedCollectionTitle = collectionTitle == null || collectionTitle.isBlank()
                ? fallbackCollectionTitle
                : collectionTitle;
            result.add(new PlayableMedia(
                id,
                playlistId == null ? "" : playlistId,
                defaultIfBlank(string(snippet, "title"), "Untitled video"),
                defaultIfBlank(string(snippet, "channelTitle"), "Unknown creator"),
                defaultIfBlank(resolvedCollectionTitle, "YouTube"),
                thumbnailUrl(snippet),
                parseDurationMillis(string(contentDetails, "duration"))
            ));
        }
        return result;
    }

    private JsonArray fetchVideosJson(List<String> ids) throws IOException, InterruptedException, YouTubeApiException {
        if (ids.isEmpty()) {
            return new JsonArray();
        }
        JsonObject json = send("/videos", Map.of(
            "part", "snippet,contentDetails",
            "id", ids.stream().collect(Collectors.joining(","))
        ));
        return array(json, "items");
    }

    private List<LibraryItem> toTrackItems(JsonArray items, String playlistId, String collectionTitle) {
        List<LibraryItem> result = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject video = element.getAsJsonObject();
            JsonObject snippet = object(video, "snippet");
            JsonObject contentDetails = object(video, "contentDetails");
            String videoId = string(video, "id");
            if (videoId.isBlank()) {
                continue;
            }
            result.add(new LibraryItem(
                LibraryItem.Kind.TRACK,
                videoId,
                watchUrl(videoId, playlistId, 0),
                defaultIfBlank(string(snippet, "title"), "Untitled video"),
                defaultIfBlank(string(snippet, "channelTitle"), "Unknown creator"),
                defaultIfBlank(collectionTitle, formatDuration(parseDurationMillis(string(contentDetails, "duration")))),
                thumbnailUrl(snippet),
                true
            ));
        }
        return result;
    }

    private List<LibraryItem> deriveCollections(List<LibraryItem> playlists) {
        List<LibraryItem> collections = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (LibraryItem playlist : playlists) {
            if (looksLikeCollection(playlist.title()) && seen.add(playlist.id())) {
                collections.add(asCollection(playlist));
            }
            if (collections.size() == 4) {
                return collections;
            }
        }
        for (LibraryItem playlist : playlists) {
            if (seen.add(playlist.id())) {
                collections.add(asCollection(playlist));
            }
            if (collections.size() == 4) {
                break;
            }
        }
        return collections;
    }

    private static boolean looksLikeCollection(String title) {
        String normalized = title == null ? "" : title.toLowerCase();
        return normalized.contains("album")
            || normalized.contains("mix")
            || normalized.contains("soundtrack")
            || normalized.contains("ost")
            || normalized.contains("session")
            || normalized.contains("compilation");
    }

    private static LibraryItem asCollection(LibraryItem playlist) {
        return new LibraryItem(
            LibraryItem.Kind.ALBUM,
            playlist.id(),
            playlist.uri(),
            playlist.title(),
            playlist.subtitle(),
            "Collection",
            playlist.imageUrl(),
            playlist.playable()
        );
    }

    private JsonObject send(String path, Map<String, String> parameters) throws IOException, InterruptedException, YouTubeApiException {
        this.authManager.ensureAccessToken();
        String query = parameters.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ROOT + path + '?' + query))
            .header("Authorization", "Bearer " + this.authManager.accessToken())
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new YouTubeApiException(response.statusCode(), extractErrorMessage(response.body(), response.statusCode()));
        }
        if (response.body() == null || response.body().isBlank()) {
            return new JsonObject();
        }
        return this.gson.fromJson(response.body(), JsonObject.class);
    }

    private String extractErrorMessage(String body, int status) {
        try {
            JsonObject json = this.gson.fromJson(body, JsonObject.class);
            JsonObject error = object(json, "error");
            if (error.has("message")) {
                return error.get("message").getAsString();
            }
        } catch (Exception exception) {
            this.logger.debug("Failed to parse YouTube error response", exception);
        }
        if (status == 401) {
            return "YouTube session expired. Reconnect your account.";
        }
        if (status == 403) {
            return "YouTube rejected the request. Check that the YouTube Data API is enabled for your Google OAuth client.";
        }
        return "YouTube request failed with status " + status + '.';
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<LibraryItem> limit(List<LibraryItem> items, int maxItems) {
        return items.size() <= maxItems ? List.copyOf(items) : List.copyOf(items.subList(0, maxItems));
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
        return parent.get(key).getAsInt();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String thumbnailUrl(JsonObject snippet) {
        JsonObject thumbnails = object(snippet, "thumbnails");
        for (String size : List.of("high", "medium", "default")) {
            JsonObject image = object(thumbnails, size);
            String url = string(image, "url");
            if (!url.isBlank()) {
                return url;
            }
        }
        return "";
    }

    private static int parseDurationMillis(String rawDuration) {
        if (rawDuration == null || rawDuration.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, (int) Duration.parse(rawDuration).toMillis());
        } catch (Exception exception) {
            return 1;
        }
    }

    private static String formatDuration(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    public static String watchUrl(String videoId, String playlistId, int offsetSeconds) {
        StringBuilder builder = new StringBuilder("https://www.youtube.com/watch?v=").append(videoId);
        if (playlistId != null && !playlistId.isBlank()) {
            builder.append("&list=").append(playlistId);
        }
        if (offsetSeconds > 0) {
            builder.append("&t=").append(offsetSeconds).append('s');
        }
        return builder.toString();
    }

    private static String playlistUrl(String playlistId) {
        return "https://www.youtube.com/playlist?list=" + playlistId;
    }

    private static String channelUrl(String channelId) {
        return "https://www.youtube.com/channel/" + channelId;
    }

    public record ResolvedPlayback(List<PlayableMedia> items, int startIndex) {
    }

    public record PlayableMedia(
        String videoId,
        String playlistId,
        String title,
        String creator,
        String collectionTitle,
        String imageUrl,
        int durationMs
    ) {
        public LibraryItem asLibraryItem() {
            return new LibraryItem(
                LibraryItem.Kind.TRACK,
                this.videoId,
                watchUrl(this.videoId, this.playlistId, 0),
                this.title,
                this.creator,
                this.collectionTitle,
                this.imageUrl,
                true
            );
        }
    }
}
