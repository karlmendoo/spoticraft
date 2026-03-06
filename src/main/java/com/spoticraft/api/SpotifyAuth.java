package com.spoticraft.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spoticraft.SpotiCraftMod;
import com.spoticraft.config.SpotiCraftConfig;
import com.spoticraft.util.HttpHelper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles Spotify OAuth 2.0 PKCE authentication flow.
 *
 * Flow summary:
 * 1. Generate code_verifier (random 128-char string) and code_challenge (SHA-256 + base64url)
 * 2. Start localhost callback server on port 4381
 * 3. Open browser to Spotify authorization URL
 * 4. Capture auth code from redirect
 * 5. Exchange auth code + code_verifier for access_token and refresh_token
 * 6. Schedule automatic token refresh before expiry
 */
public class SpotifyAuth {

    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String REDIRECT_URI = "http://localhost:4381/callback";
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
            "playlist-read-private playlist-read-collaborative user-library-read " +
            "user-read-recently-played streaming";

    private static final SpotifyAuth INSTANCE = new SpotifyAuth();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "SpotiCraft-TokenRefresh"); t.setDaemon(true); return t; }
    );

    /** State of the auth flow for UI feedback. */
    public enum AuthState { IDLE, CONNECTING, WAITING_CALLBACK, AUTHENTICATED, FAILED }

    private volatile AuthState authState = AuthState.IDLE;
    private volatile String lastError = null;
    private String pendingCodeVerifier = null;

    private SpotifyAuth() {}

    public static SpotifyAuth getInstance() {
        return INSTANCE;
    }

    public AuthState getAuthState() {
        return authState;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isAuthenticated() {
        return SpotiCraftConfig.getInstance().isAuthenticated();
    }

    // ── PKCE helpers ──────────────────────────────────────────────────────────

    /** Generate a cryptographically random code_verifier (43-128 chars, URL-safe). */
    private String generateCodeVerifier() {
        byte[] bytes = new byte[96]; // 96 bytes → 128 base64url chars
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Compute code_challenge = BASE64URL(SHA256(code_verifier)). */
    private String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    // ── Authentication flow ───────────────────────────────────────────────────

    /**
     * Start the PKCE OAuth flow.
     * Opens the browser to the Spotify authorization page and waits for the callback.
     */
    public CompletableFuture<Void> startAuthFlow(String clientId) {
        authState = AuthState.CONNECTING;
        lastError = null;

        return CompletableFuture.runAsync(() -> {
            try {
                // 1. Generate PKCE values
                pendingCodeVerifier = generateCodeVerifier();
                String codeChallenge = generateCodeChallenge(pendingCodeVerifier);

                // 2. Start callback server
                AuthCallbackServer callbackServer = new AuthCallbackServer();
                CompletableFuture<String> codeFuture = callbackServer.start();

                // 3. Build authorization URL
                String authorizationUrl = AUTH_URL +
                        "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                        "&response_type=code" +
                        "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                        "&code_challenge_method=S256" +
                        "&code_challenge=" + codeChallenge +
                        "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8);

                // 4. Open browser
                SpotiCraftMod.LOGGER.info("Opening Spotify authorization URL: {}", authorizationUrl);
                java.awt.Desktop.getDesktop().browse(new java.net.URI(authorizationUrl));
                authState = AuthState.WAITING_CALLBACK;

                // 5. Wait for auth code (timeout 5 minutes)
                String authCode = codeFuture.get(5, TimeUnit.MINUTES);

                // 6. Exchange code for tokens
                exchangeCodeForTokens(clientId, authCode, pendingCodeVerifier);

                authState = AuthState.AUTHENTICATED;
                SpotiCraftMod.LOGGER.info("Spotify authentication successful.");
            } catch (Exception e) {
                authState = AuthState.FAILED;
                lastError = e.getMessage();
                SpotiCraftMod.LOGGER.error("Spotify authentication failed", e);
            }
        });
    }

    /**
     * Exchange the authorization code for access + refresh tokens.
     */
    private void exchangeCodeForTokens(String clientId, String code, String verifier) throws Exception {
        String formBody = "grant_type=authorization_code" +
                "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&code_verifier=" + URLEncoder.encode(verifier, StandardCharsets.UTF_8);

        String responseJson = HttpHelper.postFormAsync(TOKEN_URL, formBody).get();
        parseAndStoreTokens(responseJson);
    }

    /**
     * Refresh the access token using the stored refresh_token.
     * Called automatically before each API request when token is near expiry.
     */
    public CompletableFuture<Void> refreshAccessToken() {
        SpotiCraftConfig config = SpotiCraftConfig.getInstance();
        String refreshToken = config.refreshToken;

        if (refreshToken == null || refreshToken.isBlank()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("No refresh token available — please re-authenticate."));
        }

        String formBody = "grant_type=refresh_token" +
                "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8) +
                "&client_id=" + URLEncoder.encode(config.clientId, StandardCharsets.UTF_8);

        return HttpHelper.postFormAsync(TOKEN_URL, formBody)
                .thenAccept(responseJson -> {
                    parseAndStoreTokens(responseJson);
                    SpotiCraftMod.LOGGER.info("Spotify access token refreshed.");
                })
                .exceptionally(e -> {
                    SpotiCraftMod.LOGGER.error("Token refresh failed", e);
                    authState = AuthState.FAILED;
                    lastError = "Token refresh failed: " + e.getMessage();
                    return null;
                });
    }

    /**
     * Parse the JSON token response and store tokens in config.
     */
    private void parseAndStoreTokens(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        SpotiCraftConfig config = SpotiCraftConfig.getInstance();

        config.accessToken = obj.get("access_token").getAsString();
        int expiresIn = obj.get("expires_in").getAsInt(); // seconds
        config.tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000L);

        // refresh_token may not be returned on every refresh
        if (obj.has("refresh_token") && !obj.get("refresh_token").isJsonNull()) {
            config.refreshToken = obj.get("refresh_token").getAsString();
        }

        SpotiCraftConfig.save();
    }

    /**
     * Start a background scheduler that checks token expiry every minute
     * and refreshes proactively before the token expires.
     */
    public void startTokenRefreshScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            SpotiCraftConfig config = SpotiCraftConfig.getInstance();
            if (config.isAuthenticated() && config.isTokenExpired()) {
                SpotiCraftMod.LOGGER.info("Access token expiring soon, refreshing...");
                refreshAccessToken();
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Ensure the access token is valid before making an API call.
     * Refreshes if expired or near expiry.
     */
    public CompletableFuture<String> getValidToken() {
        SpotiCraftConfig config = SpotiCraftConfig.getInstance();

        if (!config.isAuthenticated()) {
            return CompletableFuture.failedFuture(
                    new RuntimeException("Not authenticated — please connect to Spotify first."));
        }

        if (config.isTokenExpired()) {
            return refreshAccessToken().thenApply(v -> config.accessToken);
        }

        return CompletableFuture.completedFuture(config.accessToken);
    }

    /** Revoke tokens and clear stored credentials. */
    public void disconnect() {
        SpotiCraftConfig config = SpotiCraftConfig.getInstance();
        config.accessToken = "";
        config.refreshToken = "";
        config.tokenExpiresAt = 0;
        SpotiCraftConfig.save();
        authState = AuthState.IDLE;
        SpotiCraftMod.LOGGER.info("Disconnected from Spotify.");
    }
}
