package io.github.karlmendoo.spoticraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.karlmendoo.spoticraft.youtube.model.LibraryItem;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SpotiCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_REDIRECT_URI = "http://127.0.0.1:43897/callback";
    private static final int MAX_RECENT_ITEMS = 12;

    public String clientId = "";
    public String clientSecret = "";
    public String redirectUri = DEFAULT_REDIRECT_URI;
    public String accessToken = "";
    public String refreshToken = "";
    public long accessTokenExpiryEpochSecond;
    public boolean overlayEnabled = true;
    public int overlayOpacity = 190;
    public boolean openBrowserOnAuth = true;
    public List<StoredLibraryItem> recentItems = new ArrayList<>();

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
                    config.overlayOpacity = clamp(config.overlayOpacity, 60, 255);
                    if (config.recentItems == null) {
                        config.recentItems = new ArrayList<>();
                    }
                    if (config.recentItems.size() > MAX_RECENT_ITEMS) {
                        config.recentItems.subList(MAX_RECENT_ITEMS, config.recentItems.size()).clear();
                    }
                    config.path = path;
                }
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
        return !isBlank(this.clientId);
    }

    public boolean hasClientSecret() {
        return !isBlank(this.clientSecret);
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

    public List<LibraryItem> recentLibraryItems() {
        List<LibraryItem> result = new ArrayList<>(this.recentItems.size());
        for (StoredLibraryItem item : this.recentItems) {
            result.add(item.toLibraryItem());
        }
        return result;
    }

    public void rememberRecentItem(LibraryItem item) {
        if (item == null || isBlank(item.id()) || !item.playable()) {
            return;
        }
        this.recentItems.removeIf(existing -> existing.matches(item));
        this.recentItems.add(0, new StoredLibraryItem(item));
        if (this.recentItems.size() > MAX_RECENT_ITEMS) {
            this.recentItems.subList(MAX_RECENT_ITEMS, this.recentItems.size()).clear();
        }
        save();
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

    public static final class StoredLibraryItem {
        public String kind = LibraryItem.Kind.TRACK.name();
        public String id = "";
        public String uri = "";
        public String title = "";
        public String subtitle = "";
        public String detail = "";
        public String imageUrl = "";
        public boolean playable = true;

        public StoredLibraryItem() {
        }

        private StoredLibraryItem(LibraryItem item) {
            this.kind = item.kind().name();
            this.id = item.id();
            this.uri = item.uri();
            this.title = item.title();
            this.subtitle = item.subtitle();
            this.detail = item.detail();
            this.imageUrl = item.imageUrl();
            this.playable = item.playable();
        }

        private LibraryItem toLibraryItem() {
            LibraryItem.Kind resolvedKind;
            try {
                resolvedKind = LibraryItem.Kind.valueOf(this.kind);
            } catch (IllegalArgumentException exception) {
                resolvedKind = LibraryItem.Kind.TRACK;
            }
            return new LibraryItem(resolvedKind, this.id, this.uri, this.title, this.subtitle, this.detail, this.imageUrl, this.playable);
        }

        private boolean matches(LibraryItem item) {
            return this.id.equals(item.id()) && this.kind.equals(item.kind().name());
        }
    }
}
