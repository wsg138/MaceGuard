package com.lincoln.maceguard.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    @Test void missingProfileAndLegacyCoordinatesNeverCreateResetBehavior() {
        YamlConfiguration yaml = base();
        yaml.set("gameplay_zones", java.util.List.of(java.util.Map.of("name", "war-pit", "reset_mode", "AIR", "min", java.util.Map.of("x", 0))));
        var loaded = new ConfigLoader().load(yaml);
        assertTrue(loaded.resetProfiles().isEmpty());
    }

    @Test void missingModeDisablesProfileAndRejectsSchema() {
        YamlConfiguration yaml = base();
        yaml.set("reset-profiles.pit.max-total-changes", 100);
        yaml.set("reset-profiles.pit.max-air-changes", 10);
        var loaded = new ConfigLoader().load(yaml);
        assertFalse(loaded.validSchema());
        assertFalse(loaded.resetProfiles().containsKey("pit"));
    }

    @Test void invalidAndUnknownModesDisableProfile() {
        YamlConfiguration yaml = base();
        yaml.set("reset-profiles.pit.mode", "AIR");
        yaml.set("reset-profiles.pit.max-total-changes", 100);
        yaml.set("reset-profiles.pit.max-air-changes", 10);
        assertFalse(new ConfigLoader().load(yaml).resetProfiles().containsKey("pit"));
    }

    @Test void versionIsNotAcceptedUntilCompleteSchemaValidates() {
        YamlConfiguration yaml = base();
        yaml.set("config-version", 6);
        assertFalse(new ConfigLoader().load(yaml).validSchema());
        yaml.set("config-version", 7);
        assertTrue(new ConfigLoader().load(yaml).validSchema());
    }
    @Test void missingVersionCannotBeAcceptedFromDefaults() {
        YamlConfiguration yaml = base();
        yaml.set("config-version", null);
        assertFalse(new ConfigLoader().load(yaml).validSchema());
    }
    @Test void profileRequiresExplicitCoordinateAndDestructiveLimits() {
        YamlConfiguration yaml = base();
        yaml.set("reset-profiles.pit.mode", "FULL_SNAPSHOT");
        yaml.set("reset-profiles.pit.max-total-changes", 100);
        yaml.set("reset-profiles.pit.max-air-changes", 10);
        assertFalse(new ConfigLoader().load(yaml).resetProfiles().containsKey("pit"));
        yaml.set("reset-profiles.pit.max-coordinates", 1000);
        assertTrue(new ConfigLoader().load(yaml).resetProfiles().containsKey("pit"));
    }

    private YamlConfiguration base() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("config-version", 7);
        yaml.set("mace-durability.damage-per-armor-piece", 2);
        yaml.set("temporary-blocks.cobweb-ttl-seconds", 60);
        yaml.set("temporary-blocks.max-tracked-blocks", 100);
        yaml.set("performance.capture-batch-size", 10);
        yaml.set("performance.plan-batch-size", 10);
        yaml.set("performance.restore-batch-size", 10);
        return yaml;
    }
}
