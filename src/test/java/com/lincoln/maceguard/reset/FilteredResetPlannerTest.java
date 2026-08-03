package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.config.ResetProfile;
import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilteredResetPlannerTest {
    @Test void filteredRestoreChangesOnlyExplicitReplaceableCurrentMaterials() {
        RegionDescriptor region = new RegionDescriptor("warzone-reset", "world",
                UUID.randomUUID(), "cuboid", 0, 0, 0, 1, 0, 0, "hash", 2);
        List<SnapshotBlock> targets = List.of(
                block(0, "minecraft:short_grass"),
                block(1, "minecraft:fern"));
        Snapshot snapshot = new Snapshot(Snapshot.FORMAT_VERSION, "5.0.0",
                region.id(), region.worldName(), region.worldUuid().toString(),
                region.type(), region, region.geometryHash(), "warzone-environment",
                ResetProfile.Mode.FILTERED_SNAPSHOT.name(), 1, 2, true, 2,
                2, 2, SnapshotChecksum.calculate(targets), targets);
        ResetProfile profile = new ResetProfile("warzone-environment",
                ResetProfile.Mode.FILTERED_SNAPSHOT, 1440, 100, 10, 10, 10,
                Set.of(Material.SHORT_GRASS, Material.FERN),
                Set.of(Material.AIR, Material.WATER, Material.LAVA,
                        Material.SHORT_GRASS, Material.FERN),
                ResetProfile.SolidConflictPolicy.SKIP_AND_REPORT, List.of());

        List<SnapshotBlock> current = List.of(
                block(0, "minecraft:water[level=0]"),
                block(1, "minecraft:stone"));
        ResetPlan plan = new ResetPlanner().plan(snapshot, current,
                ignored -> false, 100, profile);

        assertEquals(1, plan.totalChanges());
        assertEquals(1, plan.unsupportedStates());
        assertEquals(0, plan.changes().getFirst().x());
    }

    @Test void captureChecksumAndFilteredValidationPreserveBlockData() {
        RegionDescriptor region = new RegionDescriptor("warzone-reset", "world",
                UUID.randomUUID(), "cuboid", 0, 0, 0, 0, 0, 0, "hash", 1);
        List<SnapshotBlock> targets = List.of(
                block(0, "minecraft:short_grass[snowy=true]"));
        Snapshot snapshot = new Snapshot(Snapshot.FORMAT_VERSION, "5.0.0",
                region.id(), region.worldName(), region.worldUuid().toString(),
                region.type(), region, region.geometryHash(), "warzone-environment",
                ResetProfile.Mode.FILTERED_SNAPSHOT.name(), 1, 2, true, 1,
                1, 1, SnapshotChecksum.calculate(targets), targets);
        ResetProfile profile = new ResetProfile("warzone-environment",
                ResetProfile.Mode.FILTERED_SNAPSHOT, 1440, 100, 10, 10, 10,
                Set.of(Material.SHORT_GRASS),
                Set.of(Material.AIR, Material.WATER),
                ResetProfile.SolidConflictPolicy.SKIP_AND_REPORT, List.of());
        assertEquals(true, new SnapshotValidator().validate(snapshot, region, profile).valid());
    }

    private SnapshotBlock block(int x, String data) {
        return new SnapshotBlock(x, 0, 0, data, null);
    }
}
