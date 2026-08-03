package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.config.ResetProfile;
import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.Set;

public final class SnapshotValidator {
    public Validation validate(Snapshot snapshot, RegionDescriptor current, ResetProfile profile) {
        if (snapshot == null) return Validation.invalid("snapshot missing");
        if (snapshot.formatVersion() != Snapshot.FORMAT_VERSION)
            return Validation.invalid("unsupported snapshot format");
        if (!snapshot.complete() || snapshot.captureCompletedAt() < snapshot.captureStartedAt())
            return Validation.invalid("snapshot incomplete");
        if (!current.id().equals(snapshot.regionId())) return Validation.invalid("region ID differs");
        if (!current.worldUuid().toString().equals(snapshot.worldUuid()))
            return Validation.invalid("world UUID differs");
        if (!current.worldName().equals(snapshot.worldName()))
            return Validation.invalid("world name differs");
        if (!current.type().equals(snapshot.regionType()))
            return Validation.invalid("region type differs");
        if (!current.geometryHash().equals(snapshot.geometryHash())
                || !current.equals(snapshot.geometry()))
            return Validation.invalid("region geometry differs");
        if (!profile.name().equals(snapshot.resetProfile()))
            return Validation.invalid("reset profile differs");
        if (!profile.mode().name().equals(snapshot.resetMode()))
            return Validation.invalid("reset mode differs");
        if (snapshot.scannedCoordinateCount() < 0
                || snapshot.scannedCoordinateCount() > profile.maxCoordinates())
            return Validation.invalid("scan coverage exceeds configured safety limit");
        if (snapshot.blockCount() != snapshot.blocks().size()
                || snapshot.includedCoordinateCount() != snapshot.blocks().size())
            return Validation.invalid("persisted coordinate count differs");

        if (profile.mode() == ResetProfile.Mode.FULL_SNAPSHOT
                && snapshot.blockCount() != snapshot.scannedCoordinateCount())
            return Validation.invalid("full snapshot does not cover every non-excluded scanned coordinate");
        if (profile.mode() == ResetProfile.Mode.FILTERED_SNAPSHOT
                && snapshot.blockCount() > profile.maxCapturedCoordinates())
            return Validation.invalid("filtered snapshot exceeds captured-coordinate limit");

        Set<String> coordinates = new HashSet<>();
        for (SnapshotBlock block : snapshot.blocks()) {
            if (block.blockData() == null || block.blockData().isBlank()
                    || !current.contains(block.x(), block.y(), block.z()))
                return Validation.invalid("invalid block state");
            if (!coordinates.add(block.x() + ":" + block.y() + ":" + block.z()))
                return Validation.invalid("duplicate coordinate");
            if (profile.mode() == ResetProfile.Mode.FILTERED_SNAPSHOT) {
                if (block.blockEntity() != null)
                    return Validation.invalid("filtered snapshot contains a block entity");
                Material material;
                try { material = material(block.blockData()); }
                catch (RuntimeException ex) {
                    return Validation.invalid("filtered snapshot contains invalid block data");
                }
                if (!profile.captureMaterials().contains(material))
                    return Validation.invalid("filtered snapshot contains an unselected material");
            } else if (block.blockEntity() != null
                    && (!"CONTAINER".equals(block.blockEntity().type())
                    || block.blockEntity().inventoryItems() == null)) {
                return Validation.invalid("unsupported block entity state");
            }
        }
        if (!SnapshotChecksum.calculate(snapshot.blocks()).equals(snapshot.checksum()))
            return Validation.invalid("checksum mismatch");
        return Validation.success();
    }

    /** Compatibility helper for old tests and read-only tooling. */
    public Validation validate(Snapshot snapshot, RegionDescriptor current, String profileName) {
        ResetProfile.Mode mode;
        try { mode = ResetProfile.Mode.valueOf(snapshot.resetMode()); }
        catch (RuntimeException ex) { return Validation.invalid("reset mode differs"); }
        ResetProfile profile = new ResetProfile(profileName, mode, 0,
                Math.max(1, (int) Math.min(Integer.MAX_VALUE, current.volume())),
                Math.max(1, snapshot.blocks().size()), Integer.MAX_VALUE,
                Integer.MAX_VALUE, Set.of(), Set.of(),
                ResetProfile.SolidConflictPolicy.SKIP_AND_REPORT, java.util.List.of());
        return validate(snapshot, current, profile);
    }

    private Material material(String blockData) {
        String key = blockData;
        int properties = key.indexOf('[');
        if (properties >= 0) key = key.substring(0, properties);
        int namespace = key.indexOf(':');
        if (namespace >= 0) key = key.substring(namespace + 1);
        Material material = Material.matchMaterial(key.toUpperCase(java.util.Locale.ROOT));
        if (material == null) throw new IllegalArgumentException("unknown material");
        return material;
    }

    public record Validation(boolean valid, String reason) {
        public static Validation success() { return new Validation(true, "valid"); }
        public static Validation invalid(String reason) { return new Validation(false, reason); }
    }
}
