package com.lincoln.maceguard.reset;

import java.util.List;

public record ResetPlan(String regionId, String worldUuid, String geometryHash, String snapshotChecksum,
                        long coordinatesInspected, int totalChanges, int nonAirChanges, int airChanges,
                        int blockEntities, int unsupportedStates, long excludedCoordinates, int estimatedBatches,
                        String planHash, List<Change> changes) {
    public record Change(int x, int y, int z, SnapshotBlock before, SnapshotBlock target) { }
}
