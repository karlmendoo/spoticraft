package io.github.karlmendoo.spoticraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpotiCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_REDIRECT_URI = "http://127.0.0.1:43897/callback";

    public String clientId = "";
    public String clientSecret = "";
    public String redirectUri = DEFAULT_REDIRECT_URI;
    public String accessToken = "";
    public String refreshToken = "";
    public long accessTokenExpiryEpochSecond;
    public boolean overlayEnabled = true;
    public int overlayOpacity = 190;
    public boolean openBrowserOnAuth = true;

    private transient Path path;

    private SpotiCraftConfig(Path path) {
        this.path = path;
    }

    public static SpotiCraftConfig load(Logger logger) {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                SpotiCraftConfig created = new SpotiCraftConfig(path);
                created.save();
                return created;
            }

            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                SpotiCraftConfig config = GSON.fromJson(reader, SpotiCraftConfig.class);
                if (config == null) {
                    config = new SpotiCraftConfig(path);
                } else {
                    config.redirectUri = isBlank(config.redirectUri) ? DEFAULT_REDIRECT_URI : config.redirectUri;
                    config.path = path;
                }
                    config.overlayOpacity = clamp(config.overlayOpacity, 60, 255);
                return config;
            }
        } catch (IOException exception) {
            logger.error("Failed to load SpotiCraft config from {}", path, exception);
            return new SpotiCraftConfig(path);
        }
    }

    public void save() {
        try {
            Files.createDirectories(this.path.getParent());
            try (Writer writer = Files.newBufferedWriter(this.path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save SpotiCraft config", exception);
        }
    }

    public boolean hasAppCredentials() {
        return !isBlank(this.clientId) && !isBlank(this.clientSecret);
    }

    public boolean hasRefreshToken() {
        return !isBlank(this.refreshToken);
    }

    public boolean hasAccessToken() {
        return !isBlank(this.accessToken);
    }

    public Path path() {
        return this.path;
    }

    public void clearTokens() {
        this.accessToken = "";
        this.refreshToken = "";
        this.accessTokenExpiryEpochSecond = 0L;
    }

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("spoticraft.json");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
