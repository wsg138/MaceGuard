package com.lincoln.maceguard.worldguard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomBehaviorDecisionTest {
    @Test void worldGuardDenialIsNeverOverridden() { assertFalse(CustomBehaviorDecision.enabled(false, true, true)); }
    @Test void missingOrDeniedFlagDoesNothing() { assertFalse(CustomBehaviorDecision.enabled(true, false, true)); }
    @Test void integrationFailureDoesNotInterfereWithUnderlyingBuild() { assertFalse(CustomBehaviorDecision.enabled(true, true, false)); }
    @Test void allAuthoritiesMustAllowCustomBehavior() { assertTrue(CustomBehaviorDecision.enabled(true, true, true)); }
    @Test void legacyBuildProtectionEngineIsGone() { assertThrows(ClassNotFoundException.class, () -> Class.forName("com.lincoln.maceguard.adapter.bukkit.listener.BuildProtectionListener")); }
}
