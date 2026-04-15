package com.lincoln.maceguard.core.model;

import java.util.List;
import java.util.Map;

public final class SnapshotData {
    private final String zoneName;
    private final String worldName;
    private final CuboidRegion region;
    private final Map<Long, String> blocks;
    private final List<SnapshotBlock> serializedBlocks;

    public SnapshotData(String zoneName, String worldName, CuboidRegion region, Map<Long, String> blocks, List<SnapshotBlock> serializedBlocks) {
        this.zoneName = zoneName;
        this.worldName = worldName;
        this.region = region;
        this.blocks = Map.copyOf(blocks);
        this.serializedBlocks = List.copyOf(serializedBlocks);
    }

    public String zoneName() {
        return zoneName;
    }

    public String worldName() {
        return worldName;
    }

    public CuboidRegion region() {
        return region;
    }

    public Map<Long, String> blocks() {
        return blocks;
    }

    public List<SnapshotBlock> serializedBlocks() {
        return serializedBlocks;
    }

    public boolean isUsable() {
        return !blocks.isEmpty();
    }
}
