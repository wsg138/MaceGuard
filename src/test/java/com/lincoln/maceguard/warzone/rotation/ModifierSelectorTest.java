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
    private static final String ELYTRA_ID = "elytra-no-rockets";
    private static final String MACE_ID = "MACE";
    private static final String PEARL_TARGET_ID = "ENDER_PEARL";
    private static final String WIND_TARGET_ID = "WIND_CHARGE";
    private static final String PEARL_DISABLED_ID = "ender-pearl-disabled";
    private static final String PEARL_COOLDOWN_FIVE_ID = "ender-pearl-cooldown-5";
    private static final String PEARL_COOLDOWN_TEN_ID = "ender-pearl-cooldown-10";
    private static final String WIND_DISABLED_ID = "wind-charge-disabled";
    private static final String WIND_COOLDOWN_FIVE_ID = "wind-charge-cooldown-5";
    private static final String WIND_COOLDOWN_TEN_ID = "wind-charge-cooldown-10";
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
                value.contains(PEARL_DISABLED_ID)
                        && value.contains(PEARL_COOLDOWN_FIVE_ID)));
        assertTrue(combinations.stream().noneMatch(value ->
                value.contains(PEARL_COOLDOWN_FIVE_ID)
                        && value.contains(PEARL_COOLDOWN_TEN_ID)));
        assertTrue(combinations.stream().noneMatch(value ->
                value.contains(WIND_DISABLED_ID)
                        && value.contains(WIND_COOLDOWN_TEN_ID)));
    }

    @Test void disabledModifiersAreNeverSelectedOrManuallyComposed() {
        WarzoneConfig config = withModifier(config(1, 3), PEARL_DISABLED_ID,
                modifier(PEARL_DISABLED_ID, false, 3, Set.of(), Map.of(
                        target(PEARL_TARGET_ID), restriction(PEARL_TARGET_ID,
                                RestrictionMode.DISABLED, null))));
        ModifierSelector selector = new ModifierSelector(new java.util.Random(2L));
        for (int index = 0; index < 200; index++)
            assertFalse(selector.select(config, Set.of()).modifierIds()
                    .contains(PEARL_DISABLED_ID));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> selector.compose(config, List.of(PEARL_DISABLED_ID)));
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
                    .contains(ELYTRA_ID));
    }

    @Test void inclusionChanceHundredSelectsElytraWhenPossible() {
        WarzoneConfig config = withElytraRule(config(1, 3), 100, 0);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(3L));
        for (int index = 0; index < 50; index++)
            assertTrue(selector.select(config, Set.of()).modifierIds()
                    .contains(ELYTRA_ID));
    }

    @Test void disabledElytraOverridesHundredPercentInclusion() {
        WarzoneConfig base = withElytraRule(config(1, 3), 100, 0);
        WarzoneConfig config = withModifier(base, ELYTRA_ID,
                modifier(ELYTRA_ID, false, 1,
                        Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of()));
        ModifierSelector selector = new ModifierSelector(new java.util.Random(3L));
        for (int index = 0; index < 50; index++)
            assertFalse(selector.select(config, Set.of()).modifierIds()
                    .contains(ELYTRA_ID));
    }

    @Test void partialInclusionRejectsAnOnlyElytraConfiguration() {
        WarzoneConfig base = withElytraRule(config(1, 1), 8, 0);
        WarzoneConfig onlyElytra = withModifiers(base, Map.of(
                ELYTRA_ID, base.modifiers().get(ELYTRA_ID)));

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
                ELYTRA_ID, base.modifiers().get(ELYTRA_ID)));
        WarzoneConfig conflicted = withConflictGroups(limited, Map.of(
                "elytra-cobweb", Set.of(ELYTRA_ID, "cobwebs"),
                "elytra-lunge", Set.of(ELYTRA_ID, "no-lunge")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ModifierSelector(new java.util.Random(1L))
                        .selectableCombinations(conflicted));
        assertTrue(failure.getMessage().contains("Elytra combination"));
    }

    @Test void everyNonTerminalInclusionPercentageHasBothFeasibleBranches() {
        for (int chance : List.of(1, 8, 50, 99)) {
            WarzoneConfig config = withElytraRule(config(1, 3), chance, 90);
            List<List<String>> combinations = selectableForChance(config, chance);
            assertTrue(combinations.stream().anyMatch(value ->
                    value.contains(ELYTRA_ID)), "chance=" + chance);
            assertTrue(combinations.stream().anyMatch(value ->
                    !value.contains(ELYTRA_ID)), "chance=" + chance);
        }
    }

    @Test void unrestrictedMaceChanceRecognizesCustomRestrictionIds() {
        WarzoneConfig base = withElytraRule(config(2, 2), 100, 90);
        WarzoneConfig.Modifier customMace = modifier("custom-mace-rule", true, 10,
                Set.of(), Map.of(target(MACE_ID),
                        restriction(MACE_ID, RestrictionMode.DISABLED, null)));
        WarzoneConfig onlyRestrictedElytra = withModifiers(base, Map.of(
                ELYTRA_ID, base.modifiers().get(ELYTRA_ID),
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
            assertTrue(ids.contains(ELYTRA_ID));
            assertFalse(ids.contains("mace-disabled"));
            assertFalse(ids.contains("mace-cooldown"));
        }
        assertThrows(IllegalArgumentException.class, () ->
                selector.compose(config, List.of(ELYTRA_ID, "mace-cooldown")));
    }

    @Test void defaultFixedSeedSamplingTracksConfiguredElytraPercentages() {
        WarzoneConfig config = withElytraRule(config(1, 3), 8, 90);
        ModifierSelector selector = new ModifierSelector(new java.util.Random(912_044L));
        int elytra = 0;
        int unrestricted = 0;
        int samples = 10_000;
        for (int index = 0; index < samples; index++) {
            List<String> ids = selector.select(config, Set.of()).modifierIds();
            if (!ids.contains(ELYTRA_ID)) continue;
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
                        List.of(PEARL_COOLDOWN_FIVE_ID, PEARL_COOLDOWN_TEN_ID)));
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

    private List<List<String>> selectableForChance(WarzoneConfig config,
                                                    int chance) {
        return new ModifierSelector(new java.util.Random(chance))
                .selectableCombinations(config);
    }

    static WarzoneConfig config(int minimum, int maximum) {
        RestrictionTarget mace = target(MACE_ID);
        RestrictionTarget pearl = target(PEARL_TARGET_ID);
        RestrictionTarget wind = target(WIND_TARGET_ID);
        // Immutable, method-local fixture; no concurrent access is possible.
        @SuppressWarnings("PMD.UseConcurrentHashMap")
        Map<String, WarzoneConfig.Modifier> modifiers = Map.ofEntries(
                Map.entry("cobwebs", modifier("cobwebs", true, 10,
                        Set.of(WarzoneConfig.Effect.COBWEBS), Map.of())),
                Map.entry("no-lunge", modifier("no-lunge", true, 8, Set.of(), Map.of(
                        RestrictionTarget.SPEAR_LUNGE,
                        new WarzoneConfig.Restriction(RestrictionTarget.SPEAR_LUNGE,
                                RestrictionMode.DISABLED, null)))),
                Map.entry("mace-disabled", modifier("mace-disabled", true, 4, Set.of(), Map.of(
                        mace, restriction(MACE_ID, RestrictionMode.DISABLED, null)))),
                Map.entry("mace-cooldown", modifier("mace-cooldown", true, 8, Set.of(), Map.of(
                        mace, restriction(MACE_ID, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10))))),
                Map.entry(PEARL_DISABLED_ID, modifier(PEARL_DISABLED_ID, true, 3, Set.of(), Map.of(
                        pearl, restriction(PEARL_TARGET_ID, RestrictionMode.DISABLED, null)))),
                Map.entry(PEARL_COOLDOWN_FIVE_ID, modifier(PEARL_COOLDOWN_FIVE_ID, true, 9, Set.of(), Map.of(
                        pearl, restriction(PEARL_TARGET_ID, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(5))))),
                Map.entry(PEARL_COOLDOWN_TEN_ID, modifier(PEARL_COOLDOWN_TEN_ID, true, 6, Set.of(), Map.of(
                        pearl, restriction(PEARL_TARGET_ID, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10))))),
                Map.entry(WIND_DISABLED_ID, modifier(WIND_DISABLED_ID, true, 3, Set.of(), Map.of(
                        wind, restriction(WIND_TARGET_ID, RestrictionMode.DISABLED, null)))),
                Map.entry(WIND_COOLDOWN_FIVE_ID, modifier(WIND_COOLDOWN_FIVE_ID, true, 9, Set.of(), Map.of(
                        wind, restriction(WIND_TARGET_ID, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(5))))),
                Map.entry(WIND_COOLDOWN_TEN_ID, modifier(WIND_COOLDOWN_TEN_ID, true, 6, Set.of(), Map.of(
                        wind, restriction(WIND_TARGET_ID, RestrictionMode.COOLDOWN,
                                Duration.ofSeconds(10))))),
                Map.entry(ELYTRA_ID, modifier(ELYTRA_ID, true, 1,
                        Set.of(WarzoneConfig.Effect.ELYTRA_NO_ROCKETS), Map.of()))
        );
        // Method-local fixture whose insertion order mirrors the configured range.
        @SuppressWarnings("PMD.UseConcurrentHashMap")
        Map<Integer, Integer> countWeights = new LinkedHashMap<>();
        for (int count = minimum; count <= maximum; count++) countWeights.put(count, 1);
        return new WarzoneConfig(5, true,
                new WarzoneConfig.Region("world", "warzone", List.of("spawn", "market")),
                new WarzoneConfig.Schedule(DayOfWeek.SUNDAY, LocalTime.of(4, 0),
                        ZoneId.of("America/Indiana/Indianapolis")),
                new WarzoneConfig.Selection(WarzoneConfig.Selection.Mode.WEIGHTED_RANDOM_MODIFIERS,
                        minimum, maximum, true, countWeights),
                Map.of(ELYTRA_ID, new WarzoneConfig.SpecialRule(8, 90)),
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
                        "ender-pearl-mode", Set.of(PEARL_DISABLED_ID,
                                PEARL_COOLDOWN_FIVE_ID, PEARL_COOLDOWN_TEN_ID),
                        "wind-charge-mode", Set.of(WIND_DISABLED_ID,
                                WIND_COOLDOWN_FIVE_ID, WIND_COOLDOWN_TEN_ID)));
    }

    static WarzoneConfig withElytraRule(WarzoneConfig base, int inclusion, int unrestrictedMace) {
        return withSpecialRules(base, Map.of(ELYTRA_ID,
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
        // Method-local fixture copy; no concurrent access is possible.
        @SuppressWarnings("PMD.UseConcurrentHashMap")
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
