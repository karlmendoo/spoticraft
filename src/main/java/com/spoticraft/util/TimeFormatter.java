package com.spoticraft.util;

/**
 * Formats milliseconds into mm:ss display strings.
 */
public class TimeFormatter {

    private TimeFormatter() {}

    /**
     * Convert milliseconds to "mm:ss" format.
     * e.g. 185000 → "3:05"
     */
    public static String format(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * Returns a display string like "1:23 / 4:56" for progress bars.
     */
    public static String formatProgress(long progressMs, long durationMs) {
        return format(progressMs) + " / " + format(durationMs);
    }
}
