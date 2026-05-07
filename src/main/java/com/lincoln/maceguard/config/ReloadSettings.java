package com.lincoln.maceguard.config;

public record ReloadSettings(
        boolean preserveTemporaryBlocks,
        boolean clearInvalidZoneState
) {
}
