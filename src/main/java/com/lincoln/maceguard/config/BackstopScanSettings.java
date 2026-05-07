package com.lincoln.maceguard.config;

public record BackstopScanSettings(
        boolean enabled,
        int intervalMinutes,
        int maxZonesPerTick,
        int maxBlocksPerTick,
        boolean repairMode,
        boolean reportOnly
) {
}
