package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;

final class AutomatedProjectileRestriction {
    private static final RestrictionTarget WIND_CHARGE =
            RestrictionTarget.parse("WIND_CHARGE").orElseThrow();

    private AutomatedProjectileRestriction() { }

    static boolean windChargeDisabled(WarzoneConfig.ActiveSet activeSet) {
        WarzoneConfig.Restriction restriction =
                activeSet.restrictions().get(WIND_CHARGE);
        return restriction != null && restriction.mode() == RestrictionMode.DISABLED;
    }

    static boolean blocksWindCharge(WarzoneConfig.ActiveSet activeSet,
                                    boolean sourceInside,
                                    boolean launchInside) {
        return (sourceInside || launchInside) && windChargeDisabled(activeSet);
    }

    static boolean canCorrelatePending(boolean shooterAbsent,
                                       boolean defaultSpawnReason) {
        return shooterAbsent && defaultSpawnReason;
    }
}
