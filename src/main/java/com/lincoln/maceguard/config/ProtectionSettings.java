package com.lincoln.maceguard.config;

public record ProtectionSettings(
        boolean denyRespawnAnchor,
        boolean denyEndCrystal
) {
}
