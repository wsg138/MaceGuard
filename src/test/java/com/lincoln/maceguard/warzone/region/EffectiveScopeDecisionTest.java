package com.lincoln.maceguard.warzone.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveScopeDecisionTest {
    @Test void includesOnlyResolvedOuterRegionOutsideExclusions() {
        assertTrue(EffectiveScopeDecision.contains(true, true, true, true, false));
        assertFalse(EffectiveScopeDecision.contains(true, true, true, false, false));
    }

    @Test void spawnAndMarketStyleExclusionsBypassAllWeeklyScope() {
        assertFalse(EffectiveScopeDecision.contains(true, true, true, true, true));
    }

    @Test void unresolvedOuterOrExclusionFailsClosedWithoutBroadening() {
        assertFalse(EffectiveScopeDecision.contains(true, false, true, true, false));
        assertFalse(EffectiveScopeDecision.contains(true, true, false, true, false));
        assertFalse(EffectiveScopeDecision.contains(false, true, true, true, false));
    }
}
