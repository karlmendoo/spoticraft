package io.github.karlmendoo.spoticraft.youtube.model;

public record ToastMessage(
    String title,
    String subtitle,
    String imageUrl,
    long createdAtMs,
    long durationMs
) {
    public boolean isExpired(long nowMs) {
        return nowMs - this.createdAtMs > this.durationMs;
    }
}
