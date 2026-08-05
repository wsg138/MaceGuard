package com.lincoln.maceguard.warzone.combat;

public final class StasisPolicy {
    private StasisPolicy() { }

    public static boolean shouldBlock(boolean aged, boolean inCombat,
                                      boolean combatBypass, boolean maceGuardBypass,
                                      boolean latched, boolean stasisDenied) {
        return aged && inCombat && !combatBypass && !maceGuardBypass
                && latched && stasisDenied;
    }
}
