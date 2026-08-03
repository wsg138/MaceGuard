package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;

final class WarzoneStatusValues {
    private static final RestrictionTarget MACE = target("MACE");
    private static final RestrictionTarget ENDER_PEARL = target("ENDER_PEARL");
    private static final RestrictionTarget WIND_CHARGE = target("WIND_CHARGE");

    private WarzoneStatusValues() { }

    static String resolve(String parameter, boolean scopeActive,
                          WarzoneConfig.ActiveSet active) {
        return switch (parameter) {
            case "mace_status" -> status(scopeActive, active, MACE);
            case "ender_pearl_status" -> status(scopeActive, active, ENDER_PEARL);
            case "wind_charge_status" -> status(scopeActive, active, WIND_CHARGE);
            case "spear_lunge_status" -> status(
                    scopeActive, active, RestrictionTarget.SPEAR_LUNGE);
            case "elytra_status" -> elytraStatus(scopeActive, active);
            case "mace_disabled" -> Boolean.toString(
                    disabled(scopeActive, active, MACE));
            case "mace_cooldown_seconds" -> Long.toString(
                    cooldownSeconds(scopeActive, active, MACE));
            case "ender_pearl_disabled" -> Boolean.toString(
                    disabled(scopeActive, active, ENDER_PEARL));
            case "ender_pearl_cooldown_seconds" -> Long.toString(
                    cooldownSeconds(scopeActive, active, ENDER_PEARL));
            case "wind_charge_disabled" -> Boolean.toString(
                    disabled(scopeActive, active, WIND_CHARGE));
            case "wind_charge_cooldown_seconds" -> Long.toString(
                    cooldownSeconds(scopeActive, active, WIND_CHARGE));
            case "spear_lunge_disabled" -> Boolean.toString(
                    disabled(scopeActive, active, RestrictionTarget.SPEAR_LUNGE));
            case "elytra_gliding_allowed" -> Boolean.toString(
                    scopeActive && active.elytraGlidingAllowed());
            case "firework_boost_blocked" -> Boolean.toString(
                    scopeActive && active.fireworkBoostBlocked());
            default -> null;
        };
    }

    private static String status(boolean scopeActive, WarzoneConfig.ActiveSet active,
                                 RestrictionTarget target) {
        if (!scopeActive) return "Inactive";
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        if (restriction == null) return "Allowed";
        if (restriction.mode() == RestrictionMode.DISABLED) return "Disabled";
        return restriction.cooldown().getSeconds() + "s cooldown";
    }

    private static String elytraStatus(boolean scopeActive,
                                       WarzoneConfig.ActiveSet active) {
        if (!scopeActive) return "Inactive";
        return active.elytraGlidingAllowed()
                ? "Gliding allowed; rockets disabled" : "Disabled";
    }

    private static boolean disabled(boolean scopeActive,
                                    WarzoneConfig.ActiveSet active,
                                    RestrictionTarget target) {
        if (!scopeActive) return false;
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        return restriction != null && restriction.mode() == RestrictionMode.DISABLED;
    }

    private static long cooldownSeconds(boolean scopeActive,
                                        WarzoneConfig.ActiveSet active,
                                        RestrictionTarget target) {
        if (!scopeActive) return 0;
        WarzoneConfig.Restriction restriction = active.restrictions().get(target);
        return restriction != null && restriction.mode() == RestrictionMode.COOLDOWN
                ? restriction.cooldown().getSeconds() : 0;
    }

    private static RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }
}
