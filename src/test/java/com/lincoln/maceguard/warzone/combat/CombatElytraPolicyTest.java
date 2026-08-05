package com.lincoln.maceguard.warzone.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatElytraPolicyTest {
    @Test void outsideCombatIsUnrestricted() {
        assertTrue(CombatElytraPolicy.canStart(false, false, false,
                false, false, false, false));
        assertFalse(CombatElytraPolicy.blockBoost(false, false, false));
    }

    @Test void ordinaryCombatBlocksNewGlideAndBoost() {
        assertFalse(CombatElytraPolicy.canStart(true, false, false,
                false, false, false, false));
        assertTrue(CombatElytraPolicy.blockBoost(true, false, false));
    }

    @Test void latchedPlayerNeedsLiveEffectAndOutsideCarryover() {
        assertTrue(CombatElytraPolicy.canStart(true, false, false,
                true, true, true, false));
        assertFalse(CombatElytraPolicy.canStart(true, false, false,
                true, false, true, false));
        assertTrue(CombatElytraPolicy.canStart(true, false, false,
                true, false, true, true));
        assertFalse(CombatElytraPolicy.canStart(true, false, false,
                true, true, false, true));
    }

    @Test voidExplicitBypassesDisableMaceGuardCombatEnforcement() {
        assertTrue(CombatElytraPolicy.canStart(true, true, false,
                false, false, false, false));
        assertTrue(CombatElytraPolicy.canStart(true, false, true,
                false, false, false, false));
        assertFalse(CombatElytraPolicy.blockBoost(true, true, false));
        assertFalse(CombatElytraPolicy.blockBoost(true, false, true));
    }
}
