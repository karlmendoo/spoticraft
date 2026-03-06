package com.spoticraft.util;

import com.spoticraft.SpotiCraftMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads and caches album art as Minecraft NativeImageBackedTexture instances.
 * All downloads happen asynchronously on background threads.
 * Textures are registered with the Minecraft texture manager on the main thread.
 */
public class AlbumArtCache {

    private static final AlbumArtCache INSTANCE = new AlbumArtCache();

    // Map from URL → registered texture identifier
    private final Map<String, Identifier> textureCache = new ConcurrentHashMap<>();

    // Track which URLs are currently being fetched (to avoid duplicate requests)
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();

    private AlbumArtCache() {}

    public static AlbumArtCache getInstance() {
        return INSTANCE;
    }

    /**
     * Get the Identifier for the given image URL, or null if not yet loaded.
     * Triggers an async download if not cached.
     */
    public Identifier get(String url) {
        if (url == null || url.isBlank()) return null;
        if (textureCache.containsKey(url)) return textureCache.get(url);

        // Start async fetch if not already pending
        if (!pending.containsKey(url)) {
            pending.put(url, true);
            fetchAsync(url);
        }
        return null; // not yet loaded
    }

    private void fetchAsync(String url) {
        HttpHelper.getBytesAsync(url)
                .thenAccept(bytes -> {
                    // Must register texture on the render thread
                    MinecraftClient.getInstance().execute(() -> {
                        try {
                            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
                            // Create a unique Identifier from a hash of the URL
                            String idPath = "album_art/" + Math.abs(url.hashCode());
                            Identifier id = Identifier.of(SpotiCraftMod.MOD_ID, idPath);
                            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
                            textureCache.put(url, id);
                            pending.remove(url);
                        } catch (Exception e) {
                            SpotiCraftMod.LOGGER.warn("Failed to load album art from {}: {}", url, e.getMessage());
                            pending.remove(url);
                        }
                    });
                })
                .exceptionally(e -> {
                    SpotiCraftMod.LOGGER.warn("Failed to fetch album art: {}", e.getMessage());
                    pending.remove(url);
                    return null;
                });
    }

    /**
     * Clear all cached textures (call on disconnect / mod shutdown).
     */
    public void clear() {
        for (Map.Entry<String, Identifier> entry : textureCache.entrySet()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> client.getTextureManager().destroyTexture(entry.getValue()));
            }
        }
        textureCache.clear();
        pending.clear();
    }
}
