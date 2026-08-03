package com.lincoln.maceguard.warzone.runtime;

import com.lincoln.maceguard.warzone.config.WarzoneConfigLoader;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarzoneMigrationServiceTest {
    @TempDir Path directory;

    @Test void schemaFourMigrationPreservesBuiltInsCustomModifiersAndConflictGroups()
            throws InvalidConfigurationException {
        YamlConfiguration old = schemaFourWithCustomModifier();
        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema4(
                old, bundledDefaults());

        assertEquals(5, migrated.getInt("config-version"));
        assertTrue(migrated.getBoolean("enabled"));
        assertEquals("custom-world", migrated.getString("region.world"));
        assertFalse(migrated.getBoolean("modifiers.mace-disabled.enabled"));
        assertEquals(4, migrated.getInt("modifiers.mace-disabled.weight"),
                "Bundled weights remain the migration default when schema 4 had none.");
        assertEquals("<dark_red>Customized No Maces",
                migrated.getString("modifiers.mace-disabled.display-name"));

        assertTrue(migrated.contains("modifiers.custom-mace-rule"));
        assertTrue(migrated.getBoolean("modifiers.custom-mace-rule.enabled"));
        assertEquals(10, migrated.getInt("modifiers.custom-mace-rule.weight"));
        assertEquals("DISABLED", migrated.getString(
                "modifiers.custom-mace-rule.restrictions.MACE.mode"));
        assertEquals(List.of("custom-mace-rule", "mace-cooldown"),
                migrated.getStringList("conflict-groups.custom-mace-mode"));

        Path migratedFile = directory.resolve("migrated.yml");
        assertDoesNotThrow(() -> WarzoneMigrationService.saveValidatedAtomically(
                migrated, migratedFile));
        assertTrue(new WarzoneConfigLoader().load(migratedFile).valid());
    }

    @Test void invalidCustomModifierAbortsWithoutReplacingOriginalFile()
            throws IOException, InvalidConfigurationException {
        Path target = directory.resolve("warzone.yml");
        Files.writeString(target, "original-schema-four\n", StandardCharsets.UTF_8);

        YamlConfiguration old = schemaFourWithCustomModifier();
        old.set("modifiers.custom-mace-rule.display-name", null);
        YamlConfiguration migrated = WarzoneMigrationService.migrateSchema4(
                old, bundledDefaults());

        IOException failure = assertThrows(IOException.class,
                () -> WarzoneMigrationService.saveValidatedAtomically(migrated, target));
        assertTrue(failure.getMessage().contains("did not validate"));
        assertEquals("original-schema-four\n",
                Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(target.resolveSibling("warzone.yml.tmp")));
    }

    private YamlConfiguration schemaFourWithCustomModifier()
            throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                config-version: 4
                enabled: true
                region:
                  world: custom-world
                  id: warzone
                  excluded-region-ids: [spawn, market]
                rotation:
                  schedule:
                    day: MONDAY
                    time: "05:30"
                    timezone: America/Indiana/Indianapolis
                  selection:
                    mode: RANDOM_MODIFIERS
                    minimum: 1
                    maximum: 3
                    prevent-identical-repeat: true
                  warning-times: [10m, 1m]
                messages:
                  blocked-message-cooldown: 3s
                  warning-audience: global
                  transition-audience: warzone
                cobwebs:
                  clear-after: 75s
                  clear-on-meta-change: true
                  clear-on-disable: true
                restriction-targets:
                  MACE:
                    can-disable: true
                    can-cooldown: true
                    maximum-cooldown: 60s
                  SPEAR_LUNGE:
                    can-disable: true
                    can-cooldown: false
                conflict-groups:
                  mace-mode: [mace-disabled, mace-cooldown]
                  custom-mace-mode: [custom-mace-rule, mace-cooldown]
                modifiers:
                  mace-disabled:
                    enabled: false
                    display-name: "<dark_red>Customized No Maces"
                    description: "Customized built-in modifier."
                    effects: []
                    restrictions:
                      MACE:
                        mode: DISABLED
                    start-message: "Maces disabled."
                  custom-mace-rule:
                    display-name: "Custom Mace Rule"
                    description: "A valid custom schema-four modifier."
                    effects: []
                    restrictions:
                      MACE:
                        mode: DISABLED
                    start-message: "Custom rule started."
                """);
        return yaml;
    }

    private YamlConfiguration bundledDefaults() {
        InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("warzone.yml");
        assertNotNull(stream, "Bundled warzone.yml must be available to tests.");
        try (InputStreamReader reader = new InputStreamReader(
                stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
