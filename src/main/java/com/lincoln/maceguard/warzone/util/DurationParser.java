package com.lincoln.maceguard.warzone.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([smhd])", Pattern.CASE_INSENSITIVE);

    private DurationParser() { }

    public static Duration parse(Object value) {
        if (value instanceof Number number) return Duration.ofSeconds(number.longValue());
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) throw new IllegalArgumentException("duration is blank");
        Matcher matcher = TOKEN.matcher(text);
        int cursor = 0;
        long seconds = 0;
        boolean found = false;
        while (matcher.find()) {
            if (!text.substring(cursor, matcher.start()).isBlank()) throw invalid(text);
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                default -> throw invalid(text);
            };
            seconds = Math.addExact(seconds, Math.multiplyExact(amount, multiplier));
            cursor = matcher.end();
            found = true;
        }
        if (!found || !text.substring(cursor).isBlank()) throw invalid(text);
        return Duration.ofSeconds(seconds);
    }

    private static IllegalArgumentException invalid(String text) {
        return new IllegalArgumentException("invalid duration '" + text + "'");
    }
}
