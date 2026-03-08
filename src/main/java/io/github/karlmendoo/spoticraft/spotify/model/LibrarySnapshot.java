package io.github.karlmendoo.spoticraft.spotify.model;

import java.util.List;

public record LibrarySnapshot(
    List<LibraryItem> playlists,
    List<LibraryItem> likedTracks,
    List<LibraryItem> recentlyPlayed
) {
    public static LibrarySnapshot empty() {
        return new LibrarySnapshot(List.of(), List.of(), List.of());
    }
}
