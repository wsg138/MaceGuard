package com.lincoln.maceguard.warzone.config;

import com.lincoln.maceguard.warzone.restriction.RestrictionMode;
import com.lincoln.maceguard.warzone.restriction.RestrictionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneConfigLoaderTest {
    @TempDir Path directory;

    @Test void bundledDefaultConfigurationIsValid() {
        var result = loadDefault();
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(5, result.value().version());
        assertEquals(Set.of("spawn", "market"),
                Set.copyOf(result.value().region().excludedRegionIds()));
        assertEquals(35, result.value().selection().countWeights().get(1));
        assertEquals(8, result.value().specialRules().get("elytra-no-rockets")
                .weeklyInclusionChancePercent());
    }

    @Test void parsesNewRestrictionTargetsAndCooldownModifiers() {
        var result = loadDefault();
        assertTrue(result.valid(), result.errors().toString());
        assertCooldown(result.value(), "mace-cooldown", "MACE", 10);
        assertCooldown(result.value(), "ender-pearl-cooldown-5", "ENDER_PEARL", 5);
        assertCooldown(result.value(), "ender-pearl-cooldown-10", "ENDER_PEARL", 10);
        assertCooldown(result.value(), "wind-charge-cooldown-5", "WIND_CHARGE", 5);
        assertCooldown(result.value(), "wind-charge-cooldown-10", "WIND_CHARGE", 10);
    }

    @Test void enabledModifierRequiresPositiveWeight() throws IOException {
        String text = defaultText().replace(
                "  ender-pearl-disabled:\n    enabled: true\n    weight: 3",
                "  ender-pearl-disabled:\n    enabled: true\n    weight: 0");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("modifiers.ender-pearl-disabled.weight")
                        && value.contains("positive")));
    }

    @Test void disabledModifierMayRetainNonPositiveLegacyWeight() throws IOException {
        String text = defaultText().replace(
                "  ender-pearl-disabled:\n    enabled: true\n    weight: 3",
                "  ender-pearl-disabled:\n    enabled: false\n    weight: 0");
        var result = load(text);
        assertTrue(result.valid(), result.errors().toString());
        assertFalse(result.value().modifiers().get("ender-pearl-disabled").enabled());
    }

    @Test void disablingEveryPearlOutcomeDoesNotBreakOtherSelection() throws IOException {
        String text = defaultText()
                .replace("  ender-pearl-disabled:\n    enabled: true",
                        "  ender-pearl-disabled:\n    enabled: false")
                .replace("  ender-pearl-cooldown-5:\n    enabled: true",
                        "  ender-pearl-cooldown-5:\n    enabled: false")
                .replace("  ender-pearl-cooldown-10:\n    enabled: true",
                        "  ender-pearl-cooldown-10:\n    enabled: false");
        var result = load(text);
        assertTrue(result.valid(), result.errors().toString());
    }

    @Test void rejectsPartialElytraChanceWithoutANonElytraBranch()
            throws IOException {
        String text = defaultText()
                .replace("    enabled: true", "    enabled: false")
                .replace("  elytra-no-rockets:\n    enabled: false",
                        "  elytra-no-rockets:\n    enabled: true");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("non-Elytra combination")));
    }

    @Test void rejectsSpecialRulesForRuntimeIgnoredModifierIds()
            throws IOException {
        String text = defaultText().replace(
                "  special-rules:\n    elytra-no-rockets:",
                "  special-rules:\n"
                        + "    cobwebs:\n"
                        + "      weekly-inclusion-chance-percent: 5\n"
                        + "      unrestricted-mace-chance-percent: 0\n"
                        + "    elytra-no-rockets:");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("supports only 'elytra-no-rockets'")));
    }

    @Test void rejectsInvalidPercentages() throws IOException {
        String text = defaultText().replace(
                "weekly-inclusion-chance-percent: 8",
                "weekly-inclusion-chance-percent: 101");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("weekly-inclusion-chance-percent")
                        && value.contains("0 through 100")));
    }

    @Test void rejectsInvalidCountWeights() throws IOException {
        String text = defaultText().replace("      2: 45", "      2: 0");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("count-weights.2") && value.contains("positive")));
    }

    @Test void rejectsUnknownSpecialRuleModifier() throws IOException {
        String text = defaultText().replace(
                "    elytra-no-rockets:\n      weekly-inclusion",
                "    missing-modifier:\n      weekly-inclusion");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("unknown modifier 'missing-modifier'")));
    }

    @Test void rejectsAllModifiersDisabled() throws IOException {
        String text = defaultText().replace("    enabled: true", "    enabled: false");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("All modifiers are disabled")));
    }

    @Test void rejectsSequentialSchemaInsteadOfSilentlyReinterpretingIt() throws IOException {
        Path file = directory.resolve("old.yml");
        Files.writeString(file, """
                config-version: 3
                enabled: true
                region: {world: world, id: warzone}
                rotation: {warning-times: [10m]}
                messages: {}
                cobwebs: {}
                restriction-targets: {}
                rotations: {}
                """);
        var result = new WarzoneConfigLoader().load(file);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("config-version must be 5")));
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("rotations is not supported")));
    }

    @Test void rejectsUnknownConflictReference() throws IOException {
        String text = defaultText()
                .replace("    - mace-cooldown\n", "    - missing-modifier\n");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.contains("unknown modifier 'missing-modifier'")));
    }

    @Test void strictLoaderRejectsDuplicateKeys() throws IOException {
        String text = defaultText()
                .replace("    maximum: 3", "    maximum: 3\n    maximum: 2");
        var result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value ->
                value.toLowerCase().contains("duplicate")));
    }

    private ValidationResult<WarzoneConfig> loadDefault() {
        return new WarzoneConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
    }

    private String defaultText() throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "warzone.yml"));
    }

    private ValidationResult<WarzoneConfig> load(String text) throws IOException {
        Path file = directory.resolve("test-" + System.nanoTime() + ".yml");
        Files.writeString(file, text);
        return new WarzoneConfigLoader().load(file);
    }

    private void assertCooldown(WarzoneConfig config, String modifierId,
                                String targetId, long seconds) {
        var restriction = config.modifiers().get(modifierId)
                .restrictions().get(RestrictionTarget.parse(targetId).orElseThrow());
        assertEquals(RestrictionMode.COOLDOWN, restriction.mode());
        assertEquals(seconds, restriction.cooldown().getSeconds());
    }
}
