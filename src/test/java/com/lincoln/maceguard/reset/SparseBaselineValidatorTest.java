package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SparseBaselineValidatorTest {
    private final SparseBaselineValidator validator = new SparseBaselineValidator();

    @Test void exactOriginalIsValidAndCorruptionIsRejected() {
        RegionDescriptor region = region();
        SnapshotBlock original = new SnapshotBlock(0, 0, 0, "minecraft:stone", null);
        Map<String, SnapshotBlock> entries = Map.of("0:0:0", original);
        SparseBaseline baseline = baseline(region, entries, validator.checksum(entries));
        assertTrue(validator.validate(baseline, region, "warzone", "exclusions").valid());
        assertEquals("sparse baseline checksum mismatch", validator.validate(baseline(region, entries, "bad"), region, "warzone", "exclusions").reason());
    }

    @Test void changedGeometryProfileOrExclusionsRejectBaseline() {
        RegionDescriptor region = region();
        SparseBaseline baseline = baseline(region, Map.of(), validator.checksum(Map.of()));
        assertFalse(validator.validate(baseline, changedRegion(), "warzone", "exclusions").valid());
        assertFalse(validator.validate(baseline, region, "other", "exclusions").valid());
        assertFalse(validator.validate(baseline, region, "warzone", "changed").valid());
    }

    private SparseBaseline baseline(RegionDescriptor region, Map<String, SnapshotBlock> entries, String checksum) {
        return new SparseBaseline(1, "test", region.worldUuid().toString(), region.id(), region, "warzone", "exclusions", true, 1, 2, checksum, entries);
    }
    private RegionDescriptor region() { return descriptor(1); }
    private RegionDescriptor changedRegion() { return descriptor(2); }
    private RegionDescriptor descriptor(int maxX) {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String hash = com.lincoln.maceguard.worldguard.RegionGeometryFingerprint.hash("warzone", uuid, "CUBOID", 0,0,0,maxX,0,0);
        return new RegionDescriptor("warzone","world",uuid,"CUBOID",0,0,0,maxX,0,0,hash,maxX+1L);
    }
}
