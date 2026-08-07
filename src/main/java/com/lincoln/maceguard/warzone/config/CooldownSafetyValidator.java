package com.lincoln.maceguard.warzone.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runtime-facing safety validation for durations that convert to authoritative epoch deadlines. */
public final class CooldownSafetyValidator {
    public static final Duration MAX_COOLDOWN = Duration.ofDays(365);

    private CooldownSafetyValidator() { }

    public static List<String> validate(WarzoneControlConfig control) {
        if (control == null || control.gameplay() == null) return List.of();
        List<String> errors = new ArrayList<>();
        WarzoneConfig gameplay = control.gameplay();
        gameplay.targetPolicies().forEach((target, policy) -> check(
                policy.maximumCooldown(),
                "restriction-targets." + target.id() + ".maximum-cooldown", errors));
        gameplay.modifiers().forEach((modifierId, modifier) ->
                checkRestrictions(modifierId, modifier.restrictions(), errors));
        return List.copyOf(errors);
    }

    private static void checkRestrictions(String modifierId,
            Map<com.lincoln.maceguard.warzone.restriction.RestrictionTarget,
                    WarzoneConfig.Restriction> restrictions, List<String> errors) {
        restrictions.forEach((target, restriction) -> check(restriction.cooldown(),
                "modifiers." + modifierId + ".restrictions." + target.id() + ".cooldown",
                errors));
    }

    private static void check(Duration value, String path, List<String> errors) {
        if (value != null && value.compareTo(MAX_COOLDOWN) > 0)
            errors.add(path + " must not exceed 365d.");
    }
}
