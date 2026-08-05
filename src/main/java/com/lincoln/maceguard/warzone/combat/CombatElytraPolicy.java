package com.lincoln.maceguard.warzone.combat;

public final class CombatElytraPolicy {
    private CombatElytraPolicy() { }

    public static boolean canStart(boolean inCombat, boolean combatBypass,
                                   boolean maceGuardBypass, boolean latched,
                                   boolean insideConfiguredWarzone,
                                   boolean elytraEffectActive,
                                   boolean elytraEffectCarries) {
        if (!inCombat || combatBypass || maceGuardBypass) return true;
        return latched && elytraEffectActive
                && (insideConfiguredWarzone || elytraEffectCarries);
    }

    public static boolean blockBoost(boolean inCombat, boolean combatBypass,
                                     boolean maceGuardBypass) {
        return inCombat && !combatBypass && !maceGuardBypass;
    }
}
