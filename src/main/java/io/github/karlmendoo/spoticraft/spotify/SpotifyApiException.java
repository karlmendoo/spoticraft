package io.github.karlmendoo.spoticraft.spotify;

public final class SpotifyApiException extends Exception {
    private final int statusCode;

    public SpotifyApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return this.statusCode;
    }

    public boolean isAuthFailure() {
        return this.statusCode == 401;
    }

    public boolean isNoActiveDevice() {
        return this.statusCode == 404;
    }
}
