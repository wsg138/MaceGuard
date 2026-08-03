package com.lincoln.maceguard.warzone.rotation;

import com.lincoln.maceguard.warzone.config.WarzoneConfig;
import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
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
        assertTrue(combinations.stream().noneMatch(value ->
                value.contains("ender-pearl-disabled")
                        && value.contains("ender-pearl-cooldown-5")));
        assertTrue(combinations.stream().noneMatch(value ->
                value.contains("ender-pearl-cooldown-5")
                        && value.contains("ender-pearl-cooldown-10")));
        assertTrue(combinations.stream().noneMatch(value ->
                value.contains("wind-charge-disabled")
                        && value.contains("wind-charge-cooldown-10")));
    }

    @Test void disabledModifiersAreNeverSelectedOrManuallyComposed() {
        WarzoneConfig config = withModifier(config(1, 3), "ender-pearl-disabled",
                modifier("ender-pearl-disabled", false, 3, Set.of(), Map.of(
                        target("ENDER_PEARL"), restriction("ENDER_PEARL",
                                RestrictionMode.DISABLED, null))));
        ModifierSelector selector = new ModifierSelector(new java.util.Random(2L));
        for (int index = 0; index < 200; index++)
            assertFalse(selector.select(config, Set.of()).modifierIds()
                    .contains("ender-pearl-disabled"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> selector.compose(config, List.of("ender-pearl-disabled")));
        assertTrue(failure.getMessage().contains("disabled"));
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

    @Test void countWeightsChooseOnlyFeasibleConfiguredCounts() {
        WarzoneConfig base = config(1, 3);
        WarzoneConfig.Selection selection = new WarzoneConfig.Selection(
                WarzoneConfig.Selection.Mode.WEIGHTED_RANDOM_MODIFIERS,
                1, 3, false, Map.of(1, 1, 3, 999));
        WarzoneConfig onlyConflictingModes = new WarzoneConfig(base.version(), base.enabled(),
                base.region(), base.schedule(), selection, Map.of(), base.warningTimes(),
                base.messages(), base.cobwebs(), base.targetPolicies(),
                Map.of("mace-disabled", base.modifiers().get("mace-disabled"),
                        "mace-cooldown", base.modifiers().get("mace-cooldown")),
                base.conflictGroups());
        var selected = new ModifierSelector(new java.util.Random(5L))
                .select(onlyConflictingModes, Set.of());
        assertEquals(1, selected.modifierIds().size());
    }

    @Test void inclusionChanceZeroNeverSelectsElytra() {
        WarzoneConfig config = withElytraRule(config(1, 3), 0, 90);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(3L));
        for (int index = 0; index < 200; index++)
            assertFalse(selector.select(config, Set.of()).modifierIds()
                    .contains("elytra-no-rockets"));
    }

    @Test void inclusionChanceHundredSelectsElytraWhenPossible() {
        WarzoneConfig config = withElytraRule(config(1, 3), 100, 0);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(3L));
        for (int index = 0; index < 50; index++)
            assertTrue(selector.select(config, Set.of()).modifierIds()
                    .contains("elytra-no-rockets"));
    }

    @Test void disabledElytraOverridesHundredPercentInclusion() {
        WarzoneConfig base = withElytraRule(config(1, 3), 100, 0);
        WarzoneConfig config = withModifier(base, "elytra-no-rockets",
                modifier("elytra-no-rockets", false, 1,
                        Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of()));
        ModifierSelector selector = new ModifierSelector(new java.util.Random(3L));
        for (int index = 0; index < 50; index++)
            assertFalse(selector.select(config, Set.of()).modifierIds()
                    .contains("elytra-no-rockets"));
    }

    @Test void partialInclusionRejectsAnOnlyElytraConfiguration() {
        WarzoneConfig base = withElytraRule(config(1, 1), 8, 0);
        WarzoneConfig onlyElytra = withModifiers(base, Map.of(
                "elytra-no-rockets", base.modifiers().get("elytra-no-rockets")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ModifierSelector(new java.util.Random(1L))
                        .selectableCombinations(onlyElytra));
        assertTrue(failure.getMessage().contains("non-Elytra"));
    }

    @Test void partialInclusionRejectsWhenNoElytraCapableCombinationExists() {
        WarzoneConfig base = withElytraRule(config(2, 2), 8, 0);
        WarzoneConfig limited = withModifiers(base, Map.of(
                "cobwebs", base.modifiers().get("cobwebs"),
                "no-lunge", base.modifiers().get("no-lunge"),
                "elytra-no-rockets", base.modifiers().get("elytra-no-rockets")));
        limited = withConflictGroups(limited, Map.of(
                "elytra-cobweb", Set.of("elytra-no-rockets", "cobwebs"),
                "elytra-lunge", Set.of("elytra-no-rockets", "no-lunge")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ModifierSelector(new java.util.Random(1L))
                        .selectableCombinations(limited));
        assertTrue(failure.getMessage().contains("Elytra combination"));
    }

    @Test void everyNonTerminalInclusionPercentageHasBothFeasibleBranches() {
        for (int chance : List.of(1, 8, 50, 99)) {
            WarzoneConfig config = withElytraRule(config(1, 3), chance, 90);
            List<List<String>> combinations =
                    new ModifierSelector(new java.util.Random(chance))
                            .selectableCombinations(config);
            assertTrue(combinations.stream().anyMatch(value ->
                    value.contains("elytra-no-rockets")), "chance=" + chance);
            assertTrue(combinations.stream().anyMatch(value ->
                    !value.contains("elytra-no-rockets")), "chance=" + chance);
        }
    }

    @Test void unrestrictedMaceChanceRecognizesCustomRestrictionIds() {
        WarzoneConfig base = withElytraRule(config(2, 2), 100, 90);
        WarzoneConfig.Modifier customMace = modifier("custom-mace-rule", true, 10,
                Set.of(), Map.of(target("MACE"),
                        restriction("MACE", RestrictionMode.DISABLED, null)));
        WarzoneConfig onlyRestrictedElytra = withModifiers(base, Map.of(
                "elytra-no-rockets", base.modifiers().get("elytra-no-rockets"),
                "custom-mace-rule", customMace));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ModifierSelector(new java.util.Random(1L))
                        .selectableCombinations(onlyRestrictedElytra));
        assertTrue(failure.getMessage().contains("unrestricted"));
    }

    @Test void specialRulesRejectRuntimeIgnoredModifierIds() {
        WarzoneConfig base = config(1, 3);
        WarzoneConfig unsupported = withSpecialRules(base, Map.of(
                "cobwebs", new WarzoneConfig.SpecialRule(5, 0)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ModifierSelector(new java.util.Random(1L))
                        .selectableCombinations(unsupported));
        assertTrue(failure.getMessage().contains("supports only"));
    }

    @Test void unrestrictedMaceHundredExcludesBothMaceModesWithElytra() {
        WarzoneConfig config = withElytraRule(config(2, 3), 100, 100);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(8L));
        for (int index = 0; index < 100; index++) {
            List<String> ids = selector.select(config, Set.of()).modifierIds();
            assertTrue(ids.contains("elytra-no-rockets"));
            assertFalse(ids.contains("mace-disabled"));
            assertFalse(ids.contains("mace-cooldown"));
        }
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config, List.of("elytra-no-rockets", "mace-cooldown")));
    }

    @Test void defaultFixedSeedSamplingTracksConfiguredElytraPercentages() {
        WarzoneConfig config = withElytraRule(config(1, 3), 8, 90);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(912_044L));
        int elytra = 0;
        int unrestricted = 0;
        int samples = 10_000;
        for (int index = 0; index < samples; index++) {
            List<String> ids = selector.select(config, Set.of()).modifierIds();
            if (!ids.contains("elytra-no-rockets")) continue;
            elytra++;
            if (!ids.contains("mace-disabled") && !ids.contains("mace-cooldown"))
                unrestricted++;
        }
        assertTrue(elytra >= 700 && elytra <= 900, "elytra=" + elytra);
        double unrestrictedRate = unrestricted / (double) elytra;
        assertTrue(unrestrictedRate >= 0.90,
                "unrestrictedRate=" + unrestrictedRate);
    }

    @Test void rejectsConflictingManualSet() {
        WarzoneConfig config = config(1, 3);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1L));
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config, List.of("mace-disabled", "mace-cooldown")));
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config,
                        List.of("ender-pearl-cooldown-5", "ender-pearl-cooldown-10")));
    }

    @Test void manualCompositionEnforcesConfiguredMinimumAndMaximum() {
        ModifierSelector selector = new ModifierSelector(new java.util.Random(1L));
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config(2, 3), List.of("cobwebs")));
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config(1, 2),
                        List.of("cobwebs", "no-lunge", "mace-disabled")));
        assertDoesNotThrow(() -> selector.compose(config(2, 3),
                List.of("cobwebs", "no-lunge")));
        assertDoesNotThrow(() -> selector.compose(config(1, 2),
                List.of("cobwebs", "no-lunge")));
    }

    @Test void excessiveConfiguredModifierCountFailsBeforeEnumeration() {
        WarzoneConfig base = config(1, 3);
        Map<String, WarzoneConfig.Modifier> modifiers = new LinkedHashMap<>();
        for (int index = 0; index <= ModifierSelector.MAX_CONFIGURED_MODIFIERS; index++) {
            String id = "modifier-" + index;
            modifiers.put(id, modifier(id, true, 1,
                    Set.of(WarzoneConfig.Effect.COBWEBS), Map.of()));
        }
        WarzoneConfig excessive = new WarzoneConfig(base.version(), base.enabled(),
                base.region(), base.schedule(), base.selection(), Map.of(), base.warningTimes(),
                base.messages(), base.cobwebs(), base.targetPolicies(), modifiers, Map.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ModifierSelector(new java.util.Random(1L))
                        .validCombinations(excessive));
        assertTrue(failure.getMessage().contains("at most"));
    }

    static WarzoneConfig config(int minimum, int maximum) {
        RestrictionTarget mace = target("MACE");
        RestrictionTarget pearl = target("ENDER_PEARL");
        RestrictionTarget wind = target("WIND_CHARGE");
        Map<String, WarzoneConfig.Modifier> modifiers = Map.ofEntries(
                Map.entry("cobwebs", modifier("cobwebs", true, 10,
                        Set.of(WarzoneConfig.Effect.COBWEBS), Map.of())),
                Map.entry("no-lunge", modifier("no-lunge", true, 8, Set.of(), Map.of(
                        RestrictionTarget.SPEAR_LUNGE,
                        new WarzoneConfig.Restriction(RestrictionTarget.SPEAR_LUNGE,
                                RestrictionMode.DISABLED, null)))),
                Map.entry("mace-disabled", modifier("mace-disabled", true, 4, Set.of(), Map.of(
                        mace, restriction("MACE", RestrictionMode.DISABLED, null)))),
                Map.entry("mace-cooldown", modifier("mace-cooldown", true, 8, Set.of(), Map.of(
                        mace, restriction("MACE", RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10))))),
                Map.entry("ender-pearl-disabled", modifier("ender-pearl-disabled", true, 3, Set.of(), Map.of(
                        pearl, restriction("ENDER_PEARL", RestrictionMode.DISABLED, null)))),
                Map.entry("ender-pearl-cooldown-5", modifier("ender-pearl-cooldown-5", true, 9, Set.of(), Map.of(
                        pearl, restriction("ENDER_PEARL", RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(5))))),
                Map.entry("ender-pearl-cooldown-10", modifier("ender-pearl-cooldown-10", true, 6, Set.of(), Map.of(
                        pearl, restriction("ENDER_PEARL", RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10))))),
                Map.entry("wind-charge-disabled", modifier("wind-charge-disabled", true, 3, Set.of(), Map.of(
                        wind, restriction("WIND_CHARGE", RestrictionMode.DISABLED, null)))),
                Map.entry("wind-charge-cooldown-5", modifier("wind-charge-cooldown-5", true, 9, Set.of(), Map.of(
                        wind, restriction("WIND_CHARGE", RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(5))))),
                Map.entry("wind-charge-cooldown-10", modifier("wind-charge-cooldown-10", true, 6, Set.of(), Map.of(
                        wind, restriction("WIND_CHARGE", RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10))))),
                Map.entry("elytra-no-rockets", modifier("elytra-no-rockets", true, 1,
                        Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of()))
        );
        Map<Integer, Integer> countWeights = new LinkedHashMap<>();
        for (int count = minimum; count <= maximum; count++) countWeights.put(count, 1);
        return new WarzoneConfig(5, true,
                new WarzoneConfig.Region("world", "warzone", List.of("spawn", "market")),
                new WarzoneConfig.Schedule(DayOfWeek.SUNDAY, LocalTime.of(4, 0),
                        ZoneId.of("America/Indiana/Indianapolis")),
                new WarzoneConfig.Selection(WarzoneConfig.Selection.Mode.WEIGHTED_RANDOM_MODIFIERS,
                        minimum, maximum, true, countWeights),
                Map.of("elytra-no-rockets", new WarzoneConfig.SpecialRule(8, 90)),
                List.of(Duration.ofMinutes(10)),
                new WarzoneConfig.Messages(Duration.ofSeconds(2),
                        WarzoneConfig.Audience.GLOBAL, WarzoneConfig.Audience.GLOBAL),
                new WarzoneConfig.Cobwebs(Duration.ofSeconds(60), true, true),
                Map.of(mace, new WarzoneConfig.TargetPolicy(true, true, Duration.ofSeconds(60)),
                        pearl, new WarzoneConfig.TargetPolicy(true, true, Duration.ofSeconds(60)),
                        wind, new WarzoneConfig.TargetPolicy(true, true, Duration.ofSeconds(60)),
                        RestrictionTarget.SPEAR_LUNGE,
                        new WarzoneConfig.TargetPolicy(true, false, null)),
                modifiers,
                Map.of("mace-mode", Set.of("mace-disabled", "mace-cooldown"),
                        "ender-pearl-mode", Set.of("ender-pearl-disabled",
                                "ender-pearl-cooldown-5", "ender-pearl-cooldown-10"),
                        "wind-charge-mode", Set.of("wind-charge-disabled",
                                "wind-charge-cooldown-5", "wind-charge-cooldown-10")));
    }

    static WarzoneConfig withElytraRule(WarzoneConfig base, int inclusion, int unrestrictedMace) {
        return withSpecialRules(base, Map.of("elytra-no-rockets",
                new WarzoneConfig.SpecialRule(inclusion, unrestrictedMace)));
    }

    static WarzoneConfig withSpecialRules(WarzoneConfig base,
                                           Map<String, WarzoneConfig.SpecialRule> specialRules) {
        return new WarzoneConfig(base.version(), base.enabled(), base.region(), base.schedule(),
                base.selection(), specialRules, base.warningTimes(), base.messages(),
                base.cobwebs(), base.targetPolicies(), base.modifiers(), base.conflictGroups());
    }

    static WarzoneConfig withModifier(WarzoneConfig base, String id,
                                       WarzoneConfig.Modifier modifier) {
        Map<String, WarzoneConfig.Modifier> modifiers = new LinkedHashMap<>(base.modifiers());
        modifiers.put(id, modifier);
        return withModifiers(base, modifiers);
    }

    static WarzoneConfig withModifiers(WarzoneConfig base,
                                        Map<String, WarzoneConfig.Modifier> modifiers) {
        return new WarzoneConfig(base.version(), base.enabled(), base.region(), base.schedule(),
                base.selection(), base.specialRules(), base.warningTimes(), base.messages(),
                base.cobwebs(), base.targetPolicies(), modifiers, base.conflictGroups());
    }

    static WarzoneConfig withConflictGroups(WarzoneConfig base,
                                             Map<String, Set<String>> conflictGroups) {
        return new WarzoneConfig(base.version(), base.enabled(), base.region(), base.schedule(),
                base.selection(), base.specialRules(), base.warningTimes(), base.messages(),
                base.cobwebs(), base.targetPolicies(), base.modifiers(), conflictGroups);
    }

    static WarzoneConfig.Modifier modifier(
            String id, boolean enabled, int weight, Set<WarzoneConfig.Effect> effects,
            Map<RestrictionTarget, WarzoneConfig.Restriction> restrictions) {
        return new WarzoneConfig.Modifier(id, enabled, weight, id, id, effects, restrictions,
                "", "", "");
    }

    private static WarzoneConfig.Restriction restriction(
            String id, RestrictionMode mode, Duration cooldown) {
        RestrictionTarget target = target(id);
        return new WarzoneConfig.Restriction(target, mode, cooldown);
    }

    private static RestrictionTarget target(String id) {
        return RestrictionTarget.parse(id).orElseThrow();
    }
}
