package com.lincoln.maceguard.config;

import java.util.List;

public record ResetProfile(
        String name,
        Mode mode,
        int intervalMinutes,
        int maxCoordinates,
        int maxTotalChanges,
        int maxAirChanges,
        List<String> excludedRegionIds
) {
    public enum Mode { FULL_SNAPSHOT, SPARSE_ORIGINALS }
}
