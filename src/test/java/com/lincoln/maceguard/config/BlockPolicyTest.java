package com.lincoln.maceguard.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPolicyTest {
    private final BlockPolicy policy = new BlockPolicy(
            "cobweb-box",
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB, Material.ICE)),
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB, Material.ICE)),
            new BlockPolicy.BucketRule(Set.of(Material.WATER), Set.of(Material.WATER)),
            new BlockPolicy.LiquidRule(true, true),
            false);

    @Test void allowsOnlyWhitelistedPlacementAndBreaking() {
        assertTrue(policy.place().allows(Material.COBWEB));
        assertTrue(policy.place().allows(Material.ICE));
        assertFalse(policy.place().allows(Material.STONE));
        assertTrue(policy.breakRule().allows(Material.COBWEB));
        assertFalse(policy.breakRule().allows(Material.OBSIDIAN));
    }

    @Test void allowsWaterBucketsButNotOtherFluids() {
        assertTrue(policy.buckets().empty().contains(Material.WATER));
        assertTrue(policy.buckets().fill().contains(Material.WATER));
        assertFalse(policy.buckets().empty().contains(Material.LAVA));
        assertFalse(policy.buckets().fill().contains(Material.LAVA));
    }

    @Test void confinesLiquidsBlocksInfiniteSourcesAndRejectsAutomation() {
        assertTrue(policy.liquids().confineToRegion());
        assertTrue(policy.liquids().blockInfiniteWaterSources());
        assertFalse(policy.allowNonPlayerSources());
    }

    @Test void denyUnlistedFalseActsAsAnExplicitDenyList() {
        var denyList = new BlockPolicy.MaterialRule(false, Set.of(Material.TNT));
        assertFalse(denyList.allows(Material.TNT));
        assertTrue(denyList.allows(Material.STONE));
    }
}
