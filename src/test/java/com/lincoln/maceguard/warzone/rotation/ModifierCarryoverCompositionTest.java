package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifierCarryoverCompositionTest {
    @Test void onlyExactModifiersMarkedForCarryoverPopulateCarriedPolicy() {
        RestrictionTarget fiveTarget = RestrictionTarget.parse("WIND_CHARGE").orElseThrow();
        WarzoneConfig.Restriction five = new WarzoneConfig.Restriction(
                fiveTarget, RestrictionMode.COOLDOWN, Duration.ofSeconds(5));
        WarzoneConfig.Restriction ten = new WarzoneConfig.Restriction(
                fiveTarget, RestrictionMode.COOLDOWN, Duration.ofSeconds(10));
        WarzoneConfig config = config(Map.of(
                "five", modifier("five", true, Map.of(fiveTarget, five)),
                "ten", modifier("ten", false, Map.of(fiveTarget, ten)),
                "elytra", new WarzoneConfig.Modifier("elytra", true, 1, true,
                        "Elytra", "", Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS),
                        Map.of(), null, null, null)));
        ModifierSelector selector = new ModifierSelector(new Random(1L));
        WarzoneConfig.ActiveSet fiveOnly = selector.composeExact(config, List.of("five", "elytra"));
        assertTrue(fiveOnly.carriedRestrictions().containsKey(fiveTarget));
        assertTrue(fiveOnly.carriedElytraGlidingAllowed());

        WarzoneConfig.ActiveSet tenOnly = selector.composeExact(config, List.of("ten"));
        assertFalse(tenOnly.carriedRestrictions().containsKey(fiveTarget));
    }

    private WarzoneConfig.Modifier modifier(String id, boolean carry,
                                             Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions) {
        return new WarzoneConfig.Modifier(id, true, 1, carry, id, "", Set.of(), restrictions,
                null, null, null);
    }

    private WarzoneConfig config(Map<String, WarzoneConfig.Modifier> modifiers) {
        RestrictionTarget target = RestrictionTarget.parse("WIND_CHARGE").orElseThrow();
        return new WarzoneConfig(5, true,
                new WarzoneConfig.Region("world", "warzone", List.of()),
                new WarzoneConfig.Schedule(DayOfWeek.SUNDAY, LocalTime.NOON, ZoneId.of("UTC")),
                new WarzoneConfig.Selection(WarzoneConfig.Selection.Mode.WEIGHTED_RANDOM_MODIFIERS,
                        1, 3, false, Map.of(1, 1, 2, 1, 3, 1)), Map.of(), List.of(),
                new WarzoneConfig.Messages(Duration.ofSeconds(2), WarzoneConfig.Audience.GLOBAL,
                        WarzoneConfig.Audience.GLOBAL),
                new WarzoneConfig.Cobwebs(Duration.ofSeconds(60), true, true),
                Map.of(target, new WarzoneConfig.TargetPolicy(true, true, Duration.ofSeconds(60))),
                modifiers, Map.of());
    }
}
