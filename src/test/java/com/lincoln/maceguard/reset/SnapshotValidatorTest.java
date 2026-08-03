package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotValidatorTest {
    private final SnapshotValidator validator = new SnapshotValidator();

    @Test void completeAirCoverageIsValid() { assertTrue(validator.validate(snapshot(region(), true, Snapshot.FORMAT_VERSION), region(), "pit").valid()); }
    @Test void incompleteSnapshotIsRejected() { assertEquals("snapshot incomplete", validator.validate(snapshot(region(), false, Snapshot.FORMAT_VERSION), region(), "pit").reason()); }
    @Test void unsupportedLegacyFormatIsRejected() { assertEquals("unsupported snapshot format", validator.validate(snapshot(region(), true, 0), region(), "pit").reason()); }
    @Test void wrongRegionIdIsRejected() { RegionDescriptor changed = descriptor("other", region().worldUuid(), 1); assertEquals("region ID differs", validator.validate(snapshot(region(), true, Snapshot.FORMAT_VERSION), changed, "pit").reason()); }
    @Test void wrongWorldUuidIsRejected() { RegionDescriptor changed = descriptor("pit", UUID.randomUUID(), 1); assertEquals("world UUID differs", validator.validate(snapshot(region(), true, Snapshot.FORMAT_VERSION), changed, "pit").reason()); }
    @Test void changedGeometryAndHashAreRejected() { RegionDescriptor changed = descriptor("pit", region().worldUuid(), 2); assertEquals("region geometry differs", validator.validate(snapshot(region(), true, Snapshot.FORMAT_VERSION), changed, "pit").reason()); }
    @Test void corruptChecksumIsRejected() { Snapshot value = snapshot(region(), true, Snapshot.FORMAT_VERSION); value = new Snapshot(value.formatVersion(), value.pluginVersion(), value.regionId(), value.worldName(), value.worldUuid(), value.regionType(), value.geometry(), value.geometryHash(), value.resetProfile(), value.resetMode(), value.captureStartedAt(), value.captureCompletedAt(), true, value.scannedCoordinateCount(), value.blockCount(), value.includedCoordinateCount(), "bad", value.blocks()); assertEquals("checksum mismatch", validator.validate(value, region(), "pit").reason()); }
    @Test void missingCoordinateCannotMeanAir() { Snapshot value = snapshot(region(), true, Snapshot.FORMAT_VERSION); value = new Snapshot(Snapshot.FORMAT_VERSION, "x", "pit", "world", region().worldUuid().toString(), "CUBOID", region(), region().geometryHash(), "pit", "FULL_SNAPSHOT", 1, 2, true, 2, 1, 2, SnapshotChecksum.calculate(value.blocks().subList(0, 1)), value.blocks().subList(0, 1)); assertFalse(validator.validate(value, region(), "pit").valid()); }
    @Test void blockEntityDataParticipatesInChecksum() { Snapshot value = snapshot(region(), true, Snapshot.FORMAT_VERSION); var modified = List.of(new SnapshotBlock(0,0,0,"minecraft:chest", new SnapshotBlock.BlockEntity("CONTAINER", List.of(new byte[]{1}))), value.blocks().get(1)); assertNotEquals(value.checksum(), SnapshotChecksum.calculate(modified)); }

    private RegionDescriptor region() { return descriptor("pit", UUID.fromString("00000000-0000-0000-0000-000000000001"), 1); }
    private RegionDescriptor descriptor(String id, UUID uuid, int maxX) {
        String hash = com.lincoln.maceguard.worldguard.RegionGeometryFingerprint.hash(id, uuid, "CUBOID", 0,0,0,maxX,0,0);
        return new RegionDescriptor(id, "world", uuid, "CUBOID", 0,0,0,maxX,0,0,hash,maxX + 1L);
    }
    private Snapshot snapshot(RegionDescriptor region, boolean complete, int version) {
        List<SnapshotBlock> blocks = List.of(new SnapshotBlock(0,0,0,"minecraft:stone",null), new SnapshotBlock(1,0,0,"minecraft:air",null));
        return new Snapshot(version,"test",region.id(),region.worldName(),region.worldUuid().toString(),region.type(),region,region.geometryHash(),"pit","FULL_SNAPSHOT",1,2,complete,2,2,2,SnapshotChecksum.calculate(blocks),blocks);
    }
}
