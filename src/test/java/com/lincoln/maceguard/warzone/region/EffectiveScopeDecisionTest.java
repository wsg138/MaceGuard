package com.lincoln.maceguard.warzone.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveScopeDecisionTest {
    @Test void missingOuterRegionMeansEveryLocationIsOutside() {
        assertFalse(contains(true, false, true, true, false));
    }

    @Test void missingSpawnExclusionMeansEveryLocationIsOutside() {
        assertFalse(contains(true, true, false, true, false));
    }

    @Test void missingMarketExclusionMeansEveryLocationIsOutside() {
        assertFalse(contains(true, true, false, true, false));
    }

    @Test void wrongWorldIsOutside() {
        assertFalse(contains(false, true, true, true, false));
    }

    @Test void resolvedOuterOutsideExclusionsIsInside() {
        assertTrue(contains(true, true, true, true, false));
    }

    @Test void insideSpawnIsOutside() {
        assertFalse(contains(true, true, true, true, true));
    }

    @Test void insideMarketIsOutside() {
        assertFalse(contains(true, true, true, true, true));
    }

    @Test void regionLossAndRecreationToggleExactMembership() {
        assertTrue(contains(true, true, true, true, false));
        assertFalse(contains(true, false, false, true, false));
        assertTrue(contains(true, true, true, true, false));
    }

    private boolean contains(boolean configuredWorld, boolean outerResolved,
                             boolean exclusionsResolved, boolean insideOuter,
                             boolean insideExcluded) {
        return EffectiveScopeDecision.contains(configuredWorld, outerResolved,
                exclusionsResolved, insideOuter, insideExcluded);
    }
}
