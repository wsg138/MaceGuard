package com.lincoln.maceguard.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Set;

public record ResetProfile(
        String name,
        Mode mode,
        int intervalMinutes,
        int maxCoordinates,
        int maxCapturedCoordinates,
        int maxTotalChanges,
        int maxAirChanges,
        Set<Material> captureMaterials,
        Set<Material> restoreWhenCurrent,
        SolidConflictPolicy solidConflictPolicy,
        List<String> excludedRegionIds
) {
    public ResetProfile {
        captureMaterials = Set.copyOf(captureMaterials);
        restoreWhenCurrent = Set.copyOf(restoreWhenCurrent);
        excludedRegionIds = List.copyOf(excludedRegionIds);
    }

    public ResetProfile(String name, Mode mode, int intervalMinutes, int maxCoordinates,
                        int maxTotalChanges, int maxAirChanges, List<String> excludedRegionIds) {
        this(name, mode, intervalMinutes, maxCoordinates, maxCoordinates, maxTotalChanges,
                maxAirChanges, Set.of(), Set.of(), SolidConflictPolicy.SKIP_AND_REPORT,
                excludedRegionIds);
    }

    public enum Mode {
        FULL_SNAPSHOT,
        FILTERED_SNAPSHOT,
        /** Retained only so old serialized state can fail closed and be reviewed. */
        SPARSE_ORIGINALS
    }

    public enum SolidConflictPolicy { SKIP_AND_REPORT }
}
