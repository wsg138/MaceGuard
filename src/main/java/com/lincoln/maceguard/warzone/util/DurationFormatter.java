package com.lincoln.maceguard.warzone.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DurationFormatter {
    private DurationFormatter() { }

    public static String words(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 || parts.isEmpty()) parts.add(seconds + "s");
        return String.join(" ", parts);
    }

    public static String clock(Duration duration) {
        long total = Math.max(0, duration.getSeconds());
        long hours = total / 3_600;
        long minutes = total % 3_600 / 60;
        long seconds = total % 60;
        return hours > 0 ? "%02d:%02d:%02d".formatted(hours, minutes, seconds)
                : "%02d:%02d".formatted(minutes, seconds);
    }
}
