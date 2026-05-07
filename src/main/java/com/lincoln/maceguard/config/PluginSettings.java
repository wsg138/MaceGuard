package com.lincoln.maceguard.config;

import com.lincoln.maceguard.core.model.EndAccessSettings;
import com.lincoln.maceguard.core.model.EndIslandSettings;
import com.lincoln.maceguard.core.model.GameplayZone;
import com.lincoln.maceguard.core.model.ProtectedRegion;

import java.util.List;
import java.util.Set;

public record PluginSettings(
        boolean enabled,
        boolean debug,
        ReloadSettings reload,
        ProtectionSettings protection,
        PerformanceSettings performance,
        BackstopScanSettings backstopScan,
        DebugPerformanceSettings debugPerformance,
        Set<String> allowedWorlds,
        List<ProtectedRegion> protectedRegions,
        List<GameplayZone> gameplayZones,
        EndAccessSettings endAccess,
        EndIslandSettings endIsland
) {
}
