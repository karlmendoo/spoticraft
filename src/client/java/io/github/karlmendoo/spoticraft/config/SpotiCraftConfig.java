package io.github.karlmendoo.spoticraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for SpotiCraft.
 * Stores Spotify tokens and user preferences. Saved as JSON in config/spoticraft.json.
 */
public class SpotiCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("spoticraft.json");

    // Spotify OAuth tokens
    private String accessToken = "";
    private String refreshToken = "";
    private long tokenExpiresAt = 0;

    // Spotify app credentials (user must fill in)
    private String clientId = "";

    // User preferences
    private int volume = 50;
    private boolean showTrackNotifications = true;
    private int notificationDurationMs = 4000;

    public static SpotiCraftConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                SpotiCraftConfig config = GSON.fromJson(json, SpotiCraftConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (IOException e) {
                SpotiCraftClient.LOGGER.error("Failed to load SpotiCraft config", e);
            }
        }
        SpotiCraftConfig config = new SpotiCraftConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            SpotiCraftClient.LOGGER.error("Failed to save SpotiCraft config", e);
        }
    }

    // Getters and setters
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public long getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(long tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public int getVolume() { return volume; }
    public void setVolume(int volume) { this.volume = Math.max(0, Math.min(100, volume)); }

    public boolean isShowTrackNotifications() { return showTrackNotifications; }
    public void setShowTrackNotifications(boolean show) { this.showTrackNotifications = show; }

    public int getNotificationDurationMs() { return notificationDurationMs; }
    public void setNotificationDurationMs(int ms) { this.notificationDurationMs = ms; }

    public boolean isAuthenticated() {
        return !accessToken.isEmpty() && System.currentTimeMillis() < tokenExpiresAt;
    }

    public boolean hasRefreshToken() {
        return !refreshToken.isEmpty();
    }

    public boolean hasClientId() {
        return !clientId.isEmpty();
    }
}
