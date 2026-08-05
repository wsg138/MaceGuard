package com.lincoln.maceguard.warzone.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneControlConfigLoaderTest {
    @TempDir Path directory;

    @Test void bundledSchemaSevenConfigurationIsValid() {
        ValidationResult<WarzoneControlConfig> result = loadDefault();
        assertTrue(result.valid(), result.errors().toString());
        assertEquals(7, result.value().version());
        assertEquals(List.of("cobwebs", "ender-pearl-cooldown-5", "wind-charge-cooldown-5"),
                result.value().kits().get("smp").modifierIds());
        assertEquals(4, result.value().schedule().cycle().size());
    }

    @Test void schemaSevenRequiresExplicitCombatStasisConfiguration() throws IOException {
        YamlConfiguration yaml = defaultYaml();
        yaml.set("combat", null);
        ValidationResult<WarzoneControlConfig> missingCombat = load(yaml.saveToString());
        assertFalse(missingCombat.valid());
        assertTrue(missingCombat.errors().stream().anyMatch(value ->
                value.contains("combat must be a section in schema 7")));

        yaml = defaultYaml();
        yaml.set("combat.stasis.minimum-age", null);
        ValidationResult<WarzoneControlConfig> missingAge = load(yaml.saveToString());
        assertFalse(missingAge.valid());
        assertTrue(missingAge.errors().stream().anyMatch(value ->
                value.contains("combat.stasis.minimum-age is required")));
    }

    @Test void enabledKitRejectsUnknownModifier() throws IOException {
        ValidationResult<WarzoneControlConfig> result = load(defaultText().replace(
                "      - wind-charge-cooldown-5", "      - missing-modifier"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("kits.smp")
                && value.contains("Unknown modifier 'missing-modifier'")));
    }

    @Test void enabledKitRejectsDisabledModifier() throws IOException {
        YamlConfiguration yaml = defaultYaml();
        yaml.set("modifiers.cobwebs.enabled", false);
        ValidationResult<WarzoneControlConfig> result = load(yaml.saveToString());
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("kits.smp")
                && value.contains("disabled")));
    }

    @Test void kitRejectsDuplicateModifierEvenWhenCompositionWouldDeduplicate() throws IOException {
        String text = defaultText().replace(
                "      - wind-charge-cooldown-5", "      - wind-charge-cooldown-5\n      - cobwebs");
        ValidationResult<WarzoneControlConfig> result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("kits.smp.modifiers")
                && value.contains("duplicate")));
    }

    @Test void kitRejectsConflictingMembers() throws IOException {
        String text = defaultText().replace(
                "      - wind-charge-cooldown-5", "      - mace-disabled\n      - mace-cooldown");
        ValidationResult<WarzoneControlConfig> result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("kits.smp")
                && (value.toLowerCase().contains("conflict") || value.toLowerCase().contains("contradictory"))), result.errors().toString());
    }

    @Test void scheduleRejectsDisabledKitReference() throws IOException {
        ValidationResult<WarzoneControlConfig> result = load(defaultText().replace(
                "  smp:\n    enabled: true", "  smp:\n    enabled: false"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("references disabled kit 'smp'")));
    }

    @Test void scheduleRejectsKitPlusModifiers() throws IOException {
        String text = defaultText().replace(
                "      - type: KIT\n        kit: smp",
                "      - type: KIT\n        kit: smp\n        modifiers: [cobwebs]");
        ValidationResult<WarzoneControlConfig> result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("cannot combine a kit with modifiers")));
    }

    @Test void invalidGuiMaterialFailsValidation() throws IOException {
        ValidationResult<WarzoneControlConfig> result = load(defaultText().replace(
                "    icon: GRASS_BLOCK", "    icon: DEFINITELY_NOT_A_MATERIAL"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("kits.smp.icon")
                && value.contains("valid Bukkit material")));
    }


    @Test void disabledScheduleMayUseAnEmptyCycle() throws IOException {
        String text = emptyCycle(defaultText())
                .replace("  schedule:\n    enabled: true", "  schedule:\n    enabled: false");
        ValidationResult<WarzoneControlConfig> result = load(text);
        assertTrue(result.valid(), result.errors().toString());
        assertFalse(result.value().schedule().enabled());
        assertTrue(result.value().schedule().cycle().isEmpty());
    }

    @Test void enabledScheduleRejectsAnEmptyCycle() throws IOException {
        String text = emptyCycle(defaultText());
        ValidationResult<WarzoneControlConfig> result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("cycle must not be empty")));
    }

    @Test void cadenceRejectsValuesOutsideThirtyTwoBitRange() throws IOException {
        ValidationResult<WarzoneControlConfig> result = load(defaultText().replace(
                "      every: 1", "      every: 4294967297"));
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains(
                "rotation.schedule.cadence.every must be a 32-bit integer")));
    }

    @Test void exactScheduleRejectsWholeSpearDisableCombinedWithLungeCooldown() throws IOException {
        String text = defaultText().replace(
                "      - type: MODIFIERS\n        modifiers:\n          - cobwebs\n          - no-lunge",
                "      - type: MODIFIERS\n        modifiers:\n          - spear-disabled\n          - lunge-cooldown-10");
        ValidationResult<WarzoneControlConfig> result = load(text);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(value -> value.contains("cycle")
                && (value.toLowerCase().contains("conflict") || value.toLowerCase().contains("contradictory"))), result.errors().toString());
    }

    private String emptyCycle(String text) {
        int start = text.indexOf("    cycle:\n");
        int end = text.indexOf("  selection:\n", start);
        assertTrue(start >= 0 && end > start, "Default schedule cycle must be present.");
        return text.substring(0, start) + "    cycle: []\n" + text.substring(end);
    }

    private YamlConfiguration defaultYaml() {
        return YamlConfiguration.loadConfiguration(
                Path.of("src", "main", "resources", "warzone.yml").toFile());
    }

    private ValidationResult<WarzoneControlConfig> loadDefault() {
        return new WarzoneControlConfigLoader().load(
                Path.of("src", "main", "resources", "warzone.yml"));
    }

    private String defaultText() throws IOException {
        return Files.readString(Path.of("src", "main", "resources", "warzone.yml"));
    }

    private ValidationResult<WarzoneControlConfig> load(String text) throws IOException {
        Path file = directory.resolve("warzone-" + System.nanoTime() + ".yml");
        Files.writeString(file, text);
        return new WarzoneControlConfigLoader().load(file);
    }
}
