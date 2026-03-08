package io.github.karlmendoo.spoticraft.spotify.model;

public enum RepeatMode {
    OFF("off"),
    CONTEXT("context"),
    TRACK("track");

    private final String apiValue;

    RepeatMode(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return this.apiValue;
    }

    public RepeatMode next() {
        return switch (this) {
            case OFF -> CONTEXT;
            case CONTEXT -> TRACK;
            case TRACK -> OFF;
        };
    }

    public static RepeatMode fromApiValue(String value) {
        if (value == null) {
            return OFF;
        }
        for (RepeatMode mode : values()) {
            if (mode.apiValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return OFF;
    }
}
