package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.config.WarzoneControlConfigLoader;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import com.lincoln.maceguard.warzone.rotation.ModifierSelector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneTransitionPolicyTest {
    @Test void cleanupTargetsContainOnlyChangedRestrictionPolicies() {
        WarzoneConfig config = config();
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1));
        WarzoneConfig.ActiveSet before = selector.composeExact(config,
                List.of("mace-cooldown", "ender-pearl-cooldown-5"));
        WarzoneConfig.ActiveSet after = selector.composeExact(config,
                List.of("mace-cooldown", "wind-charge-cooldown-5"));

        var changed = WarzoneRuntime.changedRestrictionTargets(before, after);
        assertFalse(changed.contains(RestrictionTarget.parse("MACE").orElseThrow()));
        assertTrue(changed.contains(RestrictionTarget.parse("ENDER_PEARL").orElseThrow()));
        assertTrue(changed.contains(RestrictionTarget.parse("WIND_CHARGE").orElseThrow()));
    }

    @Test void cobwebCleanupOccursOnlyWhenAbilityIsRemoved() {
        WarzoneConfig config = config();
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1));
        WarzoneConfig.ActiveSet with = selector.composeExact(config, List.of("cobwebs"));
        WarzoneConfig.ActiveSet without = selector.composeExact(config, List.of());

        assertTrue(WarzoneRuntime.shouldClearCobwebs(with, without, config.cobwebs()));
        assertFalse(WarzoneRuntime.shouldClearCobwebs(without, with, config.cobwebs()));
        assertFalse(WarzoneRuntime.shouldClearCobwebs(with, with, config.cobwebs()));
    }

    @Test void unchangedRestrictionDoesNotClearCooldownAcrossSourceOnlyChange() {
        WarzoneConfig config = config();
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1));
        WarzoneConfig.ActiveSet first = selector.composeExact(config, List.of("mace-cooldown"));
        WarzoneConfig.ActiveSet second = selector.composeExact(config, List.of("mace-cooldown"));
        assertTrue(WarzoneRuntime.changedRestrictionTargets(first, second).isEmpty());
    }

    private WarzoneConfig config() {
        var result = new WarzoneControlConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
        assertTrue(result.valid(), result.errors().toString());
        return result.value().gameplay();
    }
}
