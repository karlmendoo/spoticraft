package io.github.karlmendoo.spoticraft.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class SpotifyAuthManager {
    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = String.join(" ",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-read-recently-played"
    );

    private final SpotiCraftConfig config;
    private final Logger logger;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();

    private HttpServer callbackServer;
    private String activeState = "";

    public SpotifyAuthManager(SpotiCraftConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public synchronized URI startAuthorization() {
        if (!this.config.hasAppCredentials()) {
            throw new IllegalStateException("Add your Spotify client ID and client secret to " + this.config.path());
        }

        stopCallbackServer();
        this.activeState = UUID.randomUUID().toString();
        startCallbackServer();

        String query = "client_id=" + encode(this.config.clientId)
            + "&response_type=code"
            + "&redirect_uri=" + encode(this.config.redirectUri)
            + "&scope=" + encode(SCOPES)
            + "&state=" + encode(this.activeState)
            + "&show_dialog=true";
        return URI.create(AUTHORIZE_URL + "?" + query);
    }

    public synchronized void ensureAccessToken() throws IOException, InterruptedException, SpotifyApiException {
        long now = Instant.now().getEpochSecond();
        if (this.config.hasAccessToken() && now + 30 < this.config.accessTokenExpiryEpochSecond) {
            return;
        }
        if (this.config.hasRefreshToken()) {
            refreshAccessToken();
            return;
        }
        throw new SpotifyApiException(401, "Connect your Spotify account first.");
    }

    public synchronized String accessToken() {
        return this.config.accessToken;
    }

    public synchronized boolean isConnected() {
        return this.config.hasAccessToken() || this.config.hasRefreshToken();
    }

    private synchronized void startCallbackServer() {
        try {
            URI redirectUri = URI.create(this.config.redirectUri);
            int port = redirectUri.getPort() > 0 ? redirectUri.getPort() : 80;
            String path = redirectUri.getPath() == null || redirectUri.getPath().isBlank() ? "/" : redirectUri.getPath();
            this.callbackServer = HttpServer.create(new InetSocketAddress(redirectUri.getHost(), port), 0);
            this.callbackServer.createContext(path, this::handleCallback);
            this.callbackServer.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "spoticraft-auth-callback");
                thread.setDaemon(true);
                return thread;
            }));
            this.callbackServer.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Spotify callback server", exception);
        }
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        String response;
        int code = 200;
        try {
            URI requestUri = exchange.getRequestURI();
            JsonObject params = parseQuery(requestUri.getRawQuery());
            String receivedState = params.has("state") ? params.get("state").getAsString() : "";
            if (!Objects.equals(receivedState, this.activeState)) {
                throw new IllegalStateException("State mismatch during Spotify sign-in.");
            }
            if (params.has("error")) {
                throw new IllegalStateException(params.get("error").getAsString());
            }
            String authCode = params.has("code") ? params.get("code").getAsString() : "";
            if (authCode.isBlank()) {
                throw new IllegalStateException("Spotify did not return an authorization code.");
            }
            exchangeAuthorizationCode(authCode);
            response = "<html><body style='font-family:sans-serif;background:#121212;color:#fff;padding:24px'>"
                + "<h2>SpotiCraft connected</h2><p>You can close this tab and return to Minecraft.</p></body></html>";
        } catch (Exception exception) {
            this.logger.error("Spotify authorization failed", exception);
            response = "<html><body style='font-family:sans-serif;background:#1e1e1e;color:#fff;padding:24px'>"
                + "<h2>SpotiCraft connection failed</h2><p>" + escapeHtml(exception.getMessage()) + "</p></body></html>";
            code = 500;
        } finally {
            stopCallbackServer();
        }

        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private synchronized void exchangeAuthorizationCode(String authCode) throws IOException, InterruptedException, SpotifyApiException {
        String form = "grant_type=authorization_code"
            + "&code=" + encode(authCode)
            + "&redirect_uri=" + encode(this.config.redirectUri);
        JsonObject tokenResponse = sendTokenRequest(form);
        applyTokenResponse(tokenResponse);
    }

    private synchronized void refreshAccessToken() throws IOException, InterruptedException, SpotifyApiException {
        String form = "grant_type=refresh_token&refresh_token=" + encode(this.config.refreshToken);
        JsonObject tokenResponse = sendTokenRequest(form);
        applyTokenResponse(tokenResponse);
    }

    private JsonObject sendTokenRequest(String form) throws IOException, InterruptedException, SpotifyApiException {
        String credentials = this.config.clientId + ':' + this.config.clientSecret;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

        HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new SpotifyApiException(response.statusCode(), extractTokenError(response.body()));
        }
        return this.gson.fromJson(response.body(), JsonObject.class);
    }

    private void applyTokenResponse(JsonObject tokenResponse) {
        this.config.accessToken = tokenResponse.get("access_token").getAsString();
        if (tokenResponse.has("refresh_token")) {
            this.config.refreshToken = tokenResponse.get("refresh_token").getAsString();
        }
        this.config.accessTokenExpiryEpochSecond = Instant.now().getEpochSecond() + tokenResponse.get("expires_in").getAsLong();
        this.config.save();
    }

    private void stopCallbackServer() {
        if (this.callbackServer != null) {
            this.callbackServer.stop(0);
            this.callbackServer = null;
        }
    }

    private static JsonObject parseQuery(String query) {
        JsonObject result = new JsonObject();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String part : query.split("&")) {
            String[] pieces = part.split("=", 2);
            String key = java.net.URLDecoder.decode(pieces[0], StandardCharsets.UTF_8);
            String value = pieces.length > 1 ? java.net.URLDecoder.decode(pieces[1], StandardCharsets.UTF_8) : "";
            result.addProperty(key, value);
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonObject errorObject(String rawBody) {
        try {
            return this.gson.fromJson(rawBody, JsonObject.class);
        } catch (Exception exception) {
            return new JsonObject();
        }
    }

    private String extractTokenError(String rawBody) {
        JsonObject json = errorObject(rawBody);
        if (json.has("error_description")) {
            return json.get("error_description").getAsString();
        }
        if (json.has("error")) {
            return json.get("error").getAsString();
        }
        return "Spotify authentication failed.";
    }

    private static String escapeHtml(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
