package com.pkmprojects.mongodbserver.dto;

/**
 * Human-readable byte formatting shared by view models. Uses the same
 * B/KB/MB/GB thresholds as the dashboard's storage-size display.
 */
public final class ByteSize {

    private ByteSize() {
    }

    public static String format(long bytes) {
        double value = bytes;
        if (value < 1024) {
            return String.format("%.0f B", value);
        }
        if (value < 1048576) {
            return String.format("%.1f KB", value / 1024);
        }
        if (value < 1073741824) {
            return String.format("%.1f MB", value / 1048576);
        }
        return String.format("%.2f GB", value / 1073741824);
    }
}