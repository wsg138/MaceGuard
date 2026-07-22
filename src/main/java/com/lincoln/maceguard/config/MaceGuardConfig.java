package com.lincoln.maceguard.config;

import com.lincoln.maceguard.core.model.EndAccessSettings;
import com.lincoln.maceguard.core.model.EndIslandSettings;

import java.util.Map;
import java.util.Set;

public record MaceGuardConfig(
        boolean validSchema,
        boolean enabled,
        boolean debug,
        int durabilityCap,
        TemporarySettings temporary,
        PerformanceSettings performance,
        Map<String, ResetProfile> resetProfiles,
        EndAccessSettings endAccess,
        EndIslandSettings endIsland,
        Set<String> errors
) {
    public record TemporarySettings(int cobwebTtlSeconds, Set<String> replacements, int maxTrackedBlocks) { }
    public record PerformanceSettings(int captureBatchSize, int planBatchSize, int restoreBatchSize) { }
}
