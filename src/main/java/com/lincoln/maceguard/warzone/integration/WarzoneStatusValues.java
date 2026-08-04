package com.lincoln.maceguard.warzone.integration;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WarzoneStatusValues {
    private static final RestrictionTarget MACE = target("MACE");
    private static final RestrictionTarget ENDER_PEARL = target("ENDER_PEARL");
    private static final RestrictionTarget WIND_CHARGE = target("WIND_CHARGE");
    private static final RestrictionTarget SPEAR = RestrictionTarget.SPEAR;
    private static final RestrictionTarget SPEAR_DAMAGE = RestrictionTarget.SPEAR_DAMAGE;
    private static final Pattern MODIFIER_VALUE = Pattern.compile(
            "^modifier_([1-3])(?:_(id|description))?$");

    private WarzoneStatusValues() { }

    static String resolve(String parameter, boolean scopeActive,
                          WarzoneConfig.ActiveSet active) {
        String status = resolveStatus(parameter, scopeActive, active);
        if (status != null) return status;
        String restriction = resolveRestrictionValue(parameter, scopeActive, active);
        return restriction != null
                ? restriction : resolveEffectValue(parameter, scopeActive, active);
    }

    static String resolveModifier(String parameter,
                                  Map<String, WarzoneConfig.Modifier> modifiers,
                                  WarzoneConfig.ActiveSet active) {
        Matcher matcher = MODIFIER_VALUE.matcher(parameter);
        if (!matcher.matches()) return null;
        ActiveModifier selected = activeModifier(
                modifiers, active, Integer.parseInt(matcher.group(1)));
        if (selected == null) return "";
        String field = matcher.group(2);
        if ("id".equals(field)) return selected.id();
        if ("description".equals(field)) return selected.modifier().description();
        return selected.modifier().displayName();
    }

    private static ActiveModifier activeModifier(
            Map<String, WarzoneConfig.Modifier> modifiers,
            WarzoneConfig.ActiveSet active, int oneBasedIndex) {
        if (modifiers == null || active == null) return null;
        int index = oneBasedIndex - 1;
        if (index < 0 || index >= active.modifierIds().size()) return null;
        String id = active.modifierIds().get(index);
        WarzoneConfig.Modifier modifier = modifiers.get(id);
        return modifier == null ? null : new ActiveModifier(id, modifier);
    }

    private static String resolveStatus(String parameter, boolean scopeActive,
                                        WarzoneConfig.ActiveSet active) {
        return switch (parameter) {
            case "mace_status" -> status(scopeActive, active, MACE);
            case "ender_pearl_status" -> status(scopeActive, active, ENDER_PEARL);
            case "wind_charge_status" -> status(scopeActive, active, WIND_CHARGE);
            case "spear_status" -> status(scopeActive, active, SPEAR);
            case "spear_damage_status" -> status(scopeActive, active, SPEAR_DAMAGE);
            case "spear_lunge_status" -> status(
                    scopeActive, active, RestrictionTarget.SPEAR_LUNGE);
            case "elytra_status" -> elytraStatus(scopeActive, active);
            default -> null;
        };
    }

    private static String resolveRestrictionValue(
            String parameter, boolean scopeActive,
            WarzoneConfig.ActiveSet active) {
        return switch (parameter) {
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
            case "spear_disabled" -> Boolean.toString(
                    disabled(scopeActive, active, SPEAR));
            case "spear_damage_cooldown_seconds" -> Long.toString(
                    cooldownSeconds(scopeActive, active, SPEAR_DAMAGE));
            case "spear_lunge_disabled" -> Boolean.toString(
                    disabled(scopeActive, active, RestrictionTarget.SPEAR_LUNGE));
            case "spear_lunge_cooldown_seconds" -> Long.toString(
                    cooldownSeconds(scopeActive, active, RestrictionTarget.SPEAR_LUNGE));
            default -> null;
        };
    }

    private static String resolveEffectValue(String parameter,
                                             boolean scopeActive,
                                             WarzoneConfig.ActiveSet active) {
        return switch (parameter) {
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

    private record ActiveModifier(String id, WarzoneConfig.Modifier modifier) { }
}
