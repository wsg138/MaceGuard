package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;

import java.util.List;

public record Snapshot(
        int formatVersion,
        String pluginVersion,
        String regionId,
        String worldName,
        String worldUuid,
        String regionType,
        RegionDescriptor geometry,
        String geometryHash,
        String resetProfile,
        String resetMode,
        long captureStartedAt,
        long captureCompletedAt,
        boolean complete,
        long scannedCoordinateCount,
        long blockCount,
        long includedCoordinateCount,
        String checksum,
        List<SnapshotBlock> blocks
) {
    public static final int FORMAT_VERSION = 2;

    public Snapshot {
        blocks = List.copyOf(blocks);
    }

    public Snapshot(int formatVersion, String pluginVersion, String regionId, String worldName,
                    String worldUuid, String regionType, RegionDescriptor geometry,
                    String geometryHash, String resetProfile, long captureStartedAt,
                    long captureCompletedAt, boolean complete, long blockCount,
                    long includedCoordinateCount, String checksum, List<SnapshotBlock> blocks) {
        this(formatVersion, pluginVersion, regionId, worldName, worldUuid, regionType, geometry,
                geometryHash, resetProfile, "FULL_SNAPSHOT", captureStartedAt,
                captureCompletedAt, complete, includedCoordinateCount, blockCount,
                includedCoordinateCount, checksum, blocks);
    }
}
