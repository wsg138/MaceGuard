package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;

import java.util.Map;

public record SparseBaseline(int formatVersion, String pluginVersion, String worldUuid, String regionId,
                             RegionDescriptor geometry, String profile, String exclusionHash, boolean complete,
                             long createdAt, long updatedAt, String checksum, Map<String, SnapshotBlock> originals) {
    public static final int FORMAT_VERSION = 1;
    public static String coordinateKey(int x, int y, int z) { return x + ":" + y + ":" + z; }
}
