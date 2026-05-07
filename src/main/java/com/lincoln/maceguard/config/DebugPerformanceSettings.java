package com.lincoln.maceguard.config;

public record DebugPerformanceSettings(
        boolean enabled,
        int logIntervalSeconds
) {
}
