package io.github.karlmendoo.spoticraft.youtube.model;

import java.util.List;

public record SearchSnapshot(
    List<LibraryItem> tracks,
    List<LibraryItem> albums,
    List<LibraryItem> artists,
    List<LibraryItem> playlists
) {
    public static SearchSnapshot empty() {
        return new SearchSnapshot(List.of(), List.of(), List.of(), List.of());
    }
}
