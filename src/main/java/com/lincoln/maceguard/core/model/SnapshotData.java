package com.lincoln.maceguard.core.model;

import java.util.List;
import java.util.Map;

public final class SnapshotData {
    private final String snapshotZoneName;
    private final String snapshotWorldName;
    private final CuboidRegion snapshotRegion;
    private final Map<Long, String> snapshotBlocks;
    private final List<SnapshotBlock> snapshotSerializedBlocks;

    public SnapshotData(String zoneName, String worldName, CuboidRegion region, Map<Long, String> blocks, List<SnapshotBlock> serializedBlocks) {
        this.snapshotZoneName = zoneName;
        this.snapshotWorldName = worldName;
        this.snapshotRegion = region;
        this.snapshotBlocks = Map.copyOf(blocks);
        this.snapshotSerializedBlocks = List.copyOf(serializedBlocks);
    }

    public String zoneName() {
        return snapshotZoneName;
    }

    public String worldName() {
        return snapshotWorldName;
    }

    public CuboidRegion region() {
        return snapshotRegion;
    }

    public Map<Long, String> blocks() {
        return snapshotBlocks;
    }

    public List<SnapshotBlock> serializedBlocks() {
        return snapshotSerializedBlocks;
    }

    public boolean isUsable() {
        return !snapshotBlocks.isEmpty();
    }
}
