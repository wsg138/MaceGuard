package com.lincoln.maceguard.warzone.region;

/** Pure effective-scope rule shared by WorldGuard-backed runtime decisions and unit tests. */
public final class EffectiveScopeDecision {
    private EffectiveScopeDecision() { }

    public static boolean contains(boolean configuredWorld, boolean outerResolved,
                                   boolean exclusionsResolved, boolean insideOuter,
                                   boolean insideExcludedRegion) {
        return configuredWorld && outerResolved && exclusionsResolved
                && insideOuter && !insideExcludedRegion;
    }
}
