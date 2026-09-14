package com.lincoln.maceguard.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MainConfigMigrationServiceTest {
    @Test
    void exactPreviousStockPolicyGainsLavaWithoutChangingSchema() {
        YamlConfiguration config = previousStockConfig();

        assertTrue(MainConfigMigrationService.upgradeStockSchema8BucketDefaults(config));
        assertEquals(ConfigLoader.VERSION, config.getInt("config-version"));
        assertEquals(List.of("WATER", "LAVA"),
                config.getStringList("block-policies.cobweb-box.buckets.empty"));
        assertEquals(List.of("WATER", "LAVA"),
                config.getStringList("block-policies.cobweb-box.buckets.fill"));
    }

    @Test
    void customizedPolicyIsNeverBroadenedAutomatically() {
        YamlConfiguration config = previousStockConfig();
        config.set("block-policies.cobweb-box.place.materials", List.of("COBWEB"));

        assertFalse(MainConfigMigrationService.upgradeStockSchema8BucketDefaults(config));
        assertEquals(List.of("WATER"),
                config.getStringList("block-policies.cobweb-box.buckets.empty"));
        assertEquals(List.of("WATER"),
                config.getStringList("block-policies.cobweb-box.buckets.fill"));
    }

    @Test
    void migrationIsIdempotentAfterLavaWasAdded() {
        YamlConfiguration config = previousStockConfig();
        assertTrue(MainConfigMigrationService.upgradeStockSchema8BucketDefaults(config));

        assertFalse(MainConfigMigrationService.upgradeStockSchema8BucketDefaults(config));
        assertEquals(List.of("WATER", "LAVA"),
                config.getStringList("block-policies.cobweb-box.buckets.empty"));
        assertEquals(List.of("WATER", "LAVA"),
                config.getStringList("block-policies.cobweb-box.buckets.fill"));
    }

    @Test
    void anotherSchemaIsNotTouched() {
        YamlConfiguration config = previousStockConfig();
        config.set("config-version", ConfigLoader.VERSION - 1);

        assertFalse(MainConfigMigrationService.upgradeStockSchema8BucketDefaults(config));
        assertEquals(List.of("WATER"),
                config.getStringList("block-policies.cobweb-box.buckets.empty"));
    }

    private YamlConfiguration previousStockConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", ConfigLoader.VERSION);
        config.set("block-policies.cobweb-box.place.deny-unlisted", true);
        config.set("block-policies.cobweb-box.place.materials", List.of("COBWEB", "ICE"));
        config.set("block-policies.cobweb-box.break.deny-unlisted", true);
        config.set("block-policies.cobweb-box.break.materials", List.of("COBWEB", "ICE"));
        config.set("block-policies.cobweb-box.buckets.empty", List.of("WATER"));
        config.set("block-policies.cobweb-box.buckets.fill", List.of("WATER"));
        config.set("block-policies.cobweb-box.liquids.confine-to-region", true);
        config.set("block-policies.cobweb-box.liquids.block-infinite-water-sources", true);
        config.set("block-policies.cobweb-box.allow-non-player-sources", false);
        return config;
    }
}
