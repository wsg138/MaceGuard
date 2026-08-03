package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WarzoneStatusValuesTest {
    private static final RestrictionTarget MACE = target("MACE");
    private static final RestrictionTarget PEARL = target("ENDER_PEARL");
    private static final RestrictionTarget WIND = target("WIND_CHARGE");

    @Test void allowedStateCoversEveryHumanAndMachinePlaceholder() {
        WarzoneConfig.ActiveSet active = active(Set.of(), Map.of());
        assertEquals("Allowed", value("mace_status", true, active));
        assertEquals("Allowed", value("ender_pearl_status", true, active));
        assertEquals("Allowed", value("wind_charge_status", true, active));
        assertEquals("Allowed", value("spear_lunge_status", true, active));
        assertEquals("Disabled", value("elytra_status", true, active));
        assertEquals("false", value("mace_disabled", true, active));
        assertEquals("0", value("mace_cooldown_seconds", true, active));
        assertEquals("false", value("ender_pearl_disabled", true, active));
        assertEquals("0", value("ender_pearl_cooldown_seconds", true, active));
        assertEquals("false", value("wind_charge_disabled", true, active));
        assertEquals("0", value("wind_charge_cooldown_seconds", true, active));
        assertEquals("false", value("spear_lunge_disabled", true, active));
        assertEquals("false", value("elytra_gliding_allowed", true, active));
        assertEquals("false", value("firework_boost_blocked", true, active));
        assertNull(value("unknown", true, active));
    }

    @Test void disabledStateReportsEveryDisabledTarget() {
        WarzoneConfig.ActiveSet active = active(Set.of(), Map.of(
                MACE, restriction(MACE, RestrictionMode.DISABLED, null),
                PEARL, restriction(PEARL, RestrictionMode.DISABLED, null),
                WIND, restriction(WIND, RestrictionMode.DISABLED, null),
                RestrictionTarget.SPEAR_LUNGE,
                restriction(RestrictionTarget.SPEAR_LUNGE,
                        RestrictionMode.DISABLED, null)));
        assertEquals("Disabled", value("mace_status", true, active));
        assertEquals("Disabled", value("ender_pearl_status", true, active));
        assertEquals("Disabled", value("wind_charge_status", true, active));
        assertEquals("Disabled", value("spear_lunge_status", true, active));
        assertEquals("true", value("mace_disabled", true, active));
        assertEquals("true", value("ender_pearl_disabled", true, active));
        assertEquals("true", value("wind_charge_disabled", true, active));
        assertEquals("true", value("spear_lunge_disabled", true, active));
    }

    @Test void cooldownStateReportsConfiguredSeconds() {
        WarzoneConfig.ActiveSet active = active(Set.of(), Map.of(
                MACE, restriction(MACE, RestrictionMode.COOLDOWN,
                        Duration.ofSeconds(10)),
                PEARL, restriction(PEARL, RestrictionMode.COOLDOWN,
                        Duration.ofSeconds(5)),
                WIND, restriction(WIND, RestrictionMode.COOLDOWN,
                        Duration.ofSeconds(10))));
        assertEquals("10s cooldown", value("mace_status", true, active));
        assertEquals("5s cooldown", value("ender_pearl_status", true, active));
        assertEquals("10s cooldown", value("wind_charge_status", true, active));
        assertEquals("10", value("mace_cooldown_seconds", true, active));
        assertEquals("5", value("ender_pearl_cooldown_seconds", true, active));
        assertEquals("10", value("wind_charge_cooldown_seconds", true, active));
    }

    @Test void inactiveScopeOverridesEveryEffectiveValue() {
        WarzoneConfig.ActiveSet active = active(
                Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of(
                        MACE, restriction(MACE, RestrictionMode.DISABLED, null),
                        PEARL, restriction(PEARL, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(5)),
                        WIND, restriction(WIND, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10)),
                        RestrictionTarget.SPEAR_LUNGE,
                        restriction(RestrictionTarget.SPEAR_LUNGE,
                                RestrictionMode.DISABLED, null)));
        for (String status : List.of("mace_status", "ender_pearl_status",
                "wind_charge_status", "spear_lunge_status", "elytra_status"))
            assertEquals("Inactive", value(status, false, active));
        for (String bool : List.of("mace_disabled", "ender_pearl_disabled",
                "wind_charge_disabled", "spear_lunge_disabled",
                "elytra_gliding_allowed", "firework_boost_blocked"))
            assertEquals("false", value(bool, false, active));
        for (String cooldown : List.of("mace_cooldown_seconds",
                "ender_pearl_cooldown_seconds", "wind_charge_cooldown_seconds"))
            assertEquals("0", value(cooldown, false, active));
    }

    @Test void elytraStateReportsGlidingAndBlockedBoosts() {
        WarzoneConfig.ActiveSet active = active(
                Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of());
        assertEquals("Gliding allowed; rockets disabled",
                value("elytra_status", true, active));
        assertEquals("true", value("elytra_gliding_allowed", true, active));
        assertEquals("true", value("firework_boost_blocked", true, active));
    }

    private String value(String parameter, boolean scopeActive,
                         WarzoneConfig.ActiveSet active) {
        return WarzoneStatusValues.resolve(parameter, scopeActive, active);
    }

    private WarzoneConfig.ActiveSet active(
            Set<WarzoneConfig.Effect> effects,
            Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions) {
        return new WarzoneConfig.ActiveSet(List.of("status-test"),
                "Status Test", "Status Test", effects, restrictions);
    }

    private WarzoneConfig.Restriction restriction(
            RestrictionTarget target, RestrictionMode mode, Duration cooldown) {
        return new WarzoneConfig.Restriction(target, mode, cooldown);
    }

    private static RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }
}
