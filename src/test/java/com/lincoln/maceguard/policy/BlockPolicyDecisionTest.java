package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPolicyDecisionTest {
    private final BlockPolicy policy = new BlockPolicy(
            "cobweb-box",
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB, Material.ICE)),
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB, Material.ICE)),
            new BlockPolicy.BucketRule(Set.of(Material.WATER), Set.of(Material.WATER)),
            new BlockPolicy.LiquidRule(true, true),
            false);

    @Test void placementBreakingAndBucketsUseTheConfiguredWhitelist() {
        var resolution = resolved("box-a");
        assertTrue(BlockPolicyListener.placeAllowed(resolution, Material.COBWEB));
        assertTrue(BlockPolicyListener.breakAllowed(resolution, Material.ICE));
        assertFalse(BlockPolicyListener.placeAllowed(resolution, Material.STONE));
        assertFalse(BlockPolicyListener.breakAllowed(resolution, Material.OBSIDIAN));
        assertTrue(BlockPolicyListener.bucketEmptyAllowed(resolution, Material.WATER));
        assertTrue(BlockPolicyListener.bucketFillAllowed(resolution, Material.WATER));
        assertFalse(BlockPolicyListener.bucketEmptyAllowed(resolution, Material.LAVA));
        assertFalse(BlockPolicyListener.bucketFillAllowed(resolution, Material.LAVA));
    }

    @Test void missingReferencedPolicyFailsClosed() {
        var missing = new BlockPolicyResolver.Resolution("box-a", "missing", null, true,
                "direct", false, BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING);
        assertFalse(BlockPolicyListener.placeAllowed(missing, Material.COBWEB));
        assertFalse(BlockPolicyListener.breakAllowed(missing, Material.COBWEB));
        assertFalse(BlockPolicyListener.bucketEmptyAllowed(missing, Material.WATER));
        assertFalse(BlockPolicyListener.bucketFillAllowed(missing, Material.WATER));
        assertTrue(BlockPolicyListener.flowDenied(missing, none(), false));
        assertTrue(BlockPolicyListener.automationDenied(missing));
    }

    @Test void liquidStaysInsideTheExactWorldGuardPolicyRegion() {
        var source = resolved("box-a");
        var sameBox = resolved("box-a");
        var anotherBoxUsingSamePolicy = resolved("box-b");

        assertFalse(BlockPolicyListener.flowDenied(source, sameBox, false));
        assertTrue(BlockPolicyListener.flowDenied(source, anotherBoxUsingSamePolicy, false));
        assertTrue(BlockPolicyListener.flowDenied(source, none(), false));
        assertTrue(BlockPolicyListener.flowDenied(none(), source, false));
    }

    @Test void infiniteWaterCreationIsDeniedInsideTheSameBox() {
        var source = resolved("box-a");
        assertTrue(BlockPolicyListener.flowDenied(source, resolved("box-a"), true));
    }

    @Test void nonPlayerSourcesAreDeniedButOutsideLocationsRemainUntouched() {
        assertTrue(BlockPolicyListener.automationDenied(resolved("box-a")));
        assertFalse(BlockPolicyListener.automationDenied(none()));
    }

    private BlockPolicyResolver.Resolution resolved(String scopeId) {
        return new BlockPolicyResolver.Resolution(scopeId, policy.name(), policy, true,
                "direct", false, BlockPolicyResolver.Status.ACTIVE);
    }

    private BlockPolicyResolver.Resolution none() {
        return BlockPolicyResolver.Resolution.none(
                BlockPolicyResolver.Status.NO_EFFECTIVE_VALUE);
    }
}
