package com.lincoln.maceguard.warzone.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaKitConfigTest {
    @TempDir Path directory;

    @Test void enabledKitMayHaveExplicitlyEmptyModifierList() throws Exception {
        YamlConfiguration yaml = defaultYaml();
        yaml.set("kits.smp.modifiers", java.util.List.of());

        ValidationResult<WarzoneControlConfig> result = load(yaml);

        assertTrue(result.valid(), result.errors().toString());
        assertNotNull(result.value().kits().get("smp"));
        assertTrue(result.value().kits().get("smp").modifierIds().isEmpty());
    }

    @Test void enabledKitMayOmitModifiersForVanillaBehavior() throws Exception {
        YamlConfiguration yaml = defaultYaml();
        yaml.set("kits.smp.modifiers", null);

        ValidationResult<WarzoneControlConfig> result = load(yaml);

        assertTrue(result.valid(), result.errors().toString());
        assertNotNull(result.value().kits().get("smp"));
        assertTrue(result.value().kits().get("smp").modifierIds().isEmpty());
    }

    private YamlConfiguration defaultYaml() {
        return YamlConfiguration.loadConfiguration(
                Path.of("src", "main", "resources", "warzone.yml").toFile());
    }

    private ValidationResult<WarzoneControlConfig> load(YamlConfiguration yaml) throws Exception {
        Path file = directory.resolve("warzone.yml");
        yaml.save(file.toFile());
        return new WarzoneControlConfigLoader().load(file);
    }
}
