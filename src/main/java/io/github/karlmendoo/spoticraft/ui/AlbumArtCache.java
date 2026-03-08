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
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        Identifier identifier = this.cache.get(imageUrl);
        if (identifier != null) {
            return identifier;
        }
        Long failedAt = this.failedLoads.get(imageUrl);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_RETRY_DELAY_MS) {
            return null;
        }
        if (!this.loading.add(imageUrl)) {
            return null;
        }

        Thread.startVirtualThread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/png,image/jpeg,image/*,*/*;q=0.8")
                    .GET()
                    .build();
                HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    markFailure(imageUrl, "Album art request returned status " + response.statusCode(), null);
                    return;
                }
                if (response.body().length == 0) {
                    markFailure(imageUrl, "Album art response was empty", null);
                    return;
                }
                NativeImage image;
                try (ByteArrayInputStream inputStream = new ByteArrayInputStream(response.body())) {
                    image = NativeImage.read(inputStream);
                }
                if (image == null) {
                    markFailure(imageUrl, "Album art response could not be decoded", null);
                    return;
                }
                MinecraftClient.getInstance().execute(() -> {
                    Identifier dynamic = Identifier.of("spoticraft", "album/" + Integer.toHexString(imageUrl.hashCode()));
                    MinecraftClient.getInstance().getTextureManager().registerTexture(
                        dynamic,
                        new NativeImageBackedTexture(() -> "spoticraft_album_art", image)
                    );
                    this.cache.put(imageUrl, dynamic);
                    this.failedLoads.remove(imageUrl);
                    this.loading.remove(imageUrl);
                });
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                markFailure(imageUrl, "Failed to load album art", exception);
            }
        });
        return null;
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
