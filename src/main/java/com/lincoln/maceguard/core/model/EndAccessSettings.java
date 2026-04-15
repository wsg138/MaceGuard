package com.lincoln.maceguard.core.model;

import java.time.Instant;

public record EndAccessSettings(
        boolean allowEyes,
        Instant eyesEnableAt,
        boolean allowPortals,
        Instant portalsEnableAt
) {
}
