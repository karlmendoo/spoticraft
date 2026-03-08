package io.github.karlmendoo.spoticraft.youtube.model;

public record QueueEntry(
    int index,
    String trackId,
    String title,
    String artist,
    String imageUrl,
    int durationMs,
    boolean current
) {
}
