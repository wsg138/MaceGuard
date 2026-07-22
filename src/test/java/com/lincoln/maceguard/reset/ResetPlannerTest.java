package com.lincoln.maceguard.reset;

import com.lincoln.maceguard.worldguard.RegionDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResetPlannerTest {
    @Test void countsAirNonAirExclusionsAndBatches() {
        Snapshot snapshot = snapshot();
        ResetPlan plan = new ResetPlanner().plan(snapshot, current("minecraft:dirt", "minecraft:stone"), block -> block.x() == 99, 1);
        assertEquals(2, plan.totalChanges()); assertEquals(1, plan.airChanges()); assertEquals(1, plan.nonAirChanges()); assertEquals(2, plan.estimatedBatches());
    }
    @Test void thresholdViolationsRefuseAutomaticAndManualPlanning() {
        ResetPlan plan = new ResetPlanner().plan(snapshot(), current("minecraft:dirt", "minecraft:stone"), block -> false, 10);
        assertTrue(new ResetPlanner().refusal(plan, 1, 1).contains("configured maximum"));
        assertTrue(new ResetPlanner().refusal(plan, 5, 0).contains("air"));
    }
    @Test void confirmationTokenIsBoundToCurrentPlanAndSingleUse() {
        ResetPlan first = new ResetPlanner().plan(snapshot(), current("minecraft:dirt", "minecraft:stone"), block -> false, 10);
        ResetPlan changed = new ResetPlanner().plan(snapshot(), current("minecraft:stone", "minecraft:stone"), block -> false, 10);
        ConfirmationTokens tokens = new ConfirmationTokens();
        String token = tokens.issue(first);
        assertFalse(tokens.consume(token, changed));
        String current = tokens.issue(first); assertTrue(tokens.consume(current, first)); assertFalse(tokens.consume(current, first));
        ResetPlan recomputed = new ResetPlanner().plan(snapshot(), current("minecraft:dirt", "minecraft:stone"), block -> false, 10);
        assertEquals(first.planHash(), recomputed.planHash());
    }
    @Test void changedContainerContentsArePlannedEvenWhenBlockDataMatches() {
        Snapshot base = snapshot();
        SnapshotBlock container = new SnapshotBlock(0,0,0,"minecraft:chest",new SnapshotBlock.BlockEntity("CONTAINER", List.of(new byte[]{1})));
        List<SnapshotBlock> targets = List.of(container, base.blocks().get(1));
        Snapshot withContainer = new Snapshot(1,"test","pit","world",base.worldUuid(),"CUBOID",base.geometry(),base.geometryHash(),"pit",1,2,true,2,2,SnapshotChecksum.calculate(targets),targets);
        List<SnapshotBlock> current = List.of(new SnapshotBlock(0,0,0,"minecraft:chest",new SnapshotBlock.BlockEntity("CONTAINER", List.of(new byte[]{2}))), base.blocks().get(1));
        ResetPlan plan = new ResetPlanner().plan(withContainer, current, block -> false, 10);
        assertEquals(1, plan.totalChanges()); assertEquals(1, plan.blockEntities());
    }
    private Snapshot snapshot() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String hash = com.lincoln.maceguard.worldguard.RegionGeometryFingerprint.hash("pit",uuid,"CUBOID",0,0,0,1,0,0);
        RegionDescriptor region = new RegionDescriptor("pit","world",uuid,"CUBOID",0,0,0,1,0,0,hash,2);
        List<SnapshotBlock> blocks = List.of(new SnapshotBlock(0,0,0,"minecraft:stone",null),new SnapshotBlock(1,0,0,"minecraft:air",null));
        return new Snapshot(1,"test","pit","world",uuid.toString(),"CUBOID",region,hash,"pit",1,2,true,2,2,SnapshotChecksum.calculate(blocks),blocks);
    }
    private List<SnapshotBlock> current(String first, String second) { return List.of(new SnapshotBlock(0,0,0,first,null), new SnapshotBlock(1,0,0,second,null)); }
}
