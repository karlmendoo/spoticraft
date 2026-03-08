package io.github.karlmendoo.spoticraft.youtube;

public final class YouTubeApiException extends Exception {
    private final int statusCode;

    public YouTubeApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return this.statusCode;
    }

    public boolean isAuthFailure() {
        return this.statusCode == 401;
    }
}
