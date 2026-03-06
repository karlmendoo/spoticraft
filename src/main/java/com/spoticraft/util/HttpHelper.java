package com.spoticraft.util;

import com.spoticraft.SpotiCraftMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Shared HTTP utilities using Java 11+ HttpClient.
 * All calls are made asynchronously to avoid blocking the game thread.
 */
public class HttpHelper {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpHelper() {}

    /**
     * Perform an async GET request with a Bearer token.
     */
    public static CompletableFuture<String> getAsync(String url, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200 || response.statusCode() == 204) {
                        return response.body();
                    } else if (response.statusCode() == 401) {
                        throw new RuntimeException("TOKEN_EXPIRED");
                    } else if (response.statusCode() == 403) {
                        throw new RuntimeException("NO_PREMIUM");
                    } else if (response.statusCode() == 404) {
                        throw new RuntimeException("NO_DEVICE");
                    } else if (response.statusCode() == 429) {
                        throw new RuntimeException("RATE_LIMITED");
                    } else {
                        SpotiCraftMod.LOGGER.warn("HTTP GET {} returned {}", url, response.statusCode());
                        throw new RuntimeException("HTTP_ERROR:" + response.statusCode());
                    }
                });
    }

    /**
     * Perform an async POST request with a Bearer token and JSON body.
     */
    public static CompletableFuture<String> postAsync(String url, String accessToken, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));

        if (body != null && !body.isBlank()) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }

        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return response.body();
                    } else if (response.statusCode() == 401) {
                        throw new RuntimeException("TOKEN_EXPIRED");
                    } else if (response.statusCode() == 403) {
                        throw new RuntimeException("NO_PREMIUM");
                    } else if (response.statusCode() == 404) {
                        throw new RuntimeException("NO_DEVICE");
                    } else if (response.statusCode() == 429) {
                        throw new RuntimeException("RATE_LIMITED");
                    } else {
                        SpotiCraftMod.LOGGER.warn("HTTP POST {} returned {}", url, response.statusCode());
                        throw new RuntimeException("HTTP_ERROR:" + response.statusCode());
                    }
                });
    }

    /**
     * Perform an async PUT request with a Bearer token and optional JSON body.
     */
    public static CompletableFuture<String> putAsync(String url, String accessToken, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));

        if (body != null && !body.isBlank()) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.PUT(HttpRequest.BodyPublishers.noBody());
        }

        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return response.body();
                    } else if (response.statusCode() == 401) {
                        throw new RuntimeException("TOKEN_EXPIRED");
                    } else if (response.statusCode() == 403) {
                        throw new RuntimeException("NO_PREMIUM");
                    } else if (response.statusCode() == 404) {
                        throw new RuntimeException("NO_DEVICE");
                    } else if (response.statusCode() == 429) {
                        throw new RuntimeException("RATE_LIMITED");
                    } else {
                        SpotiCraftMod.LOGGER.warn("HTTP PUT {} returned {}", url, response.statusCode());
                        throw new RuntimeException("HTTP_ERROR:" + response.statusCode());
                    }
                });
    }

    /**
     * Perform a raw POST (for OAuth token exchange) without Bearer token.
     */
    public static CompletableFuture<String> postFormAsync(String url, String formBody) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return response.body();
                    }
                    throw new RuntimeException("Token exchange failed: " + response.statusCode() + " " + response.body());
                });
    }

    /**
     * Download raw bytes (for album art images).
     */
    public static CompletableFuture<byte[]> getBytesAsync(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return response.body();
                    }
                    throw new RuntimeException("Image download failed: " + response.statusCode());
                });
    }

    public static HttpClient getClient() {
        return CLIENT;
    }
}
