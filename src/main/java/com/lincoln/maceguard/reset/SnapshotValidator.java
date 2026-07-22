package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;

import java.util.HashSet;
import java.util.Set;

public final class SnapshotValidator {
    public Validation validate(Snapshot snapshot, RegionDescriptor current, String profile) {
        if (snapshot == null) return Validation.invalid("snapshot missing");
        if (snapshot.formatVersion() != Snapshot.FORMAT_VERSION) return Validation.invalid("unsupported snapshot format");
        if (!snapshot.complete() || snapshot.captureCompletedAt() < snapshot.captureStartedAt()) return Validation.invalid("snapshot incomplete");
        if (!current.id().equals(snapshot.regionId())) return Validation.invalid("region ID differs");
        if (!current.worldUuid().toString().equals(snapshot.worldUuid())) return Validation.invalid("world UUID differs");
        if (!current.worldName().equals(snapshot.worldName())) return Validation.invalid("world name differs");
        if (!current.type().equals(snapshot.regionType())) return Validation.invalid("region type differs");
        if (!current.geometryHash().equals(snapshot.geometryHash()) || !current.equals(snapshot.geometry())) return Validation.invalid("region geometry differs");
        if (!profile.equals(snapshot.resetProfile())) return Validation.invalid("reset profile differs");
        if (snapshot.blockCount() != snapshot.blocks().size() || snapshot.includedCoordinateCount() != current.volume()) return Validation.invalid("coordinate coverage count differs");
        if (snapshot.blockCount() != current.volume()) return Validation.invalid("snapshot does not prove full coordinate coverage");
        Set<String> coordinates = new HashSet<>();
        for (SnapshotBlock block : snapshot.blocks()) {
            if (block.blockData() == null || block.blockData().isBlank() || !current.contains(block.x(), block.y(), block.z())) return Validation.invalid("invalid block state");
            if (block.blockEntity() != null && (!"CONTAINER".equals(block.blockEntity().type()) || block.blockEntity().inventoryItems() == null))
                return Validation.invalid("unsupported block entity state");
            if (!coordinates.add(block.x() + ":" + block.y() + ":" + block.z())) return Validation.invalid("duplicate coordinate");
        }
        if (!SnapshotChecksum.calculate(snapshot.blocks()).equals(snapshot.checksum())) return Validation.invalid("checksum mismatch");
        return Validation.success();
    }

    public record Validation(boolean valid, String reason) {
        public static Validation success() { return new Validation(true, "valid"); }
        public static Validation invalid(String reason) { return new Validation(false, reason); }
    }
}
