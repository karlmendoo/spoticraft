package io.github.karlmendoo.spoticraft.youtube.model;

public enum PlaybackState {
    IDLE("Idle"),
    PLAYING("Playing"),
    PAUSED("Paused"),
    BUFFERING("Buffering"),
    STOPPED("Stopped"),
    ERROR("Error");

    private final String label;

    PlaybackState(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }
}
