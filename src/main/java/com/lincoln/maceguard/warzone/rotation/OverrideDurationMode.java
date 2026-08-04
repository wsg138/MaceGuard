package com.lincoln.maceguard.warzone.rotation;

import java.util.Locale;
import java.util.Optional;

public enum OverrideDurationMode {
    ONE_HOUR,
    UNTIL_NEXT_SCHEDULED_CHANGE,
    UNTIL_CLEARED;

    public static Optional<OverrideDurationMode> parse(String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1h", "one_hour", "one-hour", "hour" -> Optional.of(ONE_HOUR);
            case "next", "until_next_scheduled_change", "until-next" ->
                    Optional.of(UNTIL_NEXT_SCHEDULED_CHANGE);
            case "manual", "clear", "until_cleared", "until-cleared" ->
                    Optional.of(UNTIL_CLEARED);
            default -> Optional.empty();
        };
    }

    public String argument() {
        return switch (this) {
            case ONE_HOUR -> "1h";
            case UNTIL_NEXT_SCHEDULED_CHANGE -> "next";
            case UNTIL_CLEARED -> "manual";
        };
    }
}
