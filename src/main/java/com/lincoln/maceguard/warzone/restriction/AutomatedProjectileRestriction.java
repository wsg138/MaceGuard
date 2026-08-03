package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

final class AutomatedProjectileRestriction {
    private static final RestrictionTarget WIND_CHARGE =
            RestrictionTarget.parse("WIND_CHARGE").orElseThrow();

    private AutomatedProjectileRestriction() { }

    static boolean blocksWindCharge(WarzoneConfig.ActiveSet activeSet,
                                    boolean sourceInside,
                                    boolean launchInside) {
        if (!sourceInside && !launchInside) return false;
        WarzoneConfig.Restriction restriction =
                activeSet.restrictions().get(WIND_CHARGE);
        return restriction != null && restriction.mode() == RestrictionMode.DISABLED;
    }
}
