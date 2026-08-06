package com.lincoln.maceguard.warzone.runtime;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyCarryoverMigrationTest {
    private static final int SCHEMA_FOUR = 4;
    private static final String BUILT_IN_CARRYOVER =
            "modifiers.mace-disabled.combat-carryover";
    private static final String CUSTOM_CARRYOVER =
            "modifiers.custom-rule.combat-carryover";
    private static final String CUSTOM_ENABLED = "modifiers.custom-rule.enabled";
    private static final String CUSTOM_WEIGHT = "modifiers.custom-rule.weight";
    private static final String CUSTOM_DISPLAY_NAME = "modifiers.custom-rule.display-name";
    private static final String CUSTOM_DESCRIPTION = "modifiers.custom-rule.description";
    private static final String CUSTOM_RESTRICTION_MODE =
            "modifiers.custom-rule.restrictions.MACE.mode";
    private static final String CUSTOM_START_MESSAGE = "modifiers.custom-rule.start-message";
    private static final String CUSTOM_NAME = "Custom Name";
    private static final String CUSTOM_DESCRIPTION_TEXT = "Custom Description";
    private static final String CUSTOM_START = "Custom Start";
    private static final String DISABLED_MODE = "DISABLED";
    private static final String SCHEMA_FOUR_YAML = """
            config-version: 4
            enabled: true
            region:
              world: world
              id: warzone
              excluded-region-ids: [spawn, market]
            rotation:
              schedule:
                day: SUNDAY
                time: "04:00"
                timezone: UTC
              selection:
                mode: RANDOM_MODIFIERS
                minimum: 1
                maximum: 2
                prevent-identical-repeat: true
              warning-times: [1m]
            messages:
              blocked-message-cooldown: 2s
              warning-audience: global
              transition-audience: global
            cobwebs:
              clear-after: 60s
              clear-on-meta-change: true
              clear-on-disable: true
            restriction-targets:
              MACE:
                can-disable: true
                can-cooldown: true
                maximum-cooldown: 60s
            modifiers:
              mace-disabled:
                enabled: false
                weight: 17
                display-name: Legacy Mace
                description: Legacy Description
                effects: []
                restrictions:
                  MACE: {mode: DISABLED}
                start-message: Legacy Start
                end-message: Legacy End
                warning-message: Legacy Warning
              custom-rule:
                enabled: true
                weight: 13
                display-name: Custom Name
                description: Custom Description
                effects: []
                restrictions:
                  MACE:
                    mode: COOLDOWN
                    cooldown: 9s
                start-message: Custom Start
            conflict-groups: {}
            """;
    @Test void schemaFourBuiltInAndCustomMissingCarryoverBecomeFalse()
            throws InvalidConfigurationException {
        YamlConfiguration old = schemaFour();
        YamlConfiguration intermediate = WarzoneMigrationService.migrateSchema4(
                old, WarzoneMigrationService.schemaFiveDefaults(defaults()));
        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema5(intermediate, defaults());

        assertFalse(intermediate.getBoolean(BUILT_IN_CARRYOVER));
        assertFalse(intermediate.getBoolean(CUSTOM_CARRYOVER));
        assertFalse(migrated.getBoolean(BUILT_IN_CARRYOVER));
        assertFalse(migrated.getBoolean(CUSTOM_CARRYOVER));
        assertTrue(defaults().getBoolean(BUILT_IN_CARRYOVER),
                "The fixture proves the bundled schema-7 true value cannot leak.");
        assertPreserved(migrated);
    }

    @Test void schemaFiveBuiltInAndCustomMissingCarryoverBecomeFalse()
            throws InvalidConfigurationException {
        YamlConfiguration old = schemaFive();
        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema5(old, defaults());

        assertFalse(migrated.getBoolean(BUILT_IN_CARRYOVER));
        assertFalse(migrated.getBoolean(CUSTOM_CARRYOVER));
        assertPreserved(migrated);
    }

    @Test void schemaFourAndFivePreserveExplicitFalseAndTrue()
            throws InvalidConfigurationException {
        for (int schema : List.of(SCHEMA_FOUR, 5)) {
            YamlConfiguration old = schema == SCHEMA_FOUR ? schemaFour() : schemaFive();
            old.set(BUILT_IN_CARRYOVER, false);
            old.set(CUSTOM_CARRYOVER, true);
            YamlConfiguration migrated;
            if (schema == SCHEMA_FOUR) {
                YamlConfiguration intermediate = WarzoneMigrationService.migrateSchema4(
                        old, WarzoneMigrationService.schemaFiveDefaults(defaults()));
                migrated = WarzoneMigrationService.migrateSchema5(intermediate, defaults());
            } else migrated = WarzoneMigrationService.migrateSchema5(old, defaults());

            assertFalse(migrated.getBoolean(BUILT_IN_CARRYOVER));
            assertTrue(migrated.getBoolean(CUSTOM_CARRYOVER));
        }
    }

    @Test void schemaSixStillDefaultsMissingCarryoverToFalse()
            throws InvalidConfigurationException {
        YamlConfiguration old = defaults();
        old.set("config-version", 6);
        old.set(BUILT_IN_CARRYOVER, null);
        old.set(CUSTOM_ENABLED, true);
        old.set(CUSTOM_WEIGHT, 13);
        old.set(CUSTOM_DISPLAY_NAME, CUSTOM_NAME);
        old.set(CUSTOM_DESCRIPTION, CUSTOM_DESCRIPTION_TEXT);
        old.set("modifiers.custom-rule.effects", List.of());
        old.set(CUSTOM_RESTRICTION_MODE, DISABLED_MODE);
        old.set(CUSTOM_START_MESSAGE, CUSTOM_START);
        old.set(CUSTOM_CARRYOVER, null);

        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema6(old, defaults());

        assertFalse(migrated.getBoolean(BUILT_IN_CARRYOVER));
        assertFalse(migrated.getBoolean(CUSTOM_CARRYOVER));
    }

    @Test void schemaSixPreservesExplicitFalseAndTrue()
            throws InvalidConfigurationException {
        YamlConfiguration old = defaults();
        old.set("config-version", 6);
        old.set(BUILT_IN_CARRYOVER, false);
        old.set(CUSTOM_ENABLED, true);
        old.set(CUSTOM_WEIGHT, 13);
        old.set(CUSTOM_DISPLAY_NAME, CUSTOM_NAME);
        old.set(CUSTOM_DESCRIPTION, CUSTOM_DESCRIPTION_TEXT);
        old.set("modifiers.custom-rule.effects", List.of());
        old.set(CUSTOM_RESTRICTION_MODE, DISABLED_MODE);
        old.set(CUSTOM_START_MESSAGE, CUSTOM_START);
        old.set(CUSTOM_CARRYOVER, true);

        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema6(old, defaults());

        assertFalse(migrated.getBoolean(BUILT_IN_CARRYOVER));
        assertTrue(migrated.getBoolean(CUSTOM_CARRYOVER));
    }

    @Test void newBundledModifiersAbsentFromLegacySourceKeepBundledCarryover()
            throws InvalidConfigurationException {
        YamlConfiguration old = schemaFive();
        old.set("modifiers.elytra-no-rockets", null);

        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema5(old, defaults());

        assertTrue(migrated.getBoolean("modifiers.elytra-no-rockets.combat-carryover"));
        assertFalse(migrated.getBoolean("modifiers.elytra-no-rockets.enabled"),
                "Absent legacy outcomes remain disabled, but their definition is not rewritten.");
    }

    private void assertPreserved(YamlConfiguration migrated) {
        assertFalse(migrated.getBoolean("modifiers.mace-disabled.enabled"));
        assertEquals(17, migrated.getInt("modifiers.mace-disabled.weight"));
        assertEquals("Legacy Mace", migrated.getString("modifiers.mace-disabled.display-name"));
        assertEquals("Legacy Description", migrated.getString("modifiers.mace-disabled.description"));
        assertEquals("DISABLED", migrated.getString("modifiers.mace-disabled.restrictions.MACE.mode"));
        assertEquals("Legacy Start", migrated.getString("modifiers.mace-disabled.start-message"));
        assertEquals("Legacy End", migrated.getString("modifiers.mace-disabled.end-message"));
        assertEquals("Legacy Warning", migrated.getString("modifiers.mace-disabled.warning-message"));

        assertTrue(migrated.getBoolean(CUSTOM_ENABLED));
        assertEquals(13, migrated.getInt(CUSTOM_WEIGHT));
        assertEquals(CUSTOM_NAME, migrated.getString(CUSTOM_DISPLAY_NAME));
        assertEquals(CUSTOM_DESCRIPTION_TEXT, migrated.getString(CUSTOM_DESCRIPTION));
        assertEquals("COOLDOWN", migrated.getString(CUSTOM_RESTRICTION_MODE));
        assertEquals("9s", migrated.getString("modifiers.custom-rule.restrictions.MACE.cooldown"));
        assertEquals(CUSTOM_START, migrated.getString(CUSTOM_START_MESSAGE));
    }

    private YamlConfiguration schemaFour() throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(SCHEMA_FOUR_YAML);
        return yaml;
    }

    private YamlConfiguration schemaFive() throws InvalidConfigurationException {
        YamlConfiguration yaml = WarzoneMigrationService.schemaFiveDefaults(defaults());
        yaml.set("config-version", 5);
        yaml.set(BUILT_IN_CARRYOVER, null);
        yaml.set("modifiers.mace-disabled.enabled", false);
        yaml.set("modifiers.mace-disabled.weight", 17);
        yaml.set("modifiers.mace-disabled.display-name", "Legacy Mace");
        yaml.set("modifiers.mace-disabled.description", "Legacy Description");
        yaml.set("modifiers.mace-disabled.restrictions.MACE.mode", "DISABLED");
        yaml.set("modifiers.mace-disabled.start-message", "Legacy Start");
        yaml.set("modifiers.mace-disabled.end-message", "Legacy End");
        yaml.set("modifiers.mace-disabled.warning-message", "Legacy Warning");
        yaml.set(CUSTOM_ENABLED, true);
        yaml.set(CUSTOM_WEIGHT, 13);
        yaml.set(CUSTOM_DISPLAY_NAME, CUSTOM_NAME);
        yaml.set(CUSTOM_DESCRIPTION, CUSTOM_DESCRIPTION_TEXT);
        yaml.set("modifiers.custom-rule.effects", List.of());
        yaml.set(CUSTOM_RESTRICTION_MODE, "COOLDOWN");
        yaml.set("modifiers.custom-rule.restrictions.MACE.cooldown", "9s");
        yaml.set(CUSTOM_START_MESSAGE, CUSTOM_START);
        yaml.set(CUSTOM_CARRYOVER, null);
        return yaml;
    }

    private YamlConfiguration defaults() {
        try (InputStream stream = getClass().getResourceAsStream("/warzone.yml")) {
            assertNotNull(stream);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
