package com.lincoln.maceguard.worldguard;

/** Explicit fail-closed composition for custom behavior; it never grants the underlying action. */
public final class CustomBehaviorDecision {
    private CustomBehaviorDecision() { }
    public static boolean enabled(boolean worldGuardAllowsUnderlyingAction, boolean effectiveCustomFlagAllows, boolean integrationAllows) {
        return worldGuardAllowsUnderlyingAction && effectiveCustomFlagAllows && integrationAllows;
    }
}
