package com.spoticraft.api;

import com.spoticraft.SpotiCraftMod;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Lightweight HTTP server that listens on localhost:4381 for the OAuth callback.
 * Spotify redirects the user's browser to http://localhost:4381/callback?code=...
 * after authorization, and this server captures the authorization code.
 */
public class AuthCallbackServer {

    private static final int PORT = 4381;
    private static final String CALLBACK_PATH = "/callback";

    private HttpServer server;
    private CompletableFuture<String> codeFuture;

    /**
     * Start the callback server and return a future that resolves to the auth code.
     */
    public CompletableFuture<String> start() throws IOException {
        codeFuture = new CompletableFuture<>();

        server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext(CALLBACK_PATH, this::handleCallback);
        server.setExecutor(null); // default executor
        server.start();

        SpotiCraftMod.LOGGER.info("SpotiCraft auth callback server started on port {}", PORT);
        return codeFuture;
    }

    /**
     * Handle the OAuth redirect callback from Spotify.
     */
    private void handleCallback(HttpExchange exchange) throws IOException {
        URI requestUri = exchange.getRequestURI();
        String query = requestUri.getQuery(); // e.g. "code=abc123&state=xyz"

        String code = null;
        String error = null;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2) {
                    if ("code".equals(parts[0])) {
                        code = parts[1];
                    } else if ("error".equals(parts[0])) {
                        error = parts[1];
                    }
                }
            }
        }

        // Build a friendly HTML response page
        String html;
        if (code != null) {
            html = buildHtmlPage("✅ SpotiCraft Connected!",
                    "You have successfully connected Spotify to SpotiCraft. You can close this tab and return to Minecraft.",
                    "#1DB954");
            codeFuture.complete(code);
        } else {
            html = buildHtmlPage("❌ Authorization Failed",
                    "Authorization was denied or an error occurred: " + (error != null ? error : "unknown error"),
                    "#e74c3c");
            codeFuture.completeExceptionally(new RuntimeException("Authorization denied: " + error));
        }

        byte[] response = html.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }

        // Stop server after handling the callback
        stop();
    }

    /** Stop the HTTP server. */
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            SpotiCraftMod.LOGGER.info("SpotiCraft auth callback server stopped.");
        }
    }

    private String buildHtmlPage(String title, String message, String color) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
               "<title>" + title + "</title>" +
               "<style>body{font-family:sans-serif;background:#121212;color:#fff;display:flex;" +
               "justify-content:center;align-items:center;height:100vh;margin:0;}" +
               ".box{text-align:center;padding:40px;border-radius:12px;background:#1e1e1e;}" +
               "h1{color:" + color + ";}p{color:#b3b3b3;}</style></head>" +
               "<body><div class='box'><h1>" + title + "</h1><p>" + message + "</p></div></body></html>";
    }
}
