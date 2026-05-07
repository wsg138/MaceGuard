package com.lincoln.maceguard.core.model;

import java.time.Instant;

public record EndAccessSettings(
        boolean manageEyes,
        boolean persistAutoEnable,
        boolean allowEyes,
        Instant eyesEnableAt,
        boolean allowPortals,
        Instant portalsEnableAt
) {
}
