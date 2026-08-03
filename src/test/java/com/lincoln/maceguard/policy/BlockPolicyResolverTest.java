package com.lincoln.maceguard.policy;

import com.lincoln.maceguard.config.BlockPolicy;
import com.lincoln.maceguard.worldguard.WorldGuardQueryService;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockPolicyResolverTest {
    private final BlockPolicy policy = new BlockPolicy(
            "cobweb-box",
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB)),
            new BlockPolicy.MaterialRule(true, Set.of(Material.COBWEB)),
            new BlockPolicy.BucketRule(Set.of(Material.WATER), Set.of(Material.WATER)),
            new BlockPolicy.LiquidRule(true, true), false);

    @Test void disabledInvalidSchemaMissingFlagAndNoValueAreInactive() {
        var reference = reference("cobweb-box", "direct", false);
        assertInactive(BlockPolicyResolver.decide(false, true, true, reference,
                Map.of(policy.name(), policy)), BlockPolicyResolver.Status.MACEGUARD_DISABLED);
        assertInactive(BlockPolicyResolver.decide(true, false, true, reference,
                Map.of(policy.name(), policy)), BlockPolicyResolver.Status.INVALID_SCHEMA);
        assertInactive(BlockPolicyResolver.decide(true, true, false, reference,
                Map.of(policy.name(), policy)), BlockPolicyResolver.Status.FLAG_UNAVAILABLE);
        assertInactive(BlockPolicyResolver.decide(true, true, true, null,
                Map.of(policy.name(), policy)), BlockPolicyResolver.Status.NO_EFFECTIVE_VALUE);
    }

    @Test void validSchemaPreservesFailClosedMissingNamedReference() {
        var result = BlockPolicyResolver.decide(true, true, true,
                reference("missing-policy", "direct", false), Map.of());
        assertTrue(result.referenced());
        assertNull(result.policy());
        assertEquals(BlockPolicyResolver.Status.REFERENCED_POLICY_MISSING, result.status());
        assertTrue(result.schemaAllowsEnforcement());
        assertTrue(result.finalResult().contains("fail closed"));
    }

    @Test void validNamedPolicyIsActive() {
        var result = BlockPolicyResolver.decide(true, true, true,
                reference(policy.name(), "inherited", false), Map.of(policy.name(), policy));
        assertTrue(result.referenced());
        assertSame(policy, result.policy());
        assertEquals("inherited", result.sourceKind());
        assertEquals(BlockPolicyResolver.Status.ACTIVE, result.status());
    }

    @Test void globalEffectiveSourceIsIdentifiedWithoutBeingChanged() {
        var result = BlockPolicyResolver.decide(true, true, true,
                new WorldGuardQueryService.BlockPolicyReference("__global__", policy.name(),
                        "global", true), Map.of(policy.name(), policy));
        assertTrue(result.globalSource());
        assertEquals("__global__", result.scopeId());
        assertEquals("global", result.sourceKind());
        assertSame(policy, result.policy());
    }

    private WorldGuardQueryService.BlockPolicyReference reference(String name, String kind,
                                                                    boolean global) {
        return new WorldGuardQueryService.BlockPolicyReference("cobweb-box", name, kind, global);
    }

    private void assertInactive(BlockPolicyResolver.Resolution result,
                                BlockPolicyResolver.Status status) {
        assertFalse(result.referenced());
        assertNull(result.policy());
        assertEquals(status, result.status());
        assertFalse(result.schemaAllowsEnforcement());
    }
}
