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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModifierSelectorTest {
    @Test void combinationsHonorMinimumMaximumAndConflictGroups() {
        WarzoneConfig config = config(1, 3);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(4L));
        var combinations = selector.validCombinations(config);
        assertFalse(combinations.isEmpty());
        assertTrue(combinations.stream().allMatch(value ->
                value.size() >= 1 && value.size() <= 3));
        assertTrue(combinations.stream().noneMatch(value ->
                value.contains("mace-disabled") && value.contains("mace-cooldown")));
    }

    @Test void preventsImmediateIdenticalRepeatWhenAlternativeExists() {
        WarzoneConfig config = config(1, 1);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1L));
        var selection = selector.select(config, Set.of("cobwebs"));
        assertNotEquals(List.of("cobwebs"), selection.modifierIds());
    }

    @Test void composesMultipleCompatibleRestrictionsAndEffects() {
        WarzoneConfig config = config(1, 3);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1L));
        var active = selector.compose(config,
                List.of("cobwebs", "no-lunge", "mace-disabled"));
        assertTrue(active.cobwebsAllowed());
        assertEquals(2, active.restrictions().size());
        assertTrue(active.restrictions().containsKey(RestrictionTarget.SPEAR_LUNGE));
    }

    @Test void rejectsConflictingManualSet() {
        WarzoneConfig config = config(1, 3);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1L));
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config, List.of("mace-disabled", "mace-cooldown")));
    }

    static WarzoneConfig config(int minimum, int maximum) {
        RestrictionTarget mace = RestrictionTarget.parse("MACE").orElseThrow();
        Map<String, WarzoneConfig.Modifier> modifiers = Map.of(
                "cobwebs", modifier("cobwebs", Set.of(WarzoneConfig.Effect.COBWEBS), Map.of()),
                "no-lunge", modifier("no-lunge", Set.of(), Map.of(
                        RestrictionTarget.SPEAR_LUNGE,
                        new WarzoneConfig.Restriction(RestrictionTarget.SPEAR_LUNGE,
                                RestrictionMode.DISABLED, null))),
                "mace-disabled", modifier("mace-disabled", Set.of(), Map.of(
                        mace, new WarzoneConfig.Restriction(mace,
                                RestrictionMode.DISABLED, null))),
                "mace-cooldown", modifier("mace-cooldown", Set.of(), Map.of(
                        mace, new WarzoneConfig.Restriction(mace,
                                RestrictionMode.COOLDOWN, Duration.ofSeconds(10)))),
                "elytra-no-rockets", modifier("elytra-no-rockets",
                        Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of())
        );
        return new WarzoneConfig(4, true,
                new WarzoneConfig.Region("world", "warzone", List.of("spawn", "market")),
                new WarzoneConfig.Schedule(DayOfWeek.SUNDAY, LocalTime.of(4, 0),
                        ZoneId.of("America/Indiana/Indianapolis")),
                new WarzoneConfig.Selection(WarzoneConfig.Selection.Mode.RANDOM_MODIFIERS,
                        minimum, maximum, true),
                List.of(Duration.ofMinutes(10)),
                new WarzoneConfig.Messages(Duration.ofSeconds(2),
                        WarzoneConfig.Audience.GLOBAL, WarzoneConfig.Audience.GLOBAL),
                new WarzoneConfig.Cobwebs(Duration.ofSeconds(60), true, true),
                Map.of(mace, new WarzoneConfig.TargetPolicy(true, true,
                        Duration.ofSeconds(60))),
                modifiers,
                Map.of("mace-mode", Set.of("mace-disabled", "mace-cooldown")));
    }

    private static WarzoneConfig.Modifier modifier(String id,
                                                    Set<WarzoneConfig.Effect> effects,
                                                    Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions) {
        return new WarzoneConfig.Modifier(id, id, id, effects, restrictions,
                "", "", "");
    }
}
