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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AlbumArtCache {
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, Identifier> cache = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();

    public AlbumArtCache(Logger logger) {
        this.logger = logger;
    }

    public Identifier request(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        Identifier identifier = this.cache.get(imageUrl);
        if (identifier != null || !this.loading.add(imageUrl)) {
            return identifier;
        }

        Thread.startVirtualThread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).GET().build();
                HttpResponse<byte[]> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() / 100 != 2) {
                    return;
                }
                NativeImage image;
                try (ByteArrayInputStream inputStream = new ByteArrayInputStream(response.body())) {
                    image = NativeImage.read(inputStream);
                }
                MinecraftClient.getInstance().execute(() -> {
                    Identifier dynamic = Identifier.of("spoticraft", "album/" + Integer.toHexString(imageUrl.hashCode()));
                    MinecraftClient.getInstance().getTextureManager().registerTexture(
                        dynamic,
                        new NativeImageBackedTexture(() -> "spoticraft_album_art", image)
                    );
                    this.cache.put(imageUrl, dynamic);
                    this.loading.remove(imageUrl);
                });
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.logger.debug("Failed to load album art {}", imageUrl, exception);
                this.loading.remove(imageUrl);
            }
        });
        return null;
    }
}
