package io.github.karlmendoo.spoticraft.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.karlmendoo.spoticraft.SpotiCraftClient;
import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * Handles Spotify OAuth2 authentication using the PKCE flow.
 * Opens the user's browser for login and captures the callback via a local HTTP server.
 */
public class SpotifyAuthManager {
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final int CALLBACK_PORT = 25921;
    private static final String REDIRECT_URI = "http://localhost:" + CALLBACK_PORT + "/callback";
    private static final String SCOPES = String.join(" ",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-library-read",
            "user-read-recently-played",
            "playlist-read-private",
            "playlist-read-collaborative"
    );

    private final SpotiCraftConfig config;
    private final HttpClient httpClient;
    private String codeVerifier;
    private final AtomicReference<CompletableFuture<Boolean>> pendingAuth = new AtomicReference<>();

    public SpotifyAuthManager(SpotiCraftConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Initiates the OAuth2 PKCE authorization flow by opening the user's browser.
     */
    public CompletableFuture<Boolean> startAuthFlow() {
        CompletableFuture<Boolean> existing = pendingAuth.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingAuth.set(future);

        CompletableFuture.runAsync(() -> {
            try {
                codeVerifier = generateCodeVerifier();
                String codeChallenge = generateCodeChallenge(codeVerifier);

                String authUrl = AUTH_URL + "?" +
                        "client_id=" + URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8) +
                        "&response_type=code" +
                        "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                        "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8) +
                        "&code_challenge_method=S256" +
                        "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8);

                // Start local callback server
                startCallbackServer(future);

                // Open browser
                Util.getOperatingSystem().open(URI.create(authUrl));

            } catch (Exception e) {
                SpotiCraftClient.LOGGER.error("Failed to start auth flow", e);
                future.complete(false);
            }
        });

        return future;
    }

    /**
     * Starts a temporary local HTTP server to receive the OAuth callback.
     */
    private void startCallbackServer(CompletableFuture<Boolean> future) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(CALLBACK_PORT), 0);
        server.createContext("/callback", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                String code = extractParam(query, "code");

                String response;
                if (code != null) {
                    response = "<html><body style='font-family:sans-serif;text-align:center;padding:50px;background:#1a1a2e;color:#eee'>"
                            + "<h1 style='color:#1DB954'>&#10003; SpotiCraft Connected!</h1>"
                            + "<p>You can close this window and return to Minecraft.</p></body></html>";
                    exchange.sendResponseHeaders(200, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    // Exchange code for tokens
                    boolean success = exchangeCodeForTokens(code);
                    future.complete(success);
                } else {
                    String error = extractParam(query, "error");
                    response = "<html><body style='font-family:sans-serif;text-align:center;padding:50px;background:#1a1a2e;color:#eee'>"
                            + "<h1 style='color:#e74c3c'>&#10007; Authentication Failed</h1>"
                            + "<p>Error: " + (error != null ? error : "unknown") + "</p></body></html>";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                    future.complete(false);
                }
            } catch (Exception e) {
                SpotiCraftClient.LOGGER.error("Callback error", e);
                future.complete(false);
            } finally {
                server.stop(1);
            }
        });
        server.start();
        SpotiCraftClient.LOGGER.info("OAuth callback server started on port {}", CALLBACK_PORT);
    }

    /**
     * Exchanges the authorization code for access and refresh tokens.
     */
    private boolean exchangeCodeForTokens(String code) {
        try {
            String body = "grant_type=authorization_code" +
                    "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8) +
                    "&client_id=" + URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8) +
                    "&code_verifier=" + URLEncoder.encode(codeVerifier, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseAndStoreTokens(response.body());
            } else {
                SpotiCraftClient.LOGGER.error("Token exchange failed: {} {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            SpotiCraftClient.LOGGER.error("Token exchange error", e);
            return false;
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     */
    public boolean refreshAccessToken() {
        if (!config.hasRefreshToken()) {
            return false;
        }
        try {
            String body = "grant_type=refresh_token" +
                    "&refresh_token=" + URLEncoder.encode(config.getRefreshToken(), StandardCharsets.UTF_8) +
                    "&client_id=" + URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseAndStoreTokens(response.body());
            } else {
                SpotiCraftClient.LOGGER.error("Token refresh failed: {} {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            SpotiCraftClient.LOGGER.error("Token refresh error", e);
            return false;
        }
    }

    /**
     * Ensures the access token is valid, refreshing if necessary.
     */
    public boolean ensureValidToken() {
        if (config.isAuthenticated()) {
            return true;
        }
        if (config.hasRefreshToken()) {
            return refreshAccessToken();
        }
        return false;
    }

    /**
     * Returns the current valid access token, or null if not authenticated.
     */
    public String getValidAccessToken() {
        if (ensureValidToken()) {
            return config.getAccessToken();
        }
        return null;
    }

    private boolean parseAndStoreTokens(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            String accessToken = json.get("access_token").getAsString();
            int expiresIn = json.get("expires_in").getAsInt();

            config.setAccessToken(accessToken);
            config.setTokenExpiresAt(System.currentTimeMillis() + (expiresIn * 1000L) - 60000);

            if (json.has("refresh_token")) {
                config.setRefreshToken(json.get("refresh_token").getAsString());
            }

            config.save();
            SpotiCraftClient.LOGGER.info("Spotify tokens updated successfully");
            return true;
        } catch (Exception e) {
            SpotiCraftClient.LOGGER.error("Failed to parse token response", e);
            return false;
        }
    }

    public void logout() {
        config.setAccessToken("");
        config.setRefreshToken("");
        config.setTokenExpiresAt(0);
        config.save();
    }

    private static String generateCodeVerifier() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String extractParam(String query, String param) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
