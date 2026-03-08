package io.github.karlmendoo.spoticraft.youtube.model;

public record LibraryItem(
    Kind kind,
    String id,
    String uri,
    String title,
    String subtitle,
    String detail,
    String imageUrl,
    boolean playable
) {
    public enum Kind {
        TRACK,
        ALBUM,
        ARTIST,
        PLAYLIST
    }
}
