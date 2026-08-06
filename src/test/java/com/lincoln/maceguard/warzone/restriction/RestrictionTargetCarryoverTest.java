package com.lincoln.maceguard.warzone.restriction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestrictionTargetCarryoverTest {
    @Test void tridentRemainsLocationBound() {
        assertFalse(RestrictionTarget.parse("TRIDENT").orElseThrow().combatCarryoverEligible());
    }

    @Test void documentedCarryoverTargetsRemainEligible() {
        assertTrue(RestrictionTarget.parse("MACE").orElseThrow().combatCarryoverEligible());
        assertTrue(RestrictionTarget.parse("ENDER_PEARL").orElseThrow().combatCarryoverEligible());
        assertTrue(RestrictionTarget.parse("WIND_CHARGE").orElseThrow().combatCarryoverEligible());
        assertTrue(RestrictionTarget.SPEAR.combatCarryoverEligible());
        assertTrue(RestrictionTarget.SPEAR_LUNGE.combatCarryoverEligible());
    }
}
