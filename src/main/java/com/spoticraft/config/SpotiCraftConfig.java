package com.spoticraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spoticraft.SpotiCraftMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SpotiCraft configuration — stores client ID, tokens, and UI preferences.
 * Config is stored as JSON in the game config directory.
 */
public class SpotiCraftConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("spoticraft.json");

    private static SpotiCraftConfig instance = new SpotiCraftConfig();

    // ── Spotify OAuth fields ──────────────────────────────────────────────────

    /** The Spotify application Client ID (user must provide this). */
    public String clientId = "";

    /** The OAuth access token (populated after auth). */
    public String accessToken = "";

    /** The OAuth refresh token (used to renew access tokens without re-auth). */
    public String refreshToken = "";

    /** Unix timestamp (ms) when the access token expires. */
    public long tokenExpiresAt = 0;

    // ── UI preferences ────────────────────────────────────────────────────────

    /** Whether the HUD overlay (corner widget) is visible. */
    public boolean hudVisible = true;

    /** HUD corner position: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right. */
    public int hudPosition = 3;

    /** Spotify green accent colour. */
    public static final int COLOR_ACCENT = 0xFF1DB954;

    /** Panel background — semi-transparent dark. */
    public static final int COLOR_BG = 0xCC121212;

    /** Secondary background for hovered/selected rows. */
    public static final int COLOR_BG_HOVER = 0xCC1A1A2E;

    /** Primary text colour. */
    public static final int COLOR_TEXT = 0xFFFFFFFF;

    /** Secondary (dimmer) text colour. */
    public static final int COLOR_TEXT_SECONDARY = 0xFFB3B3B3;

    // ─────────────────────────────────────────────────────────────────────────

    private SpotiCraftConfig() {}

    public static SpotiCraftConfig getInstance() {
        return instance;
    }

    /** Load configuration from disk (creates defaults if not present). */
    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                instance = GSON.fromJson(json, SpotiCraftConfig.class);
                if (instance == null) instance = new SpotiCraftConfig();
                SpotiCraftMod.LOGGER.info("SpotiCraft config loaded.");
            } catch (IOException e) {
                SpotiCraftMod.LOGGER.error("Failed to load SpotiCraft config", e);
                instance = new SpotiCraftConfig();
            }
        } else {
            instance = new SpotiCraftConfig();
            save(); // write defaults
        }
    }

    /** Persist configuration to disk. */
    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(instance));
        } catch (IOException e) {
            SpotiCraftMod.LOGGER.error("Failed to save SpotiCraft config", e);
        }
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    public boolean hasClientId() {
        return instance.clientId != null && !instance.clientId.isBlank();
    }

    public boolean isAuthenticated() {
        return instance.accessToken != null && !instance.accessToken.isBlank();
    }

    public boolean isTokenExpired() {
        return System.currentTimeMillis() >= instance.tokenExpiresAt - 30_000; // 30-sec buffer
    }
}
