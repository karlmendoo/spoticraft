package io.github.karlmendoo.spoticraft.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AlbumArtCache {
    private static final String USER_AGENT = "SpotiCraft";
    // Apply the same one-minute retry window to transport, HTTP, and decode failures so bad thumbnail URLs do not
    // trigger repeated per-frame fetch attempts while still allowing transient CDN or network issues to recover.
    private static final long FAILURE_RETRY_DELAY_MS = 60_000L;

    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final Map<String, Identifier> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> failedLoads = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    public AlbumArtCache(Logger logger) {
        this.logger = logger;
    }

    public Identifier request(String imageUrl) {
        return request(imageUrl, "");
    }

    public Identifier request(String imageUrl, String fallbackVideoId) {
        List<String> candidates = candidateUrls(imageUrl, fallbackVideoId);
        if (candidates.isEmpty()) {
            return null;
        }
        for (String candidate : candidates) {
            Identifier identifier = this.cache.get(candidate);
            if (identifier != null) {
                return identifier;
            }
        }
        long now = System.currentTimeMillis();
        for (String candidate : candidates) {
            Long failedAt = this.failedLoads.get(candidate);
            if (failedAt != null && now - failedAt < FAILURE_RETRY_DELAY_MS) {
                continue;
            }
            if (this.loading.contains(candidate)) {
                return null;
            }
        }
        loadCandidateChain(candidates, 0);
        return null;
    }

    private void loadCandidateChain(List<String> candidates, int startIndex) {
        for (int index = startIndex; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            Long failedAt = this.failedLoads.get(candidate);
            if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_RETRY_DELAY_MS) {
                continue;
            }
            if (!this.loading.add(candidate)) {
                return;
            }
            final int nextIndex = index + 1;
            loadAsync(candidate, candidates, nextIndex);
            return;
        }
    }

    private void loadAsync(String imageUrl, List<String> candidates, int nextIndex) {
        Thread.startVirtualThread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/png,image/jpeg,image/webp,image/*,*/*;q=0.8")
                    .GET()
                    .build();
                HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    markFailure(imageUrl, "Album art request returned status " + response.statusCode(), null);
                    loadCandidateChain(candidates, nextIndex);
                    return;
                }
                if (response.body().length == 0) {
                    markFailure(imageUrl, "Album art response was empty", null);
                    loadCandidateChain(candidates, nextIndex);
                    return;
                }
                NativeImage image;
                try (ByteArrayInputStream inputStream = new ByteArrayInputStream(response.body())) {
                    image = NativeImage.read(inputStream);
                }
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    if (image != null) {
                        image.close();
                    }
                    markFailure(imageUrl, "Album art response could not be decoded or had invalid dimensions", null);
                    loadCandidateChain(candidates, nextIndex);
                    return;
                }
                final NativeImage validImage = image;
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> {
                    try {
                        Identifier dynamic = Identifier.of("spoticraft", "album/" + Integer.toHexString(imageUrl.hashCode()));
                        client.getTextureManager().registerTexture(
                            dynamic,
                            new NativeImageBackedTexture(() -> "spoticraft_album_art", validImage)
                        );
                        this.cache.put(imageUrl, dynamic);
                        this.failedLoads.remove(imageUrl);
                    } catch (RuntimeException exception) {
                        this.logger.warn("Failed to register album art texture: {}", imageUrl, exception);
                        this.failedLoads.put(imageUrl, System.currentTimeMillis());
                        loadCandidateChain(candidates, nextIndex);
                    } finally {
                        this.loading.remove(imageUrl);
                    }
                });
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                markFailure(imageUrl, "Failed to load album art", exception);
                if (!(exception instanceof InterruptedException)) {
                    loadCandidateChain(candidates, nextIndex);
                }
            } catch (RuntimeException exception) {
                markFailure(imageUrl, "Failed to load album art", exception);
                loadCandidateChain(candidates, nextIndex);
            }
        });
    }

    private static List<String> candidateUrls(String imageUrl, String fallbackVideoId) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (imageUrl == null || imageUrl.isBlank()) {
            addYouTubeFallbacks(candidates, fallbackVideoId);
            return List.copyOf(candidates);
        }
        candidates.add(imageUrl);
        addYouTubeFallbacks(candidates, fallbackVideoId(imageUrl, fallbackVideoId));
        return List.copyOf(candidates);
    }

    private static void addYouTubeFallbacks(Set<String> candidates, String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return;
        }
        for (String size : List.of("maxresdefault.jpg", "hqdefault.jpg", "mqdefault.jpg", "default.jpg")) {
            candidates.add("https://i.ytimg.com/vi/" + videoId + "/" + size);
        }
    }

    private static String fallbackVideoId(String imageUrl, String fallbackVideoId) {
        if (fallbackVideoId != null && !fallbackVideoId.isBlank()) {
            return fallbackVideoId;
        }
        String marker = "/vi/";
        int markerIndex = imageUrl.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int start = markerIndex + marker.length();
        int end = imageUrl.indexOf('/', start);
        return end > start ? imageUrl.substring(start, end) : "";
    }

    private void markFailure(String imageUrl, String message, Exception exception) {
        this.failedLoads.put(imageUrl, System.currentTimeMillis());
        this.loading.remove(imageUrl);
        if (exception == null) {
            this.logger.warn("{}: {}", message, imageUrl);
            return;
        }
        this.logger.warn("{} {}", message, imageUrl, exception);
    }
}
