package com.lincoln.maceguard.warzone.restriction;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomatedProjectileRestrictionTest {
    private static final RestrictionTarget WIND_CHARGE =
            RestrictionTarget.parse("WIND_CHARGE").orElseThrow();

    @Test void disabledWindChargesAreBlockedFromEitherEffectiveLaunchLocation() {
        WarzoneConfig.ActiveSet disabled = active(
                new WarzoneConfig.Restriction(WIND_CHARGE,
                        RestrictionMode.DISABLED, null));

        assertTrue(AutomatedProjectileRestriction.blocksWindCharge(
                disabled, true, false));
        assertTrue(AutomatedProjectileRestriction.blocksWindCharge(
                disabled, false, true));
        assertTrue(AutomatedProjectileRestriction.blocksWindCharge(
                disabled, true, true));
    }

    @Test void unresolvedOrOutsideScopeDoesNotBroadenTheRestriction() {
        WarzoneConfig.ActiveSet disabled = active(
                new WarzoneConfig.Restriction(WIND_CHARGE,
                        RestrictionMode.DISABLED, null));

        assertFalse(AutomatedProjectileRestriction.blocksWindCharge(
                disabled, false, false));
    }

    @Test void automatedSourcesIgnorePlayerCooldownModes() {
        WarzoneConfig.ActiveSet cooldown = active(
                new WarzoneConfig.Restriction(WIND_CHARGE,
                        RestrictionMode.COOLDOWN, Duration.ofSeconds(10)));
        WarzoneConfig.ActiveSet unrestricted = new WarzoneConfig.ActiveSet(
                List.of("unrestricted"), "Unrestricted", "Unrestricted",
                Set.of(), Map.of());

        assertFalse(AutomatedProjectileRestriction.blocksWindCharge(
                cooldown, true, true));
        assertFalse(AutomatedProjectileRestriction.blocksWindCharge(
                unrestricted, true, true));
    }

    private WarzoneConfig.ActiveSet active(WarzoneConfig.Restriction restriction) {
        return new WarzoneConfig.ActiveSet(List.of("wind-charge-test"),
                "Wind Charge Test", "Wind Charge Test", Set.of(),
                Map.of(WIND_CHARGE, restriction));
    }
}
