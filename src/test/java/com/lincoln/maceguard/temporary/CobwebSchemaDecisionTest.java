package com.lincoln.maceguard.temporary;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.policy.BlockPolicyResolver;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CobwebSchemaDecisionTest {
    private final BlockPolicy policy = new BlockPolicy(
            "cobweb-box",
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB)),
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB)),
            new BlockPolicy.BucketRule(Set.of(Material.WATER), Set.of(Material.WATER)),
            new BlockPolicy.LiquidRule(true, true), false);

    @Test void invalidMainSchemaDisablesBothCobwebHandlers() {
        assertFalse(CobwebListener.handlersEnabled(true, false));
        assertFalse(CobwebListener.handlersEnabled(false, true));
        assertTrue(CobwebListener.handlersEnabled(true, true));
    }

    @Test void temporaryPolicyCobwebsRequirePolicyAndCobwebFlagAndBuildPermission() {
        var resolution = new BlockPolicyResolver.Resolution("cobweb-box", policy.name(), policy,
                true, "direct", false, BlockPolicyResolver.Status.ACTIVE);
        assertTrue(CobwebListener.policyTemporaryAllowed(resolution, true, true));
        assertFalse(CobwebListener.policyTemporaryAllowed(resolution, false, true));
        assertFalse(CobwebListener.policyTemporaryAllowed(resolution, true, false));
    }

    @Test void missingReferencedPolicyCannotCreateTemporaryCobwebs() {
        var missing = new BlockPolicyResolver.Resolution("cobweb-box", "missing", null,
                true, "direct", false,
                BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING);
        assertFalse(CobwebListener.policyTemporaryAllowed(missing, true, true));
    }
}
