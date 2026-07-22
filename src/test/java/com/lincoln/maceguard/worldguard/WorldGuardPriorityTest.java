package com.lincoln.maceguard.worldguard;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.RegionResultSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldGuardPriorityTest {
    private final StateFlag behavior = new StateFlag("test-maceguard-behavior", false);

    @Test void missingCustomFlagProducesNoBehavior() {
        ProtectedCuboidRegion region = region("warzone", 10);
        assertNull(new RegionResultSet(new java.util.ArrayList<>(List.of(region)), null).queryState(null, behavior));
    }

    @Test void higherPriorityWorldGuardRegionControlsOverlap() {
        ProtectedCuboidRegion broad = region("warzone", 10);
        broad.setFlag(behavior, StateFlag.State.ALLOW);
        ProtectedCuboidRegion market = region("market", 50);
        market.setFlag(behavior, StateFlag.State.DENY);
        assertEquals(StateFlag.State.DENY, new RegionResultSet(new java.util.ArrayList<>(List.of(market, broad)), null).queryState(null, behavior));
    }

    @Test void samePriorityDenyWinsThroughWorldGuardCalculation() {
        ProtectedCuboidRegion first = region("first", 10); first.setFlag(behavior, StateFlag.State.ALLOW);
        ProtectedCuboidRegion second = region("second", 10); second.setFlag(behavior, StateFlag.State.DENY);
        assertEquals(StateFlag.State.DENY, new RegionResultSet(new java.util.ArrayList<>(List.of(first, second)), null).queryState(null, behavior));
    }

    private ProtectedCuboidRegion region(String id, int priority) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(0, 0, 0), BlockVector3.at(10, 10, 10));
        region.setPriority(priority);
        return region;
    }
}
