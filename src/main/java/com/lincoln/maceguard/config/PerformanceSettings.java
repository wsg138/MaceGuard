package com.lincoln.maceguard.config;

public record PerformanceSettings(
        int resetBatchSize,
        int fullRestoreBatchSize,
        int liquidDrainBatchSize,
        int maxZoneQueriesPerTickDebugWarning
) {
}
