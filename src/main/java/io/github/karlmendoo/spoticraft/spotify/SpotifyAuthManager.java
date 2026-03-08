package io.github.karlmendoo.spoticraft.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.karlmendoo.spoticraft.config.SpotiCraftConfig;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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

    private ServerSocket callbackServer;
    private Thread callbackThread;
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
            String host = redirectUri.getHost() == null || redirectUri.getHost().isBlank() ? "127.0.0.1" : redirectUri.getHost();
            String path = callbackPath(redirectUri);
            this.callbackServer = new ServerSocket();
            this.callbackServer.bind(new InetSocketAddress(host, port));
            this.callbackThread = new Thread(() -> acceptCallback(path), "spoticraft-auth-callback");
            this.callbackThread.setDaemon(true);
            this.callbackThread.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start Spotify callback server", exception);
        }
    }

    private void acceptCallback(String expectedPath) {
        ServerSocket serverSocket;
        synchronized (this) {
            serverSocket = this.callbackServer;
        }

        if (serverSocket == null) {
            return;
        }

        try (Socket socket = serverSocket.accept()) {
            handleCallback(socket, expectedPath);
        } catch (IOException exception) {
            if (!isSocketFailure(exception)) {
                this.logger.error("Spotify callback server failed", exception);
            }
        } finally {
            synchronized (this) {
                this.callbackServer = null;
                this.callbackThread = null;
            }
        }
    }

    private void handleCallback(Socket socket, String expectedPath) throws IOException {
        String response;
        int code = 200;
        try {
            URI requestUri = readRequestUri(socket, expectedPath);
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
        try (OutputStream outputStream = socket.getOutputStream()) {
            outputStream.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Internal Server Error") + "\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("Content-Type: text/html; charset=utf-8\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(body);
            outputStream.flush();
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
        synchronized (this) {
            ServerSocket serverSocket = this.callbackServer;
            Thread callbackThread = this.callbackThread;
            this.callbackServer = null;
            this.callbackThread = null;
            if (callbackThread != null) {
                callbackThread.interrupt();
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException exception) {
                    this.logger.debug("Failed to close Spotify callback server", exception);
                }
            }
        }
    }

    private URI readRequestUri(Socket socket, String expectedPath) throws IOException {
        String request = readHttpRequest(socket.getInputStream());
        String[] requestLines = request.split("\r\n");
        String requestLine = requestLines.length == 0 ? "" : requestLines[0];
        if (requestLine.isBlank()) {
            throw new IllegalStateException("Spotify callback request was empty.");
        }

        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 2) {
            throw new IllegalStateException("Spotify callback request was malformed.");
        }

        String target = requestParts[1];
        int queryIndex = target.indexOf('?');
        String path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        if (!Objects.equals(path, expectedPath)) {
            throw new IllegalStateException("Unexpected Spotify callback path: " + path);
        }

        return URI.create("http://localhost" + target);
    }

    private String readHttpRequest(InputStream inputStream) throws IOException {
        InputStream bufferedInputStream = inputStream instanceof BufferedInputStream ? inputStream : new BufferedInputStream(inputStream);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        int maxBytes = 16 * 1024;
        while ((current = bufferedInputStream.read()) != -1) {
            output.write(current);
            if (output.size() > maxBytes) {
                throw new IllegalStateException("Spotify callback request headers were too large.");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                int length = bytes.length;
                if (length >= 4
                    && bytes[length - 4] == '\r'
                    && bytes[length - 3] == '\n'
                    && bytes[length - 2] == '\r'
                    && bytes[length - 1] == '\n') {
                    return output.toString(StandardCharsets.UTF_8);
                }
            }
            previous = current;
        }
        throw new IllegalStateException("Spotify callback request ended before headers completed.");
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

    private static String callbackPath(URI redirectUri) {
        return redirectUri.getPath() == null || redirectUri.getPath().isBlank() ? "/" : redirectUri.getPath();
    }

    private static boolean isSocketFailure(IOException exception) {
        return exception instanceof SocketException;
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
